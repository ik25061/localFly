package com.example.localfly.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.CollectionAdapter
import com.example.localfly.network.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.text.Normalizer

class CollectionListFragment : Fragment() {

    enum class Type { ALBUM, ARTIST, GENRE, YEAR, PLAYLIST }

    private var type: Type = Type.ALBUM
    private lateinit var rvCollections: RecyclerView
    private lateinit var adapter: CollectionAdapter
    private lateinit var etSearch: EditText
    private lateinit var tvTitle: TextView
    private lateinit var spinnerSort: Spinner
    private lateinit var sessionManager: SessionManager

    private var originalItems: List<Any> = emptyList()
    private var displayedItems: List<Any> = emptyList()
    
    private var currentOffset = 0
    private val limit = 100
    private var isLoading = false
    private var hasMore = true
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            type = it.getSerializable(ARG_TYPE) as? Type ?: Type.ALBUM
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_collection_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        rvCollections = view.findViewById(R.id.rvCollections)
        etSearch = view.findViewById(R.id.etSearchCollection)
        tvTitle = view.findViewById(R.id.tvCollectionTitle)
        spinnerSort = view.findViewById(R.id.spinnerSort)

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        tvTitle.text = when (type) {
            Type.ALBUM -> {
                etSearch.hint = "Buscar álbumes..."
                "Álbumes"
            }
            Type.ARTIST -> {
                etSearch.hint = "Buscar artistas..."
                "Artistas"
            }
            Type.GENRE -> {
                etSearch.hint = "Buscar géneros..."
                "Géneros"
            }
            Type.YEAR -> {
                etSearch.hint = "Buscar años..."
                "Años"
            }
            Type.PLAYLIST -> {
                etSearch.hint = "Buscar listas..."
                "Mis Listas"
            }
        }

