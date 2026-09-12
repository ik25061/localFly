package com.example.localfly.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.dialogs.EditSongMetadataDialog
import com.example.localfly.network.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Editor de metadatos del Administrador: título, álbum, géneros, año y mood. */
class SongEditorFragment : Fragment() {

    private lateinit var etSearch: EditText
    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager
    private lateinit var tvInfo: TextView

    private var rawSongs: List<Song> = emptyList()
    private var currentQuery: String = ""
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_song_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())

        etSearch = view.findViewById(R.id.etSearchSongs)
        rvSongs = view.findViewById(R.id.rvSongEditor)
        tvInfo = view.findViewById(R.id.tvEditorInfo)

        view.findViewById<ImageButton>(R.id.btnBackSongEditor).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = RetrofitClient.getBaseUrl(),
            downloadHelper = downloadHelper,
            onSongClick = { song, _ -> openEditor(song.id) },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { _, _ -> },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song -> (requireActivity() as? MainActivity)?.playbackService?.playNext(song) },
            onPlaylistAddClick = { song -> (requireActivity() as? MainActivity)?.playbackService?.addToQueue(song) },
            onAddToPlaylistClick = { song -> AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager) }
        )
        rvSongs.layoutManager = LinearLayoutManager(requireContext())
        rvSongs.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s.toString()
                loadSongs()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadSongs()
    }
private fun loadSongs() {
        val query = currentQuery.trim()
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (query.isNotEmpty()) delay(400) // Debounce para no saturar el servidor
            try {
                val resp = if (query.isEmpty()) {
                    RetrofitClient.api.getLibrary(sessionManager.getUserId(), limit = 500, offset = 0)
                } else {
                    RetrofitClient.api.searchSongs(query)
                }
                if (!isAdded) return@launch
                val songs = resp.body()?.songs ?: emptyList()
                rawSongs = songs
                adapter.updateSongs(SongAdminStore.applyTo(songs))
                tvInfo.text = "${songs.size} canciones"
            } catch (e: Exception) {
                if (isAdded) {
                    tvInfo.text = "Error al cargar: ${e.message}"
                }
            }
        }
    }

    /** Abre el editor mostrando el valor actual del servidor como referencia. */
    private fun openEditor(songId: String) {
        val raw = rawSongs.firstOrNull { it.id == songId } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            var serverSong = raw
            try {
                val resp = RetrofitClient.api.getSongsByIds(raw.id, sessionManager.getUserId())
                val body = resp.body()
                if (resp.isSuccessful && body != null && body.songs.isNotEmpty()) {
                    serverSong = body.songs.first()
                }
            } catch (e: Exception) {
                // Sin conexión: usamos la copia que ya teníamos.
            }
            if (!isAdded) return@launch
            EditSongMetadataDialog.show(
                requireContext(),
                serverSong,
                SongAdminStore.applyTo(serverSong)
            ) {
                loadSongs()
            }
        }
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
            } catch (e: Exception) {}
        }
    }

    private fun toggleDownload(song: Song) {
        val serverBaseUrl = RetrofitClient.getBaseUrl()
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
}