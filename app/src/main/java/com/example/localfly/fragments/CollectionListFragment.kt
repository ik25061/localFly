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

class CollectionListFragment : Fragment() {

    enum class Type { ALBUM, ARTIST, GENRE, YEAR }

    private var type: Type = Type.ALBUM
    private lateinit var rvCollections: RecyclerView
    private lateinit var adapter: CollectionAdapter
    private lateinit var etSearch: EditText
    private lateinit var tvTitle: TextView
    private lateinit var spinnerSort: Spinner
    private lateinit var sessionManager: SessionManager

    private var originalItems: List<Any> = emptyList()
    private var displayedItems: List<Any> = emptyList()

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
        }

        setupRecyclerView()
        setupSearch()
        setupSort()
        loadData()
    }

    private fun setupRecyclerView() {
        adapter = CollectionAdapter(emptyList()) { item -> openDetail(item) }
        rvCollections.layoutManager = GridLayoutManager(requireContext(), 3)
        rvCollections.adapter = adapter
    }

    private var searchJob: Job? = null

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchServer(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Busca en el servidor, es decir en TODA la base de datos y no solo en los
     * elementos ya cargados. Los endpoints /api/artists, /api/albums, /api/genres
     * y /api/years aceptan ?search=...; con la query vacía se devuelven todos.
     */
    private fun searchServer(query: String) {
        searchJob?.cancel()
        val normalized = query.trim()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300) // debounce mientras se escribe
            try {
                val response = when (type) {
                    Type.ALBUM -> RetrofitClient.api.getAlbums(
                        userId = sessionManager.getUserId(),
                        limit = 1000,
                        search = normalized.ifBlank { null }
                    )
                    Type.ARTIST -> RetrofitClient.api.getArtists(
                        // Sin userId: el servidor devuelve TODOS los artistas de la base de datos
                        userId = null,
                        limit = 1000,
                        search = normalized.ifBlank { null }
                    )
                    Type.GENRE -> RetrofitClient.api.getGenres(
                        userId = sessionManager.getUserId(),
                        limit = 1000,
                        search = normalized.ifBlank { null }
                    )
                    Type.YEAR -> RetrofitClient.api.getYears(
                        userId = sessionManager.getUserId(),
                        limit = 1000,
                        search = normalized.ifBlank { null }
                    )
                }

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    originalItems = when (body) {
                        is AlbumsResponse -> body.items
                        is ArtistsResponse -> body.items
                        is GenresResponse -> body.items
                        is YearsResponse -> body.items
                        else -> emptyList()
                    }
                    displayedItems = originalItems
                    sortItems(spinnerSort.selectedItemPosition)
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error al buscar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
        displayedItems = when (position) {
            0 -> displayedItems.sortedBy { item ->
                when (item) {
                    is Album -> item.name
                    is Artist -> item.name
                    is Genre -> item.name
                    is Year -> item.year.toString()
                    else -> ""
                }
            }
            1 -> displayedItems.sortedByDescending { item -> getSongCount(item) }
            2 -> displayedItems.sortedBy { item -> getSongCount(item) }
            else -> displayedItems
        }
        adapter.updateItems(displayedItems)
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