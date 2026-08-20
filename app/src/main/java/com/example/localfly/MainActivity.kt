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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.fragments.AIFragment
import com.example.localfly.fragments.DownloadsFragment
import com.example.localfly.fragments.HomeFragment
import com.example.localfly.fragments.LibraryFragment
import com.example.localfly.fragments.PlaylistsFragment
import com.example.localfly.network.*
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // ===== VARIABLES =====
    private lateinit var sessionManager: SessionManager
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var adapter: SongAdapter

    // Mini player views
    private lateinit var miniPlayer: View
    private lateinit var miniPlayerInfo: View
    private lateinit var ivMiniCover: ImageView
    private lateinit var tvNowPlayingTitle: TextView
    private lateinit var tvNowPlayingArtist: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnMiniLike: ImageButton
    private lateinit var btnMiniDislike: ImageButton
    private var btnPrev: ImageButton? = null
    private lateinit var btnNext: ImageButton

    var playbackService: PlaybackService? = null
        private set
    private var isBound = false

    // Base URL del servidor (debe coincidir con RetrofitClient/ApiConfig)
    private val serverBaseUrl = ApiConfig.BASE_URL

    // ===== SERVICE CONNECTION =====
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

    // ===== ON CREATE =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionManager = SessionManager(this)
        downloadHelper = DownloadManagerHelper.getInstance(this)

        // Verificar sesión
        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Inicializar mini player
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

        miniPlayerInfo.setOnClickListener {
            if (playbackService?.currentSong != null) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }
        btnPlayPause.setOnClickListener { playbackService?.togglePlayPause() }
        btnMiniLike.setOnClickListener { playbackService?.toggleLike() }
        btnMiniDislike.setOnClickListener { playbackService?.dislikeCurrentSong() }
        btnPrev?.setOnClickListener { playbackService?.prev() }
        btnNext.setOnClickListener { playbackService?.next() }

        // ===== CONFIGURAR ADAPTADOR =====
        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = serverBaseUrl,
            downloadHelper = downloadHelper,
            onSongClick = { song, position ->
                val allSongs = adapter.currentSongs()
                val localPaths = allSongs.map { downloadHelper.getLocalFilePath(it.id) }
                playbackService?.setQueueAndPlay(allSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLikeInLibrary(song, position) },
            onDislikeClick = { song, position -> hideSongInLibrary(song, position) },
            onDownloadClick = { song -> toggleDownload(song) },
            onDeleteClick = { song, position -> hideSongInLibrary(song, position) },
            onPlayNextClick = { song -> playbackService?.playNext(song) },
            onPlaylistAddClick = { song -> playbackService?.addToQueue(song) },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(this, lifecycleScope, song, sessionManager)
            }
        )

        // Aquí debes asignar el adaptador a tu RecyclerView (rvSongs)
        // Si no tienes rvSongs en activity_main.xml, agrégala o usa el contenedor que tengas.
        // Por ahora, lo dejamos comentado para que no dé error.
        // val rvSongs = findViewById<RecyclerView>(R.id.rvSongs)
        // rvSongs.layoutManager = LinearLayoutManager(this)
        // rvSongs.adapter = adapter

        // ===== NAVEGACIÓN INFERIOR =====
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    replaceFragment(HomeFragment())
                    true
                }
                R.id.nav_library -> {
                    replaceFragment(LibraryFragment())
                    true
                }
                R.id.nav_downloads -> {
                    replaceFragment(DownloadsFragment())
                    true
                }
                R.id.nav_playlists -> {
                    replaceFragment(PlaylistsFragment())
                    true
                }
                R.id.nav_ai -> {
                    replaceFragment(AIFragment())
                    true
                }
                else -> false
            }
        }

        // Cargar fragmento inicial
        if (savedInstanceState == null) {
            replaceFragment(HomeFragment())
            bottomNav.selectedItemId = R.id.nav_home
        }

        // Cargar la biblioteca (opcional, si quieres que se cargue al inicio)
        // loadLibrary()
    }

    // ===== MÉTODOS =====

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    private fun refreshMiniPlayer() {
        val song = playbackService?.currentSong
        if (song == null) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        tvNowPlayingTitle.text = song.title
        
        // Ajuste solicitado: "nombre del artista después de la palabra artista: artist - A.B. Quintanilla III"
        tvNowPlayingArtist.text = if (song.artist != null) "Artista: ${song.artist}" else "Artista desconocido"

        val coverUrl = "$serverBaseUrl/cover/${song.id}"

        Glide.with(this)
            .load(coverUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .centerCrop()
            .into(ivMiniCover)

        btnMiniLike.setImageResource(
            if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off
        )
        
        btnMiniDislike.setImageResource(R.drawable.ic_dislike_off)

        val isPlaying = playbackService?.player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        btnPrev?.alpha = if (playbackService?.hasPrev() == true) 1f else 0.4f
        btnNext.alpha = if (playbackService?.hasNext() == true) 1f else 0.4f
    }

    // ===== DESCARGA =====
    private fun toggleDownload(song: Song) {
        if (downloadHelper.isDownloaded(song.id)) {
            downloadHelper.removeDownload(song.id)
            Toast.makeText(this, "Descarga eliminada", Toast.LENGTH_SHORT).show()
            adapter.refreshDownloadStates()
        } else {
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
    }

    // ===== REPRODUCIR DESDE DESCARGA =====
    fun playDownloadedSong(downloaded: DownloadedSong) {
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
                duration = it.duration,
                liked = it.liked,
                hasCover = it.hasCover,
                hasLyrics = it.hasLyrics
            )
        }
        val localPaths = allDownloads.map { downloadHelper.getLocalFilePath(it.id) }

        playbackService?.setQueueAndPlay(songs, startIndex, localPaths)
    }

    // ===== LIKES / DISLIKES =====
    private fun toggleLikeInLibrary(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.likeSong(
                    song.id,
                    LikeRequest(sessionManager.getUserId(), newLiked)
                )
                if (!response.isSuccessful) {
                    sessionManager.addPendingLike(song.id, newLiked)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar el like para sincronizarlo al volver al servidor
                sessionManager.addPendingLike(song.id, newLiked)
            }
        }
    }

    private fun hideSongInLibrary(song: Song, position: Int) {
        adapter.removeAt(position)
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.hideSong(song.id, HideRequest(sessionManager.getUserId()))
                if (!response.isSuccessful) {
                    sessionManager.addPendingDislike(song.id)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar el dislike para sincronizarlo al volver al servidor
                sessionManager.addPendingDislike(song.id)
            }
        }
    }

    // ===== CARGAR BIBLIOTECA (opcional) =====
    private fun loadLibrary() {
        // Implementación similar a la de MainActivity original
        // Puedes dejarla vacía si no la necesitas
    }

    // ===== CICLO DE VIDA =====
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

    fun playSongWithQueue(songs: List<Song>, startIndex: Int) {
        val localPaths = songs.map { downloadHelper.getLocalFilePath(it.id) }
        playbackService?.setQueueAndPlay(songs, startIndex, localPaths)
    }
    fun playSongs(songs: List<Song>, startIndex: Int, localPaths: List<String?>) {
        playbackService?.setQueueAndPlay(songs, startIndex, localPaths)
    }
}