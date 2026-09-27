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
        // Diálogo previo con datos reales del servidor (solo lectura):
        // cuántas canciones analizadas hay pendientes de clasificar.
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val statusResp = RetrofitClient.api.getAIQualityStatus()
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                if (statusResp.isSuccessful && statusResp.body() != null) {
                    val status = statusResp.body()!!
                    showAIQualityConfirmDialog(status)
                } else {
                    // Servidor antiguo sin el endpoint: usar el flujo anterior.
                    showLegacyAIDialog()
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                // Sin conexión o servidor antiguo: flujo anterior.
                showLegacyAIDialog()
            }
        }
    }

    private fun showAIQualityConfirmDialog(status: AIQualityStatus) {
        val message = if (status.analyzed == 0) {
            "Todavía no hay canciones con análisis de audio (BPM y tonalidad). " +
                "Ejecuta primero el análisis de audio en el servidor y vuelve a intentarlo."
        } else if (status.pending > 0) {
            "El servidor tiene ${status.analyzed} canciones analizadas " +
                "(BPM + tonalidad). Hay ${status.pending} nuevas por clasificar en " +
                "listas de calidad (género + franja de BPM, ordenadas como un mix de DJ). " +
                "Las ${status.assigned} ya clasificadas NO se duplican: solo se amplían las listas."
        } else {
            "Ya está todo clasificado: ${status.assigned} canciones en " +
                "${status.playlists} listas de calidad. Si el servidor analizó audio nuevo " +
                "desde la última vez, se agregará solo lo nuevo sin duplicar nada. " +
                "¿Ejecutar de todos modos?"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Listas automáticas IA")
            .setMessage(message)
            .setPositiveButton("Generar") { _, _ -> createAIQualityPlaylists() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLegacyAIDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Lista Inteligente")
            .setMessage("¿Quieres que la IA genere una nueva lista basada en tus gustos musicales?")
            .setPositiveButton("Generar") { _, _ -> createAIPlaylist() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Botón IA → listas automáticas de calidad (port de
     * build_quality_playlists.py en el servidor). Idempotente: cada
     * ejecución solo agrega canciones analizadas NUEVAS; nunca crea
     * listas duplicadas ni duplica canciones.
     */
    private fun createAIQualityPlaylists() {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.generateAIQualityPlaylists(
                    AIGenerateRequest(userId = sessionManager.getUserId())
                )
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val result = response.body()!!
                    val summary = buildAIQualitySummary(result)
                    Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show()
                    loadPlaylists()
                } else if (response.code() == 409) {
                    Toast.makeText(
                        requireContext(),
                        "Ya hay una generación en curso, espera a que termine",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "El servidor no pudo generar las listas",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                // Sin conexión: se usa el flujo local anterior como respaldo.
                Toast.makeText(
                    requireContext(),
                    "Sin conexión al servidor: generando lista local",
                    Toast.LENGTH_SHORT
                ).show()
                createAIPlaylist()
            }
        }
    }

    private fun buildAIQualitySummary(result: AIGenerateResponse): String {
        return if (result.newClassified == 0 && result.songsAdded == 0) {
            "Sin novedades: ${result.totalAssigned} canciones ya clasificadas " +
                "en ${result.totalPlaylists} listas. Nada se duplicó."
        } else {
            "Listas IA actualizadas: +${result.newClassified} canciones " +
                "(${result.playlistsCreated} listas nuevas, " +
                "${result.playlistsReused} ampliadas, ${result.totalPlaylists} en total)"
        }
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
                    CreatePlaylistRequest(name, "Lista generada automáticamente por la IA local de localFly", sessionManager.getUserId(), false)
                )

                if (createResp.isSuccessful && createResp.body() != null) {
                    val playlist = createResp.body()!!.playlist
                    
                    // Añadir canciones una a una (para asegurar compatibilidad con el servidor)
                    val songIds = recommendations.map { it.id }
                    var addedCount = 0
                    for (songId in songIds) {
                        val addResp = RetrofitClient.api.addSongToPlayList(playlist.id, PlaylistSongRequest(songId))
                        if (addResp.isSuccessful) addedCount++
                    }
                    
                    progressBar.visibility = View.GONE
                    if (addedCount > 0) {
                        Toast.makeText(requireContext(), "Lista \"$name\" creada con $addedCount canciones", Toast.LENGTH_SHORT).show()
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
                    val serverPlaylists = response.body()!!.playlists
                    val pendingLocal = sessionManager.getPendingPlaylistCreations().map {
                        Playlist(id = it.localId, name = it.name, description = it.description, songIds = it.songIds, isPublic = it.isPublic)
                    }
                    val playlists = serverPlaylists + pendingLocal
                    sessionManager.savePlaylistsCache(playlists)
                    adapter.updatePlaylists(playlists)
                    tvEmpty.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    showFromCache(toastIfEmpty = true)
                }
            } catch (e: Exception) {
                if (isAdded) showFromCache(toastIfEmpty = true)
            }
        }
    }

    /** Público para que MainActivity pueda refrescar esta pantalla justo después de sincronizar. */
    fun reloadAfterSync() {
        if (isAdded) loadPlaylists()
    }

    private fun showFromCache(toastIfEmpty: Boolean) {
        progressBar.visibility = View.GONE
        val cached = sessionManager.getPlaylistsCache()
        val pendingLocal = sessionManager.getPendingPlaylistCreations().map {
            Playlist(id = it.localId, name = it.name, description = it.description, songIds = it.songIds, isPublic = it.isPublic)
        }
        val merged = cached.filter { c -> pendingLocal.none { p -> p.id == c.id } } + pendingLocal
        adapter.updatePlaylists(merged)
        tvEmpty.visibility = if (merged.isEmpty()) View.VISIBLE else View.GONE
        if (merged.isEmpty() && toastIfEmpty) {
            Toast.makeText(requireContext(), "Sin conexión y todavía no hay listas guardadas", Toast.LENGTH_SHORT).show()
        } else if (merged.isNotEmpty()) {
            Toast.makeText(requireContext(), "Sin conexión: mostrando listas guardadas", Toast.LENGTH_SHORT).show()
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
                    CreatePlaylistRequest(name, null, sessionManager.getUserId(), false)
                )
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Lista creada", Toast.LENGTH_SHORT).show()
                    loadPlaylists()
                } else {
                    Toast.makeText(requireContext(), "Error al crear la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val localId = "local_" + java.util.UUID.randomUUID().toString()
                sessionManager.addPendingPlaylistCreation(
                    PendingPlaylistCreation(localId, name, null, mutableListOf(), false)
                )
                Toast.makeText(requireContext(), "Sin conexión: la lista se creará al reconectar", Toast.LENGTH_SHORT).show()
                loadPlaylists()
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
            // Lista creada offline: borrarla de la cola pendiente y de la caché local.
            if (playlist.id.startsWith("local_")) {
                sessionManager.removePendingPlaylistCreation(playlist.id)
                val cache = sessionManager.getPlaylistsCache().toMutableList()
                cache.removeAll { it.id == playlist.id }
                sessionManager.savePlaylistsCache(cache)
                Toast.makeText(requireContext(), "Lista eliminada", Toast.LENGTH_SHORT).show()
                loadPlaylists()
                return@launch
            }
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