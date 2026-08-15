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
import kotlinx.coroutines.launch

class AIFragment : Fragment() {

    private lateinit var rvArtists: RecyclerView
    private lateinit var adapter: ArtistSelectionAdapter
    private lateinit var btnSave: MaterialButton
    private lateinit var tvAIResult: android.widget.TextView
    private lateinit var etSearch: android.widget.EditText
    private lateinit var sessionManager: SessionManager

    private val selectedArtistIds = mutableSetOf<String>()
    private var allArtists = listOf<Artist>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai_assistant, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        rvArtists = view.findViewById(R.id.rvArtistSelection)
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
        // Cuadrícula de artistas (foto circular arriba, nombre debajo) como en la web
        rvArtists.layoutManager = GridLayoutManager(requireContext(), 3)
        rvArtists.adapter = adapter
        btnSave.isEnabled = selectedArtistIds.isNotEmpty()

        btnSave.setOnClickListener {
            saveAndAnalyze()
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterArtists(s.toString())
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

    private fun loadArtists() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getArtists(sessionManager.getUserId(), limit = 1000)
                if (response.isSuccessful && response.body() != null) {
                    allArtists = response.body()!!.items
                    adapter.updateItems(allArtists)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterArtists(query: String) {
        val filtered = if (query.isEmpty()) {
            allArtists
        } else {
            allArtists.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.updateItems(filtered)
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
                val aiManager = AIRecommendationManager(sessionManager)
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