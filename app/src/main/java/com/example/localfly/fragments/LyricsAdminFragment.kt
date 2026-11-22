package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.localfly.R
import com.example.localfly.network.LyricsAdminJobRequest
import com.example.localfly.network.LyricsAdminReviewRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Herramienta de administración para generar, reprocesar y revisar letras. */
class LyricsAdminFragment : Fragment() {

    private lateinit var session: SessionManager
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnCancel: MaterialButton
    private var polling = false

    private val authorization: String
        get() = "Bearer ${session.getToken().orEmpty()}"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_admin_lyrics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        tvStatus = view.findViewById(R.id.tvLyricsAdminStatus)
        tvProgress = view.findViewById(R.id.tvLyricsAdminProgress)
        etSearch = view.findViewById(R.id.etLyricsAdminSearch)
        btnCancel = view.findViewById(R.id.btnLyricsCancel)

        view.findViewById<ImageButton>(R.id.btnBackLyricsAdmin).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.btnLyricsRefreshStatus).setOnClickListener { loadStatus() }
        view.findViewById<MaterialButton>(R.id.btnLyricsAll).setOnClickListener { startJob("all") }
        view.findViewById<MaterialButton>(R.id.btnLyricsNew).setOnClickListener { startJob("new") }
        view.findViewById<MaterialButton>(R.id.btnLyricsPending).setOnClickListener { startJob("pending") }
        view.findViewById<MaterialButton>(R.id.btnLyricsReview).setOnClickListener { startJob("review") }
        view.findViewById<MaterialButton>(R.id.btnLyricsSearch).setOnClickListener { searchSong() }
        btnCancel.setOnClickListener { cancelJob() }
        loadStatus()
        loadCurrentJob()
    }

    private fun loadStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getAdminLyricsStatus(authorization, limit = 1)
                if (!response.isSuccessful) {
                    tvStatus.text = "No se pudo consultar el estado (HTTP ${response.code()}). Comprueba que tu cuenta tenga permisos de administrador."
                    return@launch
                }
                val body = response.body() ?: return@launch
                val c = body.counts
                tvStatus.text = "Biblioteca: ${c["total"] ?: 0} canciones\n" +
                    "Nuevas: ${c["new"] ?: 0} · Pendientes: ${c["pending"] ?: 0}\n" +
                    "Generadas: ${c["generated"] ?: 0} · Revisadas: ${c["reviewed"] ?: 0}\n" +
                    "Revisión necesaria: ${c["needsReview"] ?: 0} · Instrumentales: ${c["instrumental"] ?: 0} · Errores: ${c["error"] ?: 0}"
            } catch (e: Exception) {
                tvStatus.text = "Error de conexión: ${e.message}"
            }
        }
    }

    private fun startJob(mode: String, songId: String? = null, force: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.startAdminLyricsJob(
                    authorization,
                    LyricsAdminJobRequest(mode = mode, songId = songId, force = force)
                )
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    Toast.makeText(requireContext(), "No se pudo iniciar: HTTP ${response.code()}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (!body.accepted) {
                    Toast.makeText(requireContext(), body.message ?: "No hay canciones pendientes", Toast.LENGTH_SHORT).show()
                    loadStatus()
                    return@launch
                }
                tvProgress.text = "Trabajo iniciado: ${body.total} canciones"
                monitorJob()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadCurrentJob() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getAdminLyricsJob(authorization)
                val job = response.body()?.job
                if (response.isSuccessful && job?.status == "running") monitorJob()
            } catch (_: Exception) { }
        }
    }

    private fun monitorJob() {
        if (polling) return
        polling = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                while (isAdded) {
                    val response = RetrofitClient.api.getAdminLyricsJob(authorization)
                    val job = response.body()?.job ?: break
                    val current = job.currentSong?.get("title")?.takeIf { !it.isNullOrBlank() } ?: "Preparando..."
                    tvProgress.text = "Procesadas: ${job.processed}/${job.total}\n" +
                        "Correctas: ${job.succeeded} · Revisar: ${job.needsReview}\n" +
                        "Sin letra: ${job.notFound} · Errores: ${job.failed}\n" +
                        "Actual: $current"
                    if (job.status != "running") {
                        tvProgress.append("\nEstado: ${job.status}")
                        loadStatus()
                        break
                    }
                    delay(1500)
                }
            } catch (e: Exception) {
                if (isAdded) tvProgress.text = "No se pudo consultar el progreso: ${e.message}"
            } finally {
                polling = false
            }
        }
    }

    private fun cancelJob() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.cancelAdminLyricsJob(authorization)
                Toast.makeText(requireContext(), response.body()?.message ?: "Solicitud enviada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun searchSong() {
        val query = etSearch.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            etSearch.error = "Escribe el nombre de una canción o artista"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getAdminLyricsStatus(authorization, query = query, limit = 50)
                val items = response.body()?.items.orEmpty()
                if (!response.isSuccessful || items.isEmpty()) {
                    Toast.makeText(requireContext(), "No se encontraron canciones", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val labels = items.map { "${it.title ?: "Sin título"} — ${it.artist ?: "Artista desconocido"} [${it.status}]" }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("Selecciona una canción")
                    .setItems(labels) { _, index ->
                        val song = items[index]
                        AlertDialog.Builder(requireContext())
                            .setTitle(song.title ?: "Canción")
                            .setMessage("Estado: ${song.status}\nFuente: ${song.source ?: "No disponible"}\n¿Quieres volver a generar su letra?")
                            .setPositiveButton("Regenerar") { _, _ -> startJob("selected", songId = song.songId, force = true) }
                            .setNeutralButton(if (song.reviewed) "Quitar revisión" else "Marcar revisada") { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        RetrofitClient.api.markAdminLyricsReviewed(authorization, song.songId, LyricsAdminReviewRequest(!song.reviewed))
                                        loadStatus()
                                    } catch (e: Exception) {
                                        Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cerrar", null)
                            .show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error buscando: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
