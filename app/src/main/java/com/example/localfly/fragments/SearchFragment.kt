package com.example.localfly.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.network.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
class SearchFragment : Fragment() {

    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())

        etSearch = view.findViewById(R.id.etSearch)
        progressBar = view.findViewById(R.id.progressSearch)
        rvResults = view.findViewById(R.id.rvSearchResults)

        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = ApiConfig.BASE_URL,
            downloadHelper = downloadHelper,
            onSongClick = { song, position ->
                val allSongs = adapter.currentSongs()
                val localPaths = allSongs.map { downloadHelper.getLocalFilePath(it.id) }
                (requireActivity() as? MainActivity)?.playbackService?.setQueueAndPlay(allSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { song, position -> /* No op here */ },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song -> (requireActivity() as? MainActivity)?.playbackService?.playNext(song) },
            onPlaylistAddClick = { song -> (requireActivity() as? MainActivity)?.playbackService?.addToQueue(song) },
            onAddToPlaylistClick = { song -> AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager) }
        )

        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.trim().isEmpty()) {
            adapter.updateSongs(emptyList())
            progressBar.visibility = View.GONE
            return
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(400) // Debounce para no saturar el servidor
            progressBar.visibility = View.VISIBLE
            try {
                val response = RetrofitClient.api.searchSongs(query)
                if (response.isSuccessful && response.body() != null) {
                    adapter.updateSongs(response.body()!!.songs)
                }
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
            } catch (e: Exception) { }
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
}
