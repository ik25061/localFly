package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.ArtistSelectionAdapter
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AIFragment : Fragment() {

    private lateinit var rvArtists: RecyclerView
    private lateinit var adapter: ArtistSelectionAdapter
    private lateinit var btnSave: MaterialButton
    private lateinit var sessionManager: SessionManager

    private val selectedArtistIds = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai_assistant, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        rvArtists = view.findViewById(R.id.rvArtistSelection)
        btnSave = view.findViewById(R.id.btnSaveAIPreferences)

        // Load existing favorites
        selectedArtistIds.addAll(sessionManager.getFavoriteArtists())

        adapter = ArtistSelectionAdapter(emptyList(), selectedArtistIds) {
            // Callback when selection changes (optional)
        }
        rvArtists.layoutManager = LinearLayoutManager(requireContext())
        rvArtists.adapter = adapter

        btnSave.setOnClickListener {
            saveAndAnalyze()
        }

        loadArtists()
    }

    private fun loadArtists() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getArtists(sessionManager.getUserId(), limit = 1000)
                if (response.isSuccessful && response.body() != null) {
                    adapter.updateItems(response.body()!!.items)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAndAnalyze() {
        if (selectedArtistIds.isEmpty()) {
            Toast.makeText(requireContext(), "Selecciona al menos un artista", Toast.LENGTH_SHORT).show()
            return
        }

        sessionManager.saveFavoriteArtists(selectedArtistIds)
        
        Toast.makeText(requireContext(), "IA analizando tus gustos...", Toast.LENGTH_LONG).show()
        
        // Simular demora de análisis de IA
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            Toast.makeText(requireContext(), "¡Análisis completo! Mira tus recomendaciones en el Inicio.", Toast.LENGTH_SHORT).show()
            
            // Navegar a Home para ver resultados
            activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)?.selectedItemId = R.id.nav_home
        }
    }
}