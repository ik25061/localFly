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
import kotlinx.coroutines.launch
import java.text.Normalizer

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

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userId = sessionManager.getUserId()
                val response = when (type) {
                    Type.ALBUM -> RetrofitClient.api.getAlbums(userId, limit = 1000)
                    Type.ARTIST -> RetrofitClient.api.getArtists(userId, limit = 1000)
                    Type.GENRE -> RetrofitClient.api.getGenres(userId, limit = 1000)
                    Type.YEAR -> RetrofitClient.api.getYears(userId, limit = 1000)
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
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filter(query: String) {
        val normalizedQuery = query.removeAccents().lowercase()
        displayedItems = if (normalizedQuery.isEmpty()) {
            originalItems
        } else {
            originalItems.filter { item ->
                val name = when (item) {
                    is Album -> item.name
                    is Artist -> item.name
                    is Genre -> item.name
                    is Year -> item.year.toString()
                    else -> ""
                }
                name.removeAccents().lowercase().contains(normalizedQuery)
            }
        }
        sortItems(spinnerSort.selectedItemPosition)
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

    private fun String.removeAccents(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
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