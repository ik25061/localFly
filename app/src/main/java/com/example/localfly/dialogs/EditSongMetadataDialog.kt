package com.example.localfly.dialogs

// Cuadro de edición de metadatos del Administrador: nombre mostrado, álbum,
// géneros, año y estado de ánimo. Se guarda en SongAdminStore y se conserva
// aunque la biblioteca se reescanee.

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.example.localfly.R
import com.example.localfly.network.Song
import com.example.localfly.network.SongAdminStore
import com.example.localfly.network.SongEdit
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

object EditSongMetadataDialog {

    private val MOODS = listOf(
        "Feliz", "Triste", "Energética", "Calmada",
        "Romántica", "Melancólica", "Agresiva", "Relajada"
    )

    /**
     * @param serverSong    el valor que devuelve actualmente el servidor.
     * @param displayedSong la canción tal y como se muestra (ediciones aplicadas).
     * @param onSaved       se invoca tras guardar o revertir (para refrescar).
     */
    fun show(
        context: Context,
        serverSong: Song,
        displayedSong: Song,
        onSaved: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_edit_song_metadata, null)

        val existing = SongAdminStore.getEdit(serverSong.id)

        view.findViewById<TextView>(R.id.tvEditSongTitle).text = displayedSong.title
        view.findViewById<TextView>(R.id.tvEditSongArtist).text = displayedSong.artist ?: "Artista desconocido"

        // Referencia de lo que devuelve el servidor ahora mismo.
        val serverParts = mutableListOf<String>()
        serverSong.title?.let { serverParts.add("Título: $it") }
        serverSong.album?.let { serverParts.add("Álbum: $it") }
        serverSong.year?.let { serverParts.add("Año: $it") }
        if (serverParts.isNotEmpty()) {
            val tvServer = view.findViewById<TextView>(R.id.tvServerInfo)
            tvServer.text = "Servidor: ${serverParts.joinToString(" · ")}"
            tvServer.visibility = View.VISIBLE
        }

        val etTitle = view.findViewById<EditText>(R.id.etEditTitle)
        val etAlbum = view.findViewById<EditText>(R.id.etEditAlbum)
        val etYear = view.findViewById<EditText>(R.id.etEditYear)
        val etGenres = view.findViewById<EditText>(R.id.etEditGenres)

        etTitle.setText(existing?.title ?: displayedSong.title ?: "")
        etAlbum.setText(existing?.album ?: (displayedSong.album ?: ""))
        etYear.setText((existing?.year ?: displayedSong.year)?.toString() ?: "")
        etGenres.setText((existing?.genres ?: emptyList()).joinToString(", "))

        // Estado de ánimo (chips de una sola selección)
        val moodButtons = listOf(
            view.findViewById<MaterialButton>(R.id.btnMood1),
            view.findViewById<MaterialButton>(R.id.btnMood2),
            view.findViewById<MaterialButton>(R.id.btnMood3),
            view.findViewById<MaterialButton>(R.id.btnMood4),
            view.findViewById<MaterialButton>(R.id.btnMood5),
            view.findViewById<MaterialButton>(R.id.btnMood6),
            view.findViewById<MaterialButton>(R.id.btnMood7),
            view.findViewById<MaterialButton>(R.id.btnMood8)
        )
        var selectedMood: String? = existing?.mood

        fun refreshMoods() {
            for (i in 0 until moodButtons.size) {
                val label = MOODS[i]
                moodButtons[i].text = if (label == selectedMood) "✓ $label" else label
            }
        }
        for (i in 0 until moodButtons.size) {
            moodButtons[i].setOnClickListener {
                val label = MOODS[i]
                selectedMood = if (selectedMood == label) null else label
                refreshMoods()
            }
        }
        refreshMoods()

        view.findViewById<ImageButton>(R.id.btnCloseEditDialog).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnCancelEdit).setOnClickListener { dialog.dismiss() }
val btnRevert = view.findViewById<MaterialButton>(R.id.btnRevertEdit)
        btnRevert.visibility = if (existing == null) View.GONE else View.VISIBLE
        btnRevert.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Revertir metadatos")
                .setMessage("¿Volver a los datos del servidor? Se eliminará la edición local (título, álbum, géneros, año y estado de ánimo).")
                .setPositiveButton("Revertir") { _, _ ->
                    SongAdminStore.removeEdit(serverSong.id)
                    dialog.dismiss()
                    onSaved()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        view.findViewById<MaterialButton>(R.id.btnSaveEdit).setOnClickListener {
            val titleRaw = etTitle.text.toString().trim()
            val albumRaw = etAlbum.text.toString().trim()
            val yearRaw = etYear.text.toString().trim()
            val genresRaw = etGenres.text.toString()

            var year: Int? = null
            if (yearRaw.isNotEmpty()) {
                try {
                    year = yearRaw.toInt()
                } catch (e: Exception) {
                    year = null
                }
            }

            SongAdminStore.saveEdit(
                SongEdit(
                    songId = serverSong.id,
                    title = if (titleRaw.isEmpty()) null else titleRaw,
                    album = if (albumRaw.isEmpty()) null else albumRaw,
                    genres = splitGenres(genresRaw),
                    year = year,
                    mood = selectedMood,
                    originalTitle = serverSong.title,
                    originalArtist = serverSong.artist,
                    originalAlbum = serverSong.album,
                    originalYear = serverSong.year
                )
            )
            Toast.makeText(context, "Metadatos guardados", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun splitGenres(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}