package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.localfly.R
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import com.example.localfly.network.SongAdminStore
import com.example.localfly.network.SongEdit
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Pestaña de revisión de conflictos: canciones editadas por el admin vs. el
 * valor que devuelve el servidor (actualizado en vivo). Permite quedarse con
 * la edición o volver a los datos del servidor.
 */
class ConflictReviewFragment : Fragment() {

    private lateinit var layoutConflicts: LinearLayout
    private lateinit var tvInfo: TextView
    private lateinit var tvNoConflicts: TextView
    private lateinit var sessionManager: SessionManager

    // Conflictos que el admin decidió resolver "manteniendo la edición".
    private val dismissed = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_conflict_review, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        layoutConflicts = view.findViewById(R.id.layoutConflicts)
        tvInfo = view.findViewById(R.id.tvConflictsInfo)
        tvNoConflicts = view.findViewById(R.id.tvNoConflicts)

        view.findViewById<ImageButton>(R.id.btnBackConflicts).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.btnRefreshConflicts).setOnClickListener {
            refresh()
        }

        refresh()
    }

    private fun refresh() {
        layoutConflicts.removeAllViews()
        val visible = SongAdminStore.getEdits().values.toList().filter { it.songId !in dismissed }
        tvInfo.text = if (visible.size == 1) "1 canción editada" else "${visible.size} canciones editadas"
        tvNoConflicts.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        if (visible.isEmpty()) return

        val ids = visible.map { it.songId }
        // Comprobar los datos que devuelve el servidor AHORA MISMO (post-reescaneo).
        viewLifecycleOwner.lifecycleScope.launch {
            var serverSongs: Map<String, Song> = emptyMap()
            try {
                val resp = RetrofitClient.api.getSongsByIds(ids.joinToString(","), sessionManager.getUserId())
                if (resp.isSuccessful && resp.body() != null) {
                    serverSongs = resp.body()!!.songs.associate { it.id to it }
                }
            } catch (e: Exception) {
                // Sin conexión: compararemos contra la copia capturada al editar.
            }
            if (!isAdded) return@launch

            tvInfo.text = if (visible.size == 1) "1 canción editada" else "${visible.size} canciones editadas"
            for (edit in visible) {
                layoutConflicts.addView(buildRow(edit, serverSongs[edit.songId]))
            }
        }
    }

    private fun buildRow(edit: SongEdit, server: Song?): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_edit_conflict, null)

        val serverTitle = server?.title ?: edit.originalTitle
        val serverAlbum = server?.album ?: edit.originalAlbum
        val serverYear = server?.year ?: edit.originalYear

        row.findViewById<TextView>(R.id.tvConflictSongTitle).text =
            if (!edit.title.isNullOrBlank()) edit.title!! else (serverTitle ?: "?")

        val artist = server?.artist ?: edit.originalArtist ?: "Artista desconocido"
        row.findViewById<TextView>(R.id.tvConflictSongArtist).text = artist

        val lines = mutableListOf<String>()
        if (!edit.title.isNullOrBlank()) lines.add("Título   ─ Servidor: ${serverTitle ?: "?"}   ➜   Editado: ${edit.title}")
        if (!edit.album.isNullOrBlank()) lines.add("Álbum    ─ Servidor: ${serverAlbum ?: "?"}   ➜   Editado: ${edit.album}")
        if (edit.year != null) lines.add("Año      ─ Servidor: ${serverYear ?: "?"}   ➜   Editado: ${edit.year}")
        if (edit.genres.isNotEmpty()) lines.add("Géneros  ─ Editados: ${edit.genres.joinToString(", ")}")
        if (!edit.mood.isNullOrBlank()) lines.add("Estado de ánimo ─ ${edit.mood}")
        if (lines.isEmpty()) lines.add("Edición guardada vacía (se eliminará al volver a guardar).")
        row.findViewById<TextView>(R.id.tvConflictDetails).text = lines.joinToString("\n")

        // "Usar servidor": elimina la edición local y vuelve al dato del servidor.
        row.findViewById<MaterialButton>(R.id.btnUseServer).setOnClickListener {
            SongAdminStore.removeEdit(edit.songId)
            dismissed.add(edit.songId)
            refresh()
        }

        // "Mantener edición": se conserva la edición local.
        row.findViewById<MaterialButton>(R.id.btnKeepEdit).setOnClickListener {
            dismissed.add(edit.songId)
            refresh()
        }

        return row
    }
}