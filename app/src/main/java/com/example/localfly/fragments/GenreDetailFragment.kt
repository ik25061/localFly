package com.example.localfly.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.adapters.GenrePickAdapter
import com.example.localfly.ai.GenreAIEngine
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.dialogs.NewGenreDialog
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** Detalle de un género del admin: canciones, buscador para añadir y IA local. */
class GenreDetailFragment : Fragment() {

    private var genreId: String? = null

    private lateinit var sessionManager: SessionManager
    private lateinit var downloadHelper: DownloadManagerHelper

    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvNoGenreSongs: TextView
    private lateinit var rvGenreSongs: RecyclerView
    private lateinit var songsAdapter: SongAdapter
    private lateinit var etSearch: EditText
    private lateinit var rvGenrePick: RecyclerView
    private lateinit var pickAdapter: GenrePickAdapter
    private lateinit var tvNoPick: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNoSuggestions: TextView
    private lateinit var layoutSuggestions: LinearLayout

    // Copia de la biblioteca para el buscador de "Añadir canciones".
    private var librarySongs: List<Song> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            genreId = it.getString(ARG_GENRE_ID)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_genre_detail, container, false)
    }
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())

        tvTitle = view.findViewById(R.id.tvGenreDetailTitle)
        tvMeta = view.findViewById(R.id.tvGenreDetailMeta)
        tvNoGenreSongs = view.findViewById(R.id.tvNoGenreSongs)
        rvGenreSongs = view.findViewById(R.id.rvGenreSongs)
        etSearch = view.findViewById(R.id.etGenreSearch)
        rvGenrePick = view.findViewById(R.id.rvGenrePick)
        tvNoPick = view.findViewById(R.id.tvNoPickResults)
        tvStatus = view.findViewById(R.id.tvAiStatus)
        tvNoSuggestions = view.findViewById(R.id.tvNoAiSuggestions)
        layoutSuggestions = view.findViewById(R.id.layoutAiSuggestions)

        view.findViewById<ImageButton>(R.id.btnBackGenreDetail).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<MaterialButton>(R.id.btnRenameGenre).setOnClickListener {
            val genre = SongAdminStore.getGenre(genreId ?: "")
            if (genre != null) {
                NewGenreDialog.show(requireContext(), genre) { refreshMeta() }
            }
        }

        view.findViewById<MaterialButton>(R.id.btnDeleteGenreDetail).setOnClickListener {
            confirmDeleteGenre()
        }

        // Si el género ya no existe (borrado en otra pantalla), volvemos atrás.
        if (SongAdminStore.getGenre(genreId ?: "") == null) {
            parentFragmentManager.popBackStack()
            return
        }
        tvTitle.text = SongAdminStore.getGenre(genreId!!)!!.name

        songsAdapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = ApiConfig.BASE_URL,
            downloadHelper = downloadHelper,
            onSongClick = { song, position ->
                val allSongs = songsAdapter.currentSongs()
                val localPaths = allSongs.map { downloadHelper.getLocalFilePath(it.id) }
                (requireActivity() as? MainActivity)?.playbackService?.setQueueAndPlay(allSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { _, _ -> },
            onDownloadClick = { song -> toggleDownload(song) },
            onDeleteClick = { song, _ -> toggleGenre(song) },
            onPlayNextClick = { song -> (requireActivity() as? MainActivity)?.playbackService?.playNext(song) },
            onPlaylistAddClick = { song -> (requireActivity() as? MainActivity)?.playbackService?.addToQueue(song) },
            onAddToPlaylistClick = { song -> AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager) }
        )
        rvGenreSongs.layoutManager = LinearLayoutManager(requireContext())
        rvGenreSongs.adapter = songsAdapter

        pickAdapter = GenrePickAdapter(
            songs = mutableListOf(),
            isSelected = { song -> inGenre(song.id) },
            onToggle = { song -> toggleGenre(song) }
        )
        rvGenrePick.layoutManager = LinearLayoutManager(requireContext())
        rvGenrePick.adapter = pickAdapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<MaterialButton>(R.id.btnAiAnalyze).setOnClickListener {
            runAiAnalysis()
        }

        refreshMeta()
        loadGenreSongs()
        loadPicker()
    }

    private fun inGenre(songId: String): Boolean {
        val genre = SongAdminStore.getGenre(genreId ?: "") ?: return false
        return songId in genre.songIds
    }
