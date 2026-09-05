package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.network.*
import com.example.localfly.utils.CoverPlaceholder
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class AlbumDetailFragment : Fragment() {

    private var albumId: String? = null
    private var albumName: String? = null
    private var artistName: String? = null
    private var coverId: String? = null

    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    private lateinit var ivCover: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvInfo: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnAddPlaylist: ImageButton

    private var currentSongs: List<Song> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            albumId = it.getString(ARG_ALBUM_ID)
            albumName = it.getString(ARG_ALBUM_NAME)
            artistName = it.getString(ARG_ARTIST_NAME)
            coverId = it.getString(ARG_COVER_ID)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_album_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())

        ivCover = view.findViewById(R.id.ivAlbumCover)
        tvName = view.findViewById(R.id.tvAlbumName)
        tvInfo = view.findViewById(R.id.tvAlbumInfo)
        btnPlay = view.findViewById(R.id.btnPlayAlbum)
        btnDownload = view.findViewById(R.id.btnDownloadAlbum)
        btnAddPlaylist = view.findViewById(R.id.btnAddAlbumToPlaylist)
        rvSongs = view.findViewById(R.id.rvAlbumSongs)

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        tvName.text = albumName
        
        val serverBaseUrl = ApiConfig.BASE_URL
        val seed = albumName ?: albumId ?: "Álbum"

        if (coverId.isNullOrBlank()) {
            // Sin cover conocida no llamamos a una URL "cover/null" (daría error y color).
            ivCover.setImageDrawable(CoverPlaceholder.drawable(seed))
        } else {
            val coverUrl = "$serverBaseUrl/cover/$coverId"
            Glide.with(this)
                .load(coverUrl)
                .placeholder(CoverPlaceholder.drawable(seed))
                .error(
                    Glide.with(this)
                        .load("$serverBaseUrl/cover/${coverId}")
                        .placeholder(CoverPlaceholder.drawable(seed))
                        .error(CoverPlaceholder.drawable(seed))
                        .centerCrop()
                )
                .into(ivCover)
        }

        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = serverBaseUrl,
            downloadHelper = downloadHelper,
            onSongClick = { _, position ->
                val activity = requireActivity() as? MainActivity
                val localPaths = currentSongs.map { downloadHelper.getLocalFilePath(it.id) }
                activity?.playbackService?.setQueueAndPlay(currentSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { song, position -> /* No op for album view maybe? */ },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song ->
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.playNext(song)
                Toast.makeText(requireContext(), "Se reproducirá a continuación", Toast.LENGTH_SHORT).show()
            },
            onPlaylistAddClick = { song ->
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.addToQueue(song)
                Toast.makeText(requireContext(), "Añadida al final de la cola", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager)
            }
        )

        rvSongs.layoutManager = LinearLayoutManager(requireContext())
        rvSongs.adapter = adapter

        btnPlay.setOnClickListener {
            if (currentSongs.isNotEmpty()) {
                val activity = requireActivity() as? MainActivity
                val localPaths = currentSongs.map { downloadHelper.getLocalFilePath(it.id) }
                activity?.playbackService?.setQueueAndPlay(currentSongs, 0, localPaths)
            }
        }

        btnAddPlaylist.setOnClickListener {
            if (currentSongs.isNotEmpty()) {
                AddToPlaylistDialog.showList(
                    requireContext(),
                    viewLifecycleOwner.lifecycleScope,
                    currentSongs,
                    albumName ?: "Álbum",
                    sessionManager
                )
            }
        }

        btnDownload.setOnClickListener {
            val songsToDownload = currentSongs.filter { !downloadHelper.isDownloaded(it.id) }
            if (songsToDownload.isEmpty()) {
                Toast.makeText(requireContext(), "Todas las canciones están descargadas", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(requireContext(), "Descargando álbum...", Toast.LENGTH_SHORT).show()
            activity?.lifecycleScope?.launch {
                for (song in songsToDownload) {
                    val success = downloadHelper.download(song, "$serverBaseUrl/audio/${song.id}")
                    if (success) {
                        adapter.refreshDownloadStates()
                        btnDownload.text = currentSongs.count { !downloadHelper.isDownloaded(it.id) }.toString()
                    }
                }
            }
        }

        loadAlbumSongs()
    }

    private fun loadAlbumSongs() {
        val id = albumId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getAlbumSongs(id, sessionManager.getUserId())
                if (response.isSuccessful && response.body() != null) {
                    currentSongs = response.body()!!.songs
                    adapter.updateSongs(currentSongs)
                    
                    val totalDuration = currentSongs.sumOf { it.duration ?: 0.0 }
                    tvInfo.text = "${currentSongs.size} canciones · ${formatDuration(totalDuration)}"
                    btnDownload.text = currentSongs.count { !downloadHelper.isDownloaded(it.id) }.toString()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al cargar canciones: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatDuration(durationSeconds: Double): String {
        val totalSeconds = durationSeconds.toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
                if (!response.isSuccessful) {
                    sessionManager.addPendingLike(song.id, newLiked)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar para sincronizar al volver al servidor
                sessionManager.addPendingLike(song.id, newLiked)
            }
        }
    }

    private fun toggleDownload(song: Song) {
        val serverBaseUrl = ApiConfig.BASE_URL
        if (downloadHelper.isDownloaded(song.id)) {
            downloadHelper.removeDownload(song.id)
            adapter.refreshDownloadStates()
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val success = downloadHelper.download(song, "$serverBaseUrl/audio/${song.id}")
                if (success) adapter.refreshDownloadStates()
            }
        }
    }

    companion object {
        private const val ARG_ALBUM_ID = "album_id"
        private const val ARG_ALBUM_NAME = "album_name"
        private const val ARG_ARTIST_NAME = "artist_name"
        private const val ARG_COVER_ID = "cover_id"

        fun newInstance(id: String, name: String, artist: String?, coverId: String?) =
            AlbumDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ALBUM_ID, id)
                    putString(ARG_ALBUM_NAME, name)
                    putString(ARG_ARTIST_NAME, artist)
                    putString(ARG_COVER_ID, coverId)
                }
            }
    }
}