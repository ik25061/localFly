package com.example.localfly

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.ApiService
import com.example.localfly.network.HideRequest
import com.example.localfly.network.LikeRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import com.example.localfly.utils.LocalLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service que mantiene la reproducción de música sonando en segundo plano,
 * gestiona la cola de canciones (siguiente/anterior) y se integra con el
 * sistema mediante MediaSessionService.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_LOCAL_BIND = "com.example.localfly.ACTION_LOCAL_BIND"

        const val FADE_DURATION_MS = 2500L
        const val FADE_STEP_MS = 100L

        const val COMMAND_LIKE = "com.example.localfly.COMMAND_LIKE"
        const val COMMAND_DISLIKE = "com.example.localfly.COMMAND_DISLIKE"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val serverBaseUrl = ApiConfig.BASE_URL

    var player: ExoPlayer? = null
        private set

    private var crossfadeEnabled = false
    private var fadeOutStartedForCurrentSong = false
    private var fadeJob: kotlinx.coroutines.Job? = null

    private val fadeTickerHandler = Handler(Looper.getMainLooper())
    private val fadeTickerRunnable = object : Runnable {
        override fun run() {
            checkFadeOutTrigger()
            fadeTickerHandler.postDelayed(this, 250)
        }
    }

    private var mediaSession: MediaSession? = null

    var currentSong: Song? = null
        private set

    var queue: List<Song> = emptyList()
        private set

    private var queueLocalPaths: List<String?> = emptyList()

    var currentIndex: Int = -1
        private set

    var onStateChanged: (() -> Unit)? = null

    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        LocalLogger.log(this, "PlaybackService iniciado (MediaSessionService)")
        sessionManager = SessionManager(this)
        downloadHelper = DownloadManagerHelper.getInstance(this)

        crossfadeEnabled = sessionManager.isCrossfadeEnabled()

        player = ExoPlayer.Builder(this).build()
        player?.skipSilenceEnabled = crossfadeEnabled
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onStateChanged?.invoke()
                if (isPlaying && crossfadeEnabled) {
                    fadeTickerHandler.post(fadeTickerRunnable)
                } else {
                    fadeTickerHandler.removeCallbacks(fadeTickerRunnable)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    next()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    handleAutoAdvance()
                }
            }
        })

        setupMediaSession()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )

        syncOfflineActions()
    }

    private fun setupMediaSession() {
        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(COMMAND_LIKE, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_DISLIKE, Bundle.EMPTY))
                    .build()
                
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
                
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    COMMAND_LIKE -> toggleLike()
                    COMMAND_DISLIKE -> dislikeCurrentSong()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                when (playerCommand) {
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                        dislikeCurrentSong()
                        return SessionResult.RESULT_SUCCESS
                    }
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                        toggleLike()
                        return SessionResult.RESULT_SUCCESS
                    }
                }
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(callback)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
            
        updateMediaSessionCustomLayout()
    }

    private fun updateMediaSessionCustomLayout() {
        val song = currentSong
        val likeIcon = if (song?.liked == true) R.drawable.ic_like_on else R.drawable.ic_like_off
        
        val likeButton = CommandButton.Builder(CommandButton.ICON_HEART_FILLED)
            .setSessionCommand(SessionCommand(COMMAND_LIKE, Bundle.EMPTY))
            .setIconResId(likeIcon)
            .setDisplayName("Me gusta")
            .build()

        val dislikeButton = CommandButton.Builder(CommandButton.ICON_BLOCK)
            .setSessionCommand(SessionCommand(COMMAND_DISLIKE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_dislike_off)
            .setDisplayName("No me gusta")
            .build()

        // Enviar botones custom. El sistema los ubicará según la versión de Android.
        mediaSession?.setCustomLayout(listOf(likeButton, dislikeButton))
    }

    fun setCrossfadeEnabled(enabled: Boolean) {
        crossfadeEnabled = enabled
        sessionManager.setCrossfadeEnabled(enabled)
        player?.skipSilenceEnabled = enabled
        if (!enabled) {
            fadeJob?.cancel()
            player?.volume = 1f
            fadeTickerHandler.removeCallbacks(fadeTickerRunnable)
        } else if (player?.isPlaying == true) {
            fadeTickerHandler.post(fadeTickerRunnable)
        }
    }

    private fun checkFadeOutTrigger() {
        if (!crossfadeEnabled) return
        val p = player ?: return
        if (!p.isPlaying) return
        val duration = p.duration
        if (duration <= 0) return
        val remaining = duration - p.currentPosition
        if (!fadeOutStartedForCurrentSong && remaining in 0..FADE_DURATION_MS) {
            fadeOutStartedForCurrentSong = true
            startFade(from = p.volume, to = 0f, durationMs = remaining.coerceAtMost(FADE_DURATION_MS))
        }
    }

    private fun startFade(from: Float, to: Float, durationMs: Long) {
        fadeJob?.cancel()
        if (durationMs <= 0) {
            player?.volume = to
            return
        }
        fadeJob = serviceScope.launch {
            val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val progress = i / steps.toFloat()
                player?.volume = from + (to - from) * progress
                delay(FADE_STEP_MS)
            }
            player?.volume = to
        }
    }

    private fun syncOfflineActions() {
        serviceScope.launch(Dispatchers.IO) {
            while (true) {
                val pendingLikes = sessionManager.getPendingLikes()
                val pendingDislikes = sessionManager.getPendingDislikes()
                if (pendingLikes.isEmpty() && pendingDislikes.isEmpty()) {
                    delay(30000)
                    continue
                }
                var anySuccess = false
                pendingLikes.forEach { (songId, liked) ->
                    try {
                        val response = RetrofitClient.api.likeSong(songId, LikeRequest(sessionManager.getUserId(), liked))
                        if (response.isSuccessful) {
                            sessionManager.removePendingLike(songId)
                            anySuccess = true
                        }
                    } catch (e: Exception) {}
                }
                pendingDislikes.forEach { songId ->
                    try {
                        val response = RetrofitClient.api.hideSong(songId, HideRequest(sessionManager.getUserId()))
                        if (response.isSuccessful) {
                            sessionManager.removePendingDislike(songId)
                            anySuccess = true
                        }
                    } catch (e: Exception) {}
                }
                if (anySuccess) withContext(Dispatchers.Main) { onStateChanged?.invoke() }
                delay(15000) 
            }
        }
    }

    private fun checkAutoDelete(song: Song?, index: Int) {
        if (song == null || !sessionManager.isAutoDeleteEnabled()) return
        val localPath = queueLocalPaths.getOrNull(index)
        if (localPath == null && !downloadHelper.isDownloaded(song.id)) return

        serviceScope.launch {
            delay(300)
            try {
                withContext(Dispatchers.IO) { downloadHelper.removeDownload(song.id) }
            } catch (e: Exception) {}
            val mutablePaths = queueLocalPaths.toMutableList()
            if (index in mutablePaths.indices) {
                mutablePaths[index] = null
                queueLocalPaths = mutablePaths
            }
            withContext(Dispatchers.Main) { onStateChanged?.invoke() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    fun setQueueAndPlay(songs: List<Song>, startIndex: Int, localPaths: List<String?>? = null) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        queue = songs
        queueLocalPaths = localPaths ?: List(songs.size) { null }
        currentIndex = startIndex
        playCurrentIndex()
    }

    fun playSong(song: Song, localFilePath: String? = null) {
        setQueueAndPlay(listOf(song), 0, listOf(localFilePath))
    }

    fun playNext(song: Song) {
        if (queue.isEmpty()) { playSong(song); return }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        val insertIndex = currentIndex + 1
        mutableQueue.add(insertIndex, song)
        mutablePaths.add(insertIndex, null)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    fun addToQueue(song: Song) {
        if (queue.isEmpty()) { playSong(song); return }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.add(song)
        mutablePaths.add(null)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    fun addListToQueue(songs: List<Song>) {
        if (queue.isEmpty()) { setQueueAndPlay(songs, 0); return }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.addAll(songs)
        mutablePaths.addAll(List(songs.size) { null })
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == currentIndex || toIndex == currentIndex) return
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        val song = mutableQueue.removeAt(fromIndex)
        val path = mutablePaths.removeAt(fromIndex)
        mutableQueue.add(toIndex, song)
        mutablePaths.add(toIndex, path)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        currentIndex = when {
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        onStateChanged?.invoke()
    }

    fun removeFromQueue(index: Int) {
        if (index == currentIndex || index !in queue.indices) return
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.removeAt(index)
        mutablePaths.removeAt(index)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        if (index < currentIndex) currentIndex--
        onStateChanged?.invoke()
    }

    fun updateFullQueue(newSongs: List<Song>) {
        val currentSongId = currentSong?.id
        queue = newSongs
        queueLocalPaths = newSongs.map { downloadHelper.getLocalFilePath(it.id) }
        if (currentSongId != null) {
            val newIdx = newSongs.indexOfFirst { it.id == currentSongId }
            if (newIdx != -1) currentIndex = newIdx
        }
        onStateChanged?.invoke()
    }

    private fun handleAutoAdvance() {
        if (!hasNext()) return
        val songToHandle = currentSong
        val indexToHandle = currentIndex
        currentIndex++
        currentSong = queue.getOrNull(currentIndex)
        fadeOutStartedForCurrentSong = false
        fadeJob?.cancel()
        if (crossfadeEnabled) {
            player?.volume = 0f
            startFade(from = 0f, to = 1f, durationMs = FADE_DURATION_MS)
        } else {
            player?.volume = 1f
        }
        if (hasNext()) {
            val nextSong = queue[currentIndex + 1]
            val nextLocalPath = queueLocalPaths.getOrNull(currentIndex + 1)
            val nextMetaBuilder = MediaMetadata.Builder().setTitle(nextSong.title).setArtist(nextSong.artist)
            if (nextLocalPath == null) nextMetaBuilder.setArtworkUri(Uri.parse("$serverBaseUrl/cover/${nextSong.id}"))
            val nextMediaItem = MediaItem.Builder()
                .setUri(if (nextLocalPath != null) Uri.fromFile(File(nextLocalPath)) else Uri.parse("$serverBaseUrl/audio/${nextSong.id}"))
                .setMediaMetadata(nextMetaBuilder.build())
                .build()
            player?.addMediaItem(nextMediaItem)
        }
        onStateChanged?.invoke()
        checkAutoDelete(songToHandle, indexToHandle)
    }

    fun next() {
        val songToHandle = currentSong
        val indexToHandle = currentIndex
        if (currentIndex + 1 < queue.size) {
            currentIndex++
            playCurrentIndex()
            checkAutoDelete(songToHandle, indexToHandle)
        } else {
            player?.stop()
            player?.clearMediaItems()
            checkAutoDelete(songToHandle, indexToHandle)
            currentSong = null
            currentIndex = -1
            onStateChanged?.invoke()
        }
    }

    fun prev() {
        if (currentIndex > 0) {
            currentIndex--
            playCurrentIndex()
        }
    }

    fun hasNext(): Boolean = currentIndex + 1 < queue.size
    fun hasPrev(): Boolean = currentIndex > 0
    fun getProgressMs(): Long = player?.currentPosition ?: 0L
    fun getDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }

    private fun playCurrentIndex() {
        val song = queue.getOrNull(currentIndex) ?: return
        val localPath = queueLocalPaths.getOrNull(currentIndex)
        currentSong = song
        val metaBuilder = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
        if (localPath == null) metaBuilder.setArtworkUri(Uri.parse("$serverBaseUrl/cover/${song.id}"))
        val mediaItem = MediaItem.Builder()
            .setUri(if (localPath != null) Uri.fromFile(File(localPath)) else Uri.parse("$serverBaseUrl/audio/${song.id}"))
            .setMediaMetadata(metaBuilder.build())
            .build()
        player?.setMediaItem(mediaItem)
        if (hasNext()) {
            val nextSong = queue[currentIndex + 1]
            val nextLocalPath = queueLocalPaths.getOrNull(currentIndex + 1)
            val nextMetaBuilder = MediaMetadata.Builder().setTitle(nextSong.title).setArtist(nextSong.artist)
            if (nextLocalPath == null) nextMetaBuilder.setArtworkUri(Uri.parse("$serverBaseUrl/cover/${nextSong.id}"))
            val nextMediaItem = MediaItem.Builder()
                .setUri(if (nextLocalPath != null) Uri.fromFile(File(nextLocalPath)) else Uri.parse("$serverBaseUrl/audio/${nextSong.id}"))
                .setMediaMetadata(nextMetaBuilder.build())
                .build()
            player?.addMediaItem(nextMediaItem)
        }
        player?.prepare()
        fadeOutStartedForCurrentSong = false
        fadeJob?.cancel()
        player?.volume = if (crossfadeEnabled) 0f else 1f
        player?.play()
        if (crossfadeEnabled) startFade(from = 0f, to = 1f, durationMs = FADE_DURATION_MS)
        updateMediaSessionCustomLayout()
        onStateChanged?.invoke()
    }

    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun toggleShuffle() { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; onStateChanged?.invoke() } }
    fun toggleRepeat() { player?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; onStateChanged?.invoke() } }

    fun toggleLike() {
        val song = currentSong ?: return
        val newLiked = !song.liked
        currentSong = song.copy(liked = newLiked)
        com.example.localfly.ai.AIWeightsStore(this).reinforce(song.id, if (newLiked) 1f else -0.5f)
        downloadHelper.updateLiked(song.id, newLiked)
        updateMediaSessionCustomLayout()
        onStateChanged?.invoke()
        serviceScope.launch {
            try {
                val response = RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
                if (!response.isSuccessful) sessionManager.addPendingLike(song.id, newLiked)
            } catch (e: Exception) { sessionManager.addPendingLike(song.id, newLiked) }
        }
    }

    fun dislikeCurrentSong() {
        val songToHide = currentSong ?: return
        com.example.localfly.ai.AIWeightsStore(this).reinforce(songToHide.id, -1f)
        serviceScope.launch {
            try {
                val response = RetrofitClient.api.hideSong(songToHide.id, HideRequest(sessionManager.getUserId()))
                if (!response.isSuccessful) sessionManager.addPendingDislike(songToHide.id)
            } catch (e: Exception) { sessionManager.addPendingDislike(songToHide.id) }
        }
        next()
    }

    fun flushPendingLyricsUploads() {
        val pending = sessionManager.getPendingLyricsUploads()
        if (pending.isEmpty()) return
        serviceScope.launch {
            for ((songId, content) in pending) {
                try {
                    val response = RetrofitClient.api.saveLyricsFile(songId, ApiService.SaveLyricsFileRequest(content))
                    if (response.isSuccessful) sessionManager.removePendingLyricsUpload(songId)
                } catch (e: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        fadeTickerHandler.removeCallbacks(fadeTickerRunnable)
        fadeJob?.cancel()
        serviceScope.cancel()
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}