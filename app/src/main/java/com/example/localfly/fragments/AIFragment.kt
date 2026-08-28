package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.ArtistSelectionAdapter
import com.example.localfly.ai.AIRecommendationManager
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AIFragment : Fragment() {

    private lateinit var rvArtists: RecyclerView
    private lateinit var adapter: ArtistSelectionAdapter
    private lateinit var btnSave: MaterialButton
    private lateinit var tvAIResult: android.widget.TextView
    private lateinit var etSearch: android.widget.EditText
    private lateinit var sessionManager: SessionManager

    private val selectedArtistIds = mutableSetOf<String>()
    private var allArtists = mutableListOf<Artist>()
    
    private var searchJob: kotlinx.coroutines.Job? = null
    
    private var currentOffset = 0
    private val limit = 60
    private var isLoading = false
    private var hasMore = true
    private var currentQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai_assistant, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        rvArtists = view.findViewById(R.id.rvArtistSelection)
        view.findViewById<View>(R.id.btnAiSettings).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, AISettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        btnSave = view.findViewById(R.id.btnSaveAIPreferences)
        tvAIResult = view.findViewById(R.id.tvAIResult)
        etSearch = view.findViewById(R.id.etSearchArtists)
        val cardAIResult = view.findViewById<androidx.cardview.widget.CardView>(R.id.cardAIResult)

        // Cargar favoritos locales primero
        selectedArtistIds.addAll(sessionManager.getFavoriteArtists())

        // Cargar favoritos del servidor para sincronizar
        loadServerFavorites()

        adapter = ArtistSelectionAdapter(emptyList(), selectedArtistIds) {
            btnSave.isEnabled = selectedArtistIds.isNotEmpty()
        }
        val layoutManager = GridLayoutManager(requireContext(), 3)
        rvArtists.layoutManager = layoutManager
        rvArtists.adapter = adapter
        btnSave.isEnabled = selectedArtistIds.isNotEmpty()

        rvArtists.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                if (!isLoading && hasMore && currentQuery.isEmpty() && lastVisible >= total - 12) {
                    loadArtists(isNextPage = true)
                }
            }
        })

        btnSave.setOnClickListener {
            saveAndAnalyze()
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s.toString()
                filterArtists(currentQuery)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        loadArtists()
    }

    private fun loadServerFavorites() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getFavoriteArtists(sessionManager.getUserId())
                if (response.isSuccessful && response.body() != null) {
                    val serverIds = response.body()!!.items.map { it.id }.toSet()
                    if (serverIds != selectedArtistIds) {
                        selectedArtistIds.clear()
                        selectedArtistIds.addAll(serverIds)
                        sessionManager.saveFavoriteArtists(selectedArtistIds)
                        adapter.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                // Silently fail or log
            }
        }
    }

    private fun loadArtists(isNextPage: Boolean = false) {
        if (isLoading) return
        
        if (isNextPage) {
            currentOffset += limit
        } else {
            currentOffset = 0
            hasMore = true
            allArtists.clear()
        }

        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getArtists(
                    sessionManager.getUserId(), 
                    limit = limit,
                    offset = currentOffset
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val newItems = body.items
                    
                    allArtists.addAll(newItems)
                    adapter.updateItems(allArtists)
                    
                    hasMore = body.pagination?.hasMore ?: (newItems.size >= limit)
                }
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    private fun filterArtists(query: String) {
        searchJob?.cancel()
        if (query.isNotEmpty()) {
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300)
                try {
                    val response = RetrofitClient.api.getArtists(sessionManager.getUserId(), search = query, limit = 100)
                    if (response.isSuccessful && response.body() != null) {
                        adapter.updateItems(response.body()!!.items)
                    }
                } catch (e: Exception) { }
            }
        } else {
            adapter.updateItems(allArtists)
        }
    }

    private fun saveAndAnalyze() {
        if (selectedArtistIds.isEmpty()) {
            Toast.makeText(requireContext(), "Selecciona al menos un artista", Toast.LENGTH_SHORT).show()
            return
        }

        sessionManager.saveFavoriteArtists(selectedArtistIds)

        btnSave.isEnabled = false
        val cardAIResult = view?.findViewById<androidx.cardview.widget.CardView>(R.id.cardAIResult)
        cardAIResult?.visibility = View.VISIBLE
        tvAIResult.text = "🤖 IA local analizando tus gustos..."

        // Sincronizar con el servidor en segundo plano
        syncFavoritesWithServer()

        // Ejecutar la IA local (en el dispositivo) con una pequeña demora para UX
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val aiManager = AIRecommendationManager(sessionManager, com.example.localfly.ai.AIWeightsStore(requireContext()))
                val summary = aiManager.generateRecommendationSummary()
                tvAIResult.text = summary
            } catch (e: Exception) {
                tvAIResult.text = "⚠️ La IA local no pudo analizar tus gustos: ${e.message}"
            } finally {
                btnSave.isEnabled = true
            }

            // Navegar a Inicio para ver las recomendaciones
            activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)?.selectedItemId = R.id.nav_home
        }
    }

    private fun syncFavoritesWithServer() {
        viewLifecycleOwner.lifecycleScope.launch {
            val userId = sessionManager.getUserId()
            // Optimizamos: si hay muchos, lanzamos en bloques o secuencial para no saturar
            selectedArtistIds.forEach { artistId ->
                try {
                    RetrofitClient.api.toggleFavoriteArtist(
                        FavoriteArtistRequest(userId, artistId, true)
                    )
                } catch (e: Exception) { }
            }
        }
    }
}