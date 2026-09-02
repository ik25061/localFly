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
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
class LikedSongsFragment : Fragment() {

    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager
    private lateinit var tvCount: TextView
    private lateinit var btnPlayAll: MaterialButton
    private lateinit var btnDownloadAll: MaterialButton
    private lateinit var btnAddPlaylist: ImageButton

    private var currentSongs: MutableList<Song> = mutableListOf()
    private var currentOffset = 0
    private val pageSize = 100
    private var isLoadingMore = false
    private var hasMoreLiked = true

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
        btnAddPlaylist = view.findViewById(R.id.btnLikedToPlaylist)

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
                val localPaths = currentSongs.map { downloadHelper.getLocalFilePath(it.id) }
                activity?.playbackService?.setQueueAndPlay(currentSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { _, _ -> },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song ->
                (requireActivity() as? MainActivity)?.playbackService?.playNext(song)
            },
            onPlaylistAddClick = { song ->
                (requireActivity() as? MainActivity)?.playbackService?.addToQueue(song)
            },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager)
            }
        )

        rvSongs.layoutManager = LinearLayoutManager(requireContext())
        rvSongs.adapter = adapter

        rvSongs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = rvSongs.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val totalItems = layoutManager.itemCount
                if (!isLoadingMore && hasMoreLiked && lastVisible >= totalItems - 5) {
                    loadLikedSongs(isNextPage = true)
                }
            }
        })

        btnPlayAll.setOnClickListener {
            if (currentSongs.isNotEmpty()) {
                val localPaths = currentSongs.map { downloadHelper.getLocalFilePath(it.id) }
                (requireActivity() as? MainActivity)?.playbackService?.setQueueAndPlay(currentSongs, 0, localPaths)
            }
        }

        btnAddPlaylist.setOnClickListener {
            if (currentSongs.isNotEmpty()) {
                AddToPlaylistDialog.showList(
                    requireContext(),
                    viewLifecycleOwner.lifecycleScope,
                    currentSongs,
                    "Canciones que te gustan",
                    sessionManager
                )
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

    private fun loadLikedSongs(isNextPage: Boolean = false) {
        if (isLoadingMore) return
        isLoadingMore = true

        if (!isNextPage) {
            currentOffset = 0
            hasMoreLiked = true
            currentSongs = mutableListOf()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userId = sessionManager.getUserId()
                val response = RetrofitClient.api.getLikedSongs(
                    userId,
                    limit = pageSize,
                    offset = currentOffset
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (isNextPage) {
                        currentSongs.addAll(body.songs)
                    } else {
                        currentSongs = body.songs.toMutableList()
                    }
                    adapter.updateSongs(currentSongs)

                    val total = body.pagination?.total ?: currentSongs.size
                    tvCount.text = "${currentSongs.size} de $total canciones"

                    hasMoreLiked = body.pagination?.hasMore ?: (body.songs.size >= pageSize)
                    currentOffset += pageSize
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        // If unliked, maybe remove from list?
        if (!newLiked) {
            adapter.removeAt(position)
            currentSongs.removeAt(position)
            tvCount.text = "${currentSongs.size} canciones"
            // Compensar el desplazamiento en el servidor: esta canción ya no
            // aparecerá en el ORDER BY del backend, así que la siguiente
            // página debe pedirse una posición antes para no saltarse ni
            // repetir la que le sigue.
            currentOffset = (currentOffset - 1).coerceAtLeast(0)
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