private fun refreshMeta() {
        val genre = SongAdminStore.getGenre(genreId ?: "") ?: return
        tvTitle.text = genre.name
        tvMeta.text = if (genre.songIds.size == 1) "1 canción" else "${genre.songIds.size} canciones"
    }

    private fun loadGenreSongs() {
        val genre = SongAdminStore.getGenre(genreId ?: "") ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            var songs = emptyList<Song>()
            if (genre.songIds.isNotEmpty()) {
                try {
                    val resp = RetrofitClient.api.getSongsByIds(genre.songIds.joinToString(","), sessionManager.getUserId())
                    if (resp.isSuccessful && resp.body() != null) songs = resp.body()!!.songs
                } catch (e: Exception) {}
            }
            if (!isAdded) return@launch
            songsAdapter.updateSongs(SongAdminStore.applyTo(songs))
            tvNoGenreSongs.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            refreshMeta()
        }
    }

    private fun loadPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getLibrary(sessionManager.getUserId(), limit = 500, offset = 0)
                if (resp.isSuccessful && resp.body() != null) {
                    librarySongs = resp.body()!!.songs
                }
            } catch (e: Exception) {}
            if (!isAdded) return@launch
            applyFilter()
        }
    }

    private fun applyFilter() {
        val query = etSearch.text.toString().trim().lowercase()
        val filtered = if (query.isEmpty()) {
            librarySongs
        } else {
            librarySongs.filter { song ->
                val haystack = (song.title ?: "") + " " + (song.artist ?: "") + " " + (song.album ?: "")
                haystack.lowercase().contains(query)
            }
        }
        pickAdapter.updateSongs(filtered)
        tvNoPick.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleGenre(song: Song) {
        val id = genreId ?: return
        if (inGenre(song.id)) {
            SongAdminStore.removeSongFromGenre(id, song.id)
        } else {
            SongAdminStore.addSongsToGenre(id, listOf(song.id))
        }
        applyFilter()
        loadGenreSongs()
        refreshMeta()
    }

    private fun runAiAnalysis() {
        val genre = SongAdminStore.getGenre(genreId ?: "") ?: return
        tvStatus.text = "Analizando biblioteca con IA local…"
        tvStatus.visibility = View.VISIBLE
        tvNoSuggestions.visibility = View.GONE
        layoutSuggestions.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = try {
                GenreAIEngine.suggest(sessionManager, genre, 15)
            } catch (e: Exception) {
                emptyList()
            }
            if (!isAdded) return@launch
            tvStatus.visibility = View.GONE
            if (suggestions.isEmpty()) {
                tvNoSuggestions.visibility = View.VISIBLE
            } else {
                for (s in suggestions) {
                    layoutSuggestions.addView(buildSuggestion(s.song, s.reasons))
                }
            }
        }
    }

    private fun buildSuggestion(song: Song, reasons: List<String>): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_genre_suggestion, null)
        val displayed = SongAdminStore.applyTo(song)
        row.findViewById<TextView>(R.id.tvSuggTitle).text = displayed.title
        row.findViewById<TextView>(R.id.tvSuggReasons).text =
            if (reasons.isEmpty()) "Sugerida por la IA local" else reasons.joinToString(" · ")
        val btn = row.findViewById<MaterialButton>(R.id.btnSuggAdd)
        btn.text = if (inGenre(song.id)) "Quitar" else "Añadir"
        btn.setOnClickListener {
            toggleGenre(song)
            btn.text = if (inGenre(song.id)) "Quitar" else "Añadir"
        }
        return row
    }

    private fun confirmDeleteGenre() {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar género")
            .setMessage("¿Eliminar este género? Las canciones se conservarán en la biblioteca.")
            .setPositiveButton("Eliminar") { _, _ -> deleteGenre() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteGenre() {
        SongAdminStore.deleteGenre(genreId ?: "")
        parentFragmentManager.popBackStack()
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        songsAdapter.updateSongAt(position, song.copy(liked = newLiked))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
            } catch (e: Exception) {}
        }
    }

    private fun toggleDownload(song: Song) {
        val serverBaseUrl = ApiConfig.BASE_URL
        if (downloadHelper.isDownloaded(song.id)) {
            downloadHelper.removeDownload(song.id)
            songsAdapter.refreshDownloadStates()
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val success = downloadHelper.download(song, "$serverBaseUrl/audio/${song.id}")
                if (success) songsAdapter.refreshDownloadStates()
            }
        }
    }

    companion object {
        private const val ARG_GENRE_ID = "genre_id"

        fun newInstance(genreId: String) = GenreDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_GENRE_ID, genreId)
            }
        }
    }
}