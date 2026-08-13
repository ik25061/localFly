package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class LikedSongsFragment : Fragment() {

    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager
    private lateinit var tvCount: TextView
    private lateinit var btnPlayAll: MaterialButton
    private lateinit var btnDownloadAll: MaterialButton

    private var currentSongs: List<Song> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_liked_songs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())

        rvSongs = view.findViewById(R.id.rvLikedSongsFull)
        tvCount = view.findViewById(R.id.tvLikedCount)
        btnPlayAll = view.findViewById(R.id.btnPlayAll)
        btnDownloadAll = view.findViewById(R.id.btnDownloadAllLiked)

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val serverBaseUrl = ApiConfig.BASE_URL
        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = serverBaseUrl,
            downloadHelper = downloadHelper,
            onSongClick = { _, position ->
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.setQueueAndPlay(currentSongs, position)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { _, _ -> },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song ->
                (requireActivity() as? MainActivity)?.playbackService?.playNext(song)
            },
            onPlaylistAddClick = { song ->
                val dialog = PlaylistSelectionDialogFragment.newInstance(song.id)
                dialog.show(parentFragmentManager, "playlist_selection")
            }
        )

        rvSongs.layoutManager = LinearLayoutManager(requireContext())
        rvSongs.adapter = adapter

        btnPlayAll.setOnClickListener {
            if (currentSongs.isNotEmpty()) {
                (requireActivity() as? MainActivity)?.playbackService?.setQueueAndPlay(currentSongs, 0)
            }
        }

        btnDownloadAll.setOnClickListener {
            val pending = currentSongs.filter { !downloadHelper.isDownloaded(it.id) }
            if (pending.isEmpty()) {
                Toast.makeText(requireContext(), "Ya están todas descargadas", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(requireContext(), "Descargando ${pending.size} canciones...", Toast.LENGTH_SHORT).show()
            activity?.lifecycleScope?.launch {
                for (song in pending) {
                    downloadHelper.download(song, "$serverBaseUrl/audio/${song.id}")
                    if (isAdded) adapter.refreshDownloadStates()
                }
            }
        }

        loadLikedSongs()
    }

    private fun loadLikedSongs() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userId = sessionManager.getUserId()
                val response = RetrofitClient.api.getLikedSongs(userId, limit = 500)
                if (response.isSuccessful && response.body() != null) {
                    currentSongs = response.body()!!.songs
                    adapter.updateSongs(currentSongs)
                    tvCount.text = "${currentSongs.size} canciones"
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        // If unliked, maybe remove from list?
        if (!newLiked) {
            adapter.removeAt(position)
            currentSongs = currentSongs.filter { it.id != song.id }
            tvCount.text = "${currentSongs.size} canciones"
        }
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
                downloadHelper.download(song, "$serverBaseUrl/audio/${song.id}")
                if (isAdded) adapter.refreshDownloadStates()
            }
        }
    }
}