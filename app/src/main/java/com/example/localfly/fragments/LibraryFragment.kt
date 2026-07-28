package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.PlaybackService
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.network.*
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    // Base URL del servidor (debe coincidir con RetrofitClient)
    private val serverBaseUrl = "http://127.0.1.1:5002"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        downloadHelper = DownloadManagerHelper(requireContext())
        sessionManager = SessionManager(requireContext())

        rvSongs = view.findViewById(R.id.rvLibrarySongs)

        // Inicializar adaptador
        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = serverBaseUrl,
            downloadHelper = downloadHelper,
            onSongClick = { song, position ->
                val allSongs = adapter.currentSongs()
                val localPaths = allSongs.map { downloadHelper.getLocalFilePath(it.id) }
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.setQueueAndPlay(allSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { song, position -> hideSong(song, position) },
            onDownloadClick = { song -> downloadSong(song) }
        )

        rvSongs.layoutManager = LinearLayoutManager(requireContext())
        rvSongs.adapter = adapter

        // Cargar biblioteca
        loadLibrary()
    }

    private fun loadLibrary() {
        val userId = sessionManager.getUserId()
        if (userId == null) {
            Toast.makeText(requireContext(), "Usuario no autenticado", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch

                val response = RetrofitClient.api.getLibrary(
                    userId = userId,
                    limit = 100,
                    offset = 0
                )

                if (response.isSuccessful && response.body() != null) {
                    val songs = response.body()!!.songs
                    adapter.updateSongs(songs)
                } else {
                    Toast.makeText(requireContext(), "Error al cargar biblioteca", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.likeSong(
                    song.id,
                    LikeRequest(sessionManager.getUserId(), newLiked)
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error al actualizar like", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hideSong(song: Song, position: Int) {
        adapter.removeAt(position)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.hideSong(
                    song.id,
                    HideRequest(sessionManager.getUserId())
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error al ocultar canción", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadSong(song: Song) {
        Toast.makeText(requireContext(), "Descargando \"${song.title}\"...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val audioUrl = "$serverBaseUrl/audio/${song.id}"
            val success = downloadHelper.download(song, audioUrl)
            if (success) {
                Toast.makeText(requireContext(), "Descarga completa", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Error al descargar", Toast.LENGTH_SHORT).show()
            }
            adapter.refreshDownloadStates()
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshDownloadStates()
    }
}