        setupRecyclerView()
        setupSearch()
        setupSort()
        loadData()
    }

    private fun setupRecyclerView() {
        adapter = CollectionAdapter(emptyList()) { item -> openDetail(item) }
        val layoutManager = GridLayoutManager(requireContext(), 3)
        rvCollections.layoutManager = layoutManager
        rvCollections.adapter = adapter

        rvCollections.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                if (!isLoading && hasMore && lastVisible >= total - 6) {
                    searchServer(currentQuery, isNextPage = true)
                }
            }
        })
    }

    private var searchJob: Job? = null

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s.toString()
                searchServer(currentQuery, isNextPage = false)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun searchServer(query: String, isNextPage: Boolean = false) {
        if (isLoading) return
        
        searchJob?.cancel()
        val normalized = query.trim()
        
        if (isNextPage) {
            currentOffset += limit
        } else {
            currentOffset = 0
            hasMore = true
            displayedItems = emptyList()
            originalItems = emptyList()
        }

        val minSongs = if (normalized.isEmpty()) 2 else 0

        isLoading = true
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!isNextPage) delay(300) // debounce
            try {
                val response = when (type) {
                    Type.ALBUM -> RetrofitClient.api.getAlbums(
                        userId = sessionManager.getUserId(),
                        limit = limit,
                        offset = currentOffset,
                        search = normalized.ifBlank { null },
                        minSongs = minSongs
                    )
                    Type.ARTIST -> RetrofitClient.api.getArtists(
                        userId = null,
                        limit = limit,
                        offset = currentOffset,
                        search = normalized.ifBlank { null },
                        minSongs = minSongs
                    )
                    Type.GENRE -> RetrofitClient.api.getGenres(
                        userId = sessionManager.getUserId(),
                        limit = limit,
                        offset = currentOffset,
                        search = normalized.ifBlank { null },
                        minSongs = minSongs
                    )
                    Type.YEAR -> RetrofitClient.api.getYears(
                        userId = sessionManager.getUserId(),
                        limit = limit,
                        offset = currentOffset,
                        search = normalized.ifBlank { null }
                    )
                    Type.PLAYLIST -> {
                        if (isNextPage) {
                            isLoading = false
                            hasMore = false
                            return@launch
                        }
                        RetrofitClient.api.getPlayLists(userId = sessionManager.getUserId())
                    }
                }

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val newItems = when (body) {
                        is AlbumsResponse -> {
                            hasMore = body.pagination?.hasMore ?: (body.items.size >= limit)
                            body.items
                        }
                        is ArtistsResponse -> {
                            hasMore = body.pagination?.hasMore ?: (body.items.size >= limit)
                            body.items
                        }
                        is GenresResponse -> {
                            hasMore = body.pagination?.hasMore ?: (body.items.size >= limit)
                            // Aplanar géneros concatenados
                            body.items.flatMap { genre ->
                                if (genre.name.contains(";")) {
                                    genre.name.split(";").map { part ->
                                        genre.copy(name = part.trim())
                                    }
                                } else {
                                    listOf(genre)
                                }
                            }.distinctBy { it.name.lowercase() }
                        }
                        is YearsResponse -> {
                            hasMore = body.pagination?.hasMore ?: (body.items.size >= limit)
                            body.items
                        }
                        is PlaylistsResponse -> {
                            hasMore = false
                            body.playlists
                        }
                        else -> emptyList()
                    }

                    if (isNextPage) {
                        originalItems = originalItems + newItems
                    } else {
                        originalItems = newItems
                    }
                    
                    displayedItems = originalItems
                    sortItems(spinnerSort.selectedItemPosition)
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun setupSort() {
        val options = arrayOf("A - Z", "Más canciones", "Menos canciones")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSort.adapter = spinnerAdapter

        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sortItems(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadData() {
        searchServer("")
    }

    private fun sortItems(position: Int) {
        val query = currentQuery.removeAccents().lowercase()
        
        // Filtrado por búsqueda local (opcional, el servidor ya lo hace, pero ayuda con los acentos)
        var filteredList = if (query.isEmpty()) {
            originalItems
        } else {
            originalItems.filter { item ->
                val name = when (item) {
                    is Album -> item.name
                    is Artist -> item.name
                    is Genre -> item.name
                    is Year -> item.year.toString()
                    is Playlist -> item.name
                    else -> ""
                }
                name.removeAccents().lowercase().contains(query)
            }
        }

        displayedItems = when (position) {
            0 -> filteredList.sortedBy { item ->
                when (item) {
                    is Album -> item.name
                    is Artist -> item.name
                    is Genre -> item.name
                    is Year -> item.year.toString()
                    is Playlist -> item.name
                    else -> ""
                }
            }
            1 -> filteredList.sortedByDescending { item -> getSongCount(item) }
            2 -> filteredList.sortedBy { item -> getSongCount(item) }
            else -> filteredList
        }
        adapter.updateItems(displayedItems)
    }

    private fun String.removeAccents(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    private fun getSongCount(item: Any): Int = when (item) {
        is Album -> item.songCount
        is Artist -> item.songCount
        is Genre -> item.songCount
        is Year -> item.songCount
        else -> 0
    }

    private fun openDetail(item: Any) {
        val fragment = when (item) {
            is com.example.localfly.network.Playlist -> {
                CollectionDetailFragment.newInstance(item.id, item.name, "PLAYLIST", item.coverId)
            }
            is Album -> AlbumDetailFragment.newInstance(item.id, item.name, item.artist, item.coverId)
            is Artist -> CollectionDetailFragment.newInstance(item.id, item.name, "ARTIST", item.coverId)
            is Genre -> CollectionDetailFragment.newInstance(item.id, item.name, "GENRE", item.coverId)
            is Year -> CollectionDetailFragment.newInstance(item.year.toString(), item.year.toString(), "YEAR", item.coverId)
            else -> return
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val ARG_TYPE = "type"

        fun newInstance(type: Type) = CollectionListFragment().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_TYPE, type)
            }
        }
    }
}