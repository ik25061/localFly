package com.example.localfly.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
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
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class PlaylistDetailFragment : Fragment() {

    private var playlistId: String? = null
    private var playlistName: String? = null

    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    private lateinit var tvName: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnPlayAll: MaterialButton
    private lateinit var btnDownloadAll: MaterialButton
    private lateinit var btnDelete: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

    private var currentSongs: List<Song> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            playlistId = it.getString(ARG_ID)
            playlistName = it.getString(ARG_NAME)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())

        rvSongs = view.findViewById(R.id.rvPlaylistDetailSongs)
        tvName = view.findViewById(R.id.tvPlaylistDetailName)
        tvCount = view.findViewById(R.id.tvPlaylistDetailCount)
        btnPlayAll = view.findViewById(R.id.btnPlayAllPlaylist)
        btnDownloadAll = view.findViewById(R.id.btnDownloadAllPlaylist)
        btnDelete = view.findViewById(R.id.btnDeletePlaylist)
        progressBar = view.findViewById(R.id.progressPlaylistDetail)
        tvEmpty = view.findViewById(R.id.tvEmptyPlaylistDetail)

        tvName.text = playlistName ?: "Lista"

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        btnDelete.setOnClickListener { confirmDeletePlaylist() }

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
            onDeleteClick = { song, position -> removeFromPlaylist(song, position) },
            onPlayNextClick = { song ->
                (requireActivity() as? MainActivity)?.playbackService?.playNext(song)
            },
            onPlaylistAddClick = { song ->
                (requireActivity() as? MainActivity)?.playbackService?.addToQueue(song)
                Toast.makeText(requireContext(), "Añadida al final de la cola", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager)
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

        loadPlaylist()
    }

    private fun loadPlaylist() {
        val id = playlistId ?: return
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val plResponse = RetrofitClient.api.getPlayList(id)
                if (!isAdded) return@launch

                if (!plResponse.isSuccessful || plResponse.body() == null) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "No se pudo cargar la lista", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val playlist = plResponse.body()!!.playlist
                playlistName = playlist.name
                tvName.text = playlist.name

                val songIds = playlist.songIds
                if (songIds.isEmpty()) {
                    progressBar.visibility = View.GONE
                    currentSongs = emptyList()
                    adapter.updateSongs(currentSongs)
                    tvCount.text = "0 canciones"
                    tvEmpty.visibility = View.VISIBLE
                    return@launch
                }

                val songsResponse = RetrofitClient.api.getSongsByIds(
                    songIds.joinToString(","),
                    sessionManager.getUserId()
                )
                progressBar.visibility = View.GONE

                if (songsResponse.isSuccessful && songsResponse.body() != null) {
                    currentSongs = songsResponse.body()!!.songs
                    adapter.updateSongs(currentSongs)
                    tvCount.text = if (currentSongs.size == 1) "1 canción" else "${currentSongs.size} canciones"
                    tvEmpty.visibility = if (currentSongs.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(requireContext(), "Error al cargar las canciones", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        currentSongs = currentSongs.map { if (it.id == song.id) it.copy(liked = newLiked) else it }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
                if (!response.isSuccessful) {
                    sessionManager.addPendingLike(song.id, newLiked)
                }
            } catch (e: Exception) {
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

    private fun removeFromPlaylist(song: Song, position: Int) {
        val id = playlistId ?: return
        adapter.removeAt(position)
        currentSongs = currentSongs.filter { it.id != song.id }
        tvCount.text = if (currentSongs.size == 1) "1 canción" else "${currentSongs.size} canciones"
        if (currentSongs.isEmpty()) tvEmpty.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.removeSongFromPlayList(id, PlaylistSongRequest(song.id))
                if (!response.isSuccessful) {
                    Toast.makeText(requireContext(), "No se pudo quitar de la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDeletePlaylist() {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar lista")
            .setMessage("¿Seguro que quieres eliminar \"${playlistName ?: ""}\"? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ -> deletePlaylist() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deletePlaylist() {
        val id = playlistId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.deletePlayList(id)
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Lista eliminada", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    Toast.makeText(requireContext(), "Error al eliminar la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val ARG_ID = "playlist_id"
        private const val ARG_NAME = "playlist_name"

        fun newInstance(id: String, name: String) = PlaylistDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, id)
                putString(ARG_NAME, name)
            }
        }
    }
}