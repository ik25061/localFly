package com.example.localfly

import android.content.*
import android.os.IBinder
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localfly.network.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in: run only on a disposable emulator with an empty downloads library. */
@RunWith(AndroidJUnit4::class)
class OfflinePlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test
    fun likeDoesNotReplayAndAutoDeleteRemovesCompletedDownloads() {
        assumeTrue("Requires dedicated emulator", InstrumentationRegistry.getArguments()
            .getString("dedicatedPlaybackTest") == "true")
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("localfly_downloads", Context.MODE_PRIVATE)
        val helper = DownloadManagerHelper.getInstance(context)
        assumeTrue("Never replace existing downloads", helper.getDownloadedSongs().isEmpty())
        val session = SessionManager(context)
        val oldAutoDelete = session.isAutoDeleteEnabled()
        val oldCrossfade = session.isCrossfadeEnabled()
        val oldUrl = RetrofitClient.getBaseUrl()
        val oldOnline = ServerReachability.isOnline
        val originalList = prefs.getString("list", null)
        val files = (1..4).map { File(context.filesDir, "playback-regression-$it.wav") }
        val songs = files.mapIndexed { i, _ ->
            Song("playback-regression-${i + 1}", "Test ${i + 1}", "Test artist", null,
                null, 3.0, null, null, false, false, genre = listOf("Test"))
        }
        var service: PlaybackService? = null
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as PlaybackService.LocalBinder).getService()
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        var bound = false
        try {
            files.forEach { writeWav(it) }
            val downloads = songs.mapIndexed { i, song ->
                DownloadedSong(song.id, song.title, song.artist, files[i].absolutePath,
                    duration = 3.0, genre = listOf("Test"))
            }
            assertTrue(prefs.edit().putString("list", Gson().toJson(downloads)).commit())
            session.setAutoDeleteEnabled(true)
            session.setCrossfadeEnabled(false)
            RetrofitClient.setBaseUrl("http://127.0.0.1:1")
            ServerReachability.isOnline = false
            bound = context.bindService(Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_LOCAL_BIND), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            assertTrue("Service did not bind", connected.await(10, TimeUnit.SECONDS))
            val playback = requireNotNull(service)
            verifyPlayback(playback, songs, files, helper, session)
        } finally {
            main { service?.player?.stop() }
            if (bound) context.unbindService(connection)
            context.stopService(Intent(context, PlaybackService::class.java))
            main { songs.forEach { helper.removeDownload(it.id); session.removePendingLike(it.id) } }
            files.forEach { it.delete() }
            prefs.edit().apply {
                if (originalList == null) remove("list") else putString("list", originalList)
            }.commit()
            session.setAutoDeleteEnabled(oldAutoDelete)
            session.setCrossfadeEnabled(oldCrossfade)
            RetrofitClient.setBaseUrl(oldUrl)
            ServerReachability.isOnline = oldOnline
        }
    }

    private fun verifyPlayback(playback: PlaybackService, songs: List<Song>, files: List<File>,
        helper: DownloadManagerHelper, session: SessionManager) {
        val transitions = mutableListOf<String>()
        var expected = emptyList<String>()
        main {
            val player = requireNotNull(playback.player)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    item?.let {
                        transitions.add(it.mediaId)
                        android.util.Log.i("PlaybackRegression", "transition=${it.mediaId} reason=$reason")
                    }
                }
            })
            playback.playSong(songs.first(), files.first().absolutePath)
            expected = playback.queue.map { it.id }
            assertEquals(4, expected.size)
        }
        await("First track must play") { playback.player?.isPlaying == true && playback.getProgressMs() >= 300 }
        main {
            val player = requireNotNull(playback.player)
            val position = player.currentPosition
            val count = player.mediaItemCount
            playback.toggleLike()
            assertTrue(playback.currentSong!!.liked)
            assertEquals(songs.first().id, player.currentMediaItem?.mediaId)
            assertEquals(position, player.currentPosition)
            assertEquals(count, player.mediaItemCount)
        }
        await("All four tracks must finish naturally", 25000) {
            playback.currentSong == null && transitions.size >= 4
        }
        await("Completed downloads must be deleted") {
            files.none { it.exists() } && helper.getDownloadedSongs().isEmpty()
        }
        main {
            assertEquals("No replay or skipped tracks", expected, transitions)
            assertTrue(playback.queue.isEmpty())
            assertEquals(0, playback.player!!.mediaItemCount)
            assertEquals(true, session.getPendingLikes()[songs.first().id])
            android.util.Log.i("PlaybackRegression", "PASS: like -> natural advance; four unique tracks; downloads deleted")
        }
    }

    private fun await(message: String, timeoutMs: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun writeWav(file: File) {
        val rate = 16000
        val samples = rate * 3
        val dataBytes = samples * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
        wav.putShort(2).putShort(16).put("data".toByteArray()).putInt(dataBytes)
        repeat(samples) { i ->
            wav.putShort((kotlin.math.sin(2 * Math.PI * 440 * i / rate) * 1500).toInt().toShort())
        }
        file.writeBytes(wav.array())
    }
}
