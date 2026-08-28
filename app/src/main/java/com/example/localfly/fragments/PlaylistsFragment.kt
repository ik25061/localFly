package com.example.localfly.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.PlaylistAdapter
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment() {

    private lateinit var rvPlaylists: RecyclerView
    private lateinit var adapter: PlaylistAdapter
    private lateinit var sessionManager: SessionManager
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var btnNewPlaylist: MaterialButton
    private lateinit var btnAIPlaylist: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        rvPlaylists = view.findViewById(R.id.rvPlaylists)
        progressBar = view.findViewById(R.id.progressPlaylistsList)
        tvEmpty = view.findViewById(R.id.tvEmptyPlaylists)
        btnNewPlaylist = view.findViewById(R.id.btnNewPlaylist)
        btnAIPlaylist = view.findViewById(R.id.btnAIPlaylist)

        adapter = PlaylistAdapter(
            playlists = emptyList(),
            onClick = { playlist ->
                val fragment = PlaylistDetailFragment.newInstance(playlist.id, playlist.name)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            onDeleteClick = { playlist -> confirmDelete(playlist) }
        )
        rvPlaylists.layoutManager = LinearLayoutManager(requireContext())
        rvPlaylists.adapter = adapter

        btnNewPlaylist.setOnClickListener { showCreateDialog() }
        btnAIPlaylist.setOnClickListener { showAIPlaylistDialog() }

        loadPlaylists()
    }

    override fun onResume() {
        super.onResume()
        // Por si se creó/eliminó una playlist desde el detalle
        loadPlaylists()
    }

    private fun showAIPlaylistDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Lista Inteligente")
            .setMessage("¿Quieres que la IA genere una nueva lista basada en tus gustos musicales?")
            .setPositiveButton("Generar") { _, _ -> createAIPlaylist() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createAIPlaylist() {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val weightsStore = com.example.localfly.ai.AIWeightsStore(requireContext())
                val aiManager = com.example.localfly.ai.AIRecommendationManager(sessionManager, weightsStore)
                val recommendations = aiManager.getRecommendations(limit = weightsStore.getPlaylistSongCount())
                
                if (recommendations.isEmpty()) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "La IA no tiene suficientes datos para generar una lista", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Crear la lista
                val name = "Descubrimiento IA - ${java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault()).format(java.util.Date())}"
                val createResp = RetrofitClient.api.createPlayList(
                    CreatePlaylistRequest(name, "Lista generada automáticamente por la IA local de localFly", sessionManager.getUserId())
                )

                if (createResp.isSuccessful && createResp.body() != null) {
                    val playlist = createResp.body()!!.playlist
                    
                    // Añadir canciones en lote (Bulk)
                    val songIds = recommendations.map { it.id }
                    val addResp = RetrofitClient.api.addSongsToPlayListBulk(playlist.id, PlaylistSongsBulkRequest(songIds))
                    
                    progressBar.visibility = View.GONE
                    if (addResp.isSuccessful) {
                        Toast.makeText(requireContext(), "Lista \"$name\" creada con ${songIds.size} canciones", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Lista creada, pero hubo un error al añadir las canciones", Toast.LENGTH_SHORT).show()
                    }
                    loadPlaylists()
                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error al crear la lista de la IA", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadPlaylists() {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getPlayLists(sessionManager.getUserId())
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val playlists = response.body()!!.playlists
                    adapter.updatePlaylists(playlists)
                    tvEmpty.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(requireContext(), "Error al cargar tus listas", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext())
        input.hint = "Nombre de la lista"

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva lista")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) createPlaylist(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createPlaylist(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.createPlayList(
                    CreatePlaylistRequest(name, null, sessionManager.getUserId())
                )
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Lista creada", Toast.LENGTH_SHORT).show()
                    loadPlaylists()
                } else {
                    Toast.makeText(requireContext(), "Error al crear la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDelete(playlist: Playlist) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar lista")
            .setMessage("¿Seguro que quieres eliminar \"${playlist.name}\"? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ -> deletePlaylist(playlist) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deletePlaylist(playlist: Playlist) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.deletePlayList(playlist.id)
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Lista eliminada", Toast.LENGTH_SHORT).show()
                    loadPlaylists()
                } else {
                    Toast.makeText(requireContext(), "Error al eliminar la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}