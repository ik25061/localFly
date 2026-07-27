package com.example.localfly

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.network.HideRequest
import com.example.localfly.network.LikeRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // Debe coincidir con la BASE_URL de RetrofitClient (sin la barra final)
    private val serverBaseUrl = "http://127.0.0.1:5002"

    private lateinit var sessionManager: SessionManager
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var rvSongs: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var adapter: SongAdapter

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

    private var playbackService: PlaybackService? = null
    private var isBound = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

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
        setContentView(R.layout.activity_main)

        sessionManager = SessionManager(this)
        downloadHelper = DownloadManagerHelper(this)

        rvSongs = findViewById(R.id.rvSongs)
        progressBar = findViewById(R.id.progressBarLibrary)
        tvError = findViewById(R.id.tvLibraryError)
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

        findViewById<Button>(R.id.btnGoDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

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

        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = serverBaseUrl,
            downloadHelper = downloadHelper,
            onSongClick = { song, position ->
                playbackService?.setQueueAndPlay(adapter.currentSongs(), position)
            },
            onLikeClick = { song, position -> toggleLikeInLibrary(song, position) },
            onDislikeClick = { song, position -> hideSongInLibrary(song, position) },
            onDownloadClick = { song -> downloadSong(song) }
        )
        rvSongs.layoutManager = LinearLayoutManager(this)
        rvSongs.adapter = adapter

        requestNotificationPermissionIfNeeded()
        loadLibrary()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun toggleLikeInLibrary(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        lifecycleScope.launch {
            try {
                RetrofitClient.api.likeSong(
                    song.id,
                    LikeRequest(sessionManager.getUserId(), newLiked)
                )
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo actualizar (sin conexión)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideSongInLibrary(song: Song, position: Int) {
        adapter.removeAt(position)
        lifecycleScope.launch {
            try {
                RetrofitClient.api.hideSong(song.id, HideRequest(sessionManager.getUserId()))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo ocultar (sin conexión)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadSong(song: Song) {
        Toast.makeText(this, "Descargando \"${song.title}\"...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val audioUrl = "$serverBaseUrl/audio/${song.id}"
            val success = downloadHelper.download(song, audioUrl)
            if (success) {
                Toast.makeText(this@MainActivity, "Descarga completa", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Error al descargar", Toast.LENGTH_SHORT).show()
            }
            adapter.refreshDownloadStates()
        }
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

        if (song.hasCover) {
            Glide.with(this)
                .load("$serverBaseUrl/cover/${song.id}")
                .centerCrop()
                .into(ivMiniCover)
        } else {
            ivMiniCover.setImageDrawable(null)
        }

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

    private fun loadLibrary() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val userId = sessionManager.getUserId()
                val response = RetrofitClient.api.getLibrary(userId = userId, limit = 100, offset = 0)

                if (response.isSuccessful && response.body() != null) {
                    val songs = response.body()!!.songs
                    adapter.updateSongs(songs)
                } else {
                    tvError.text = "No se pudo cargar la biblioteca (código ${response.code()})"
                    tvError.visibility = View.VISIBLE
                }
            } catch (e: IOException) {
                tvError.text = "No se pudo conectar con el servidor"
                tvError.visibility = View.VISIBLE
            } catch (e: Exception) {
                tvError.text = "Error inesperado: ${e.message}"
                tvError.visibility = View.VISIBLE
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, PlaybackService::class.java).also { intent ->
            startService(intent)
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