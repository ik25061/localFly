package com.example.localfly.fragments

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
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.network.*
import com.example.localfly.utils.NaturalOrderComparator
import kotlinx.coroutines.launch

@UnstableApi
class PodcastDetailFragment : Fragment() {

    private var podcastId: String? = null
    private var podcastTitle: String? = null

    private lateinit var rvEpisodes: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    private lateinit var tvTitle: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var progressBar: ProgressBar

    private var currentEpisodes: List<Song> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            podcastId = it.getString(ARG_ID)
            podcastTitle = it.getString(ARG_TITLE)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false) // Reusar layout de playlist por ahora
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())

        rvEpisodes = view.findViewById(R.id.rvPlaylistDetailSongs)
        tvTitle = view.findViewById(R.id.tvPlaylistDetailName)
        tvAuthor = view.findViewById(R.id.tvPlaylistDetailCount)
        progressBar = view.findViewById(R.id.progressPlaylistDetail)
        
        view.findViewById<View>(R.id.btnPublicPrivatePlaylist).visibility = View.GONE
        view.findViewById<View>(R.id.btnDeletePlaylist).visibility = View.GONE

        tvTitle.text = podcastTitle ?: "Podcast"

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = RetrofitClient.getBaseUrl(),
            downloadHelper = downloadHelper,
            onSongClick = { _, position ->
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.setQueueAndPlay(currentEpisodes, position)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { _, _ -> },
            onDownloadClick = { song -> toggleDownload(song) }
        )
        rvEpisodes.layoutManager = LinearLayoutManager(requireContext())
        rvEpisodes.adapter = adapter

        loadPodcast()
    }

    private fun loadPodcast() {
        val id = podcastId ?: return
        progressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getPodcast(id, sessionManager.getUserId())
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE

                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    tvTitle.text = body.podcast.title
                    tvAuthor.text = body.podcast.author ?: "Autor desconocido"

                    // Mapear y Ordenar episodios naturalmente
                    val episodes = body.episodes.sortedWith { e1, e2 -> 
                        NaturalOrderComparator.compare(e1.title, e2.title)
                    }.map { ep ->
                        Song(
                            id = ep.id,
                            title = ep.title,
                            artist = body.podcast.author,
                            album = body.podcast.title,
                            year = null,
                            duration = ep.duration,
                            bpm = null,
                            key = null,
                            liked = false,
                            hasCover = true,
                            isEpisode = true,
                            lastPositionMs = ep.lastPositionMs,
                            subtitleUrl = ep.subtitleUrl
                        )
                    }
                    currentEpisodes = episodes
                    adapter.updateSongs(episodes)
                }
            } catch (e: Exception) {
                if (isAdded) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error al cargar podcast", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleLike(song: Song, position: Int) {
        // Op opcional para episodios
    }

    private fun toggleDownload(song: Song) {
        // Implementar descarga de episodios si es necesario
    }

    companion object {
        private const val ARG_ID = "podcast_id"
        private const val ARG_TITLE = "podcast_title"

        fun newInstance(id: String, title: String) = PodcastDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, id)
                putString(ARG_TITLE, title)
            }
        }
    }
}
