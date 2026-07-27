package com.example.localfly

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.network.Song

class DownloadsActivity : AppCompatActivity() {

    private lateinit var rvDownloads: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var miniPlayer: View
    private lateinit var miniPlayerInfo: View
    private lateinit var ivMiniCover: ImageView
    private lateinit var tvNowPlayingTitle: TextView
    private lateinit var tvNowPlayingArtist: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnMiniLike: ImageButton
    private lateinit var btnMiniDislike: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var adapter: DownloadedSongAdapter

    private var playbackService: PlaybackService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            playbackService?.onStateChanged = { refreshMiniPlayer() }
            refreshMiniPlayer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        downloadHelper = DownloadManagerHelper(this)

        rvDownloads = findViewById(R.id.rvDownloads)
        tvEmpty = findViewById(R.id.tvEmptyDownloads)
        miniPlayer = findViewById(R.id.miniPlayer)
        miniPlayerInfo = findViewById(R.id.miniPlayerInfo)
        ivMiniCover = findViewById(R.id.ivMiniCover)
        tvNowPlayingTitle = findViewById(R.id.tvNowPlayingTitle)
        tvNowPlayingArtist = findViewById(R.id.tvNowPlayingArtist)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnMiniLike = findViewById(R.id.btnMiniLike)
        btnMiniDislike = findViewById(R.id.btnMiniDislike)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        miniPlayerInfo.setOnClickListener {
            if (playbackService?.currentSong != null) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }
        btnPlayPause.setOnClickListener { playbackService?.togglePlayPause() }
        btnMiniLike.setOnClickListener { playbackService?.toggleLike() }
        btnMiniDislike.setOnClickListener { playbackService?.dislikeCurrentSong() }
        btnPrev.setOnClickListener { playbackService?.prev() }
        btnNext.setOnClickListener { playbackService?.next() }

        adapter = DownloadedSongAdapter(
            mutableListOf(),
            onItemClick = { downloaded -> playDownloadedFromQueue(downloaded) },
            onDeleteClick = { downloaded ->
                downloadHelper.removeDownload(downloaded.id)
                loadDownloads()
            }
        )
        rvDownloads.layoutManager = LinearLayoutManager(this)
        rvDownloads.adapter = adapter

        loadDownloads()
    }

    private fun loadDownloads() {
        val items = downloadHelper.getDownloadedSongs()
        adapter.updateItems(items)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvDownloads.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Reproduce la canción tocada, con el resto de descargas como cola (siguiente/anterior) */
    private fun playDownloadedFromQueue(downloaded: DownloadedSong) {
        val allDownloads = downloadHelper.getDownloadedSongs()
        val startIndex = allDownloads.indexOfFirst { it.id == downloaded.id }
        if (startIndex == -1) return

        val songs = allDownloads.map {
            Song(
                id = it.id,
                title = it.title,
                artist = it.artist,
                album = null,
                year = null,
                duration = null,
                liked = false,
                hasCover = false
            )
        }
        val localPaths = allDownloads.map { downloadHelper.getLocalFilePath(it.id) }

        playbackService?.setQueueAndPlay(songs, startIndex, localPaths)
    }

    private fun refreshMiniPlayer() {
        val song = playbackService?.currentSong
        if (song == null) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        tvNowPlayingTitle.text = song.title
        tvNowPlayingArtist.text = song.artist ?: "Desconocido"
        ivMiniCover.setImageDrawable(null) // sin portada cacheada offline por ahora

        btnMiniLike.setImageResource(
            if (song.liked) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )

        val isPlaying = playbackService?.player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        btnPrev.alpha = if (playbackService?.hasPrev() == true) 1f else 0.4f
        btnNext.alpha = if (playbackService?.hasNext() == true) 1f else 0.4f
    }

    override fun onStart() {
        super.onStart()
        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}