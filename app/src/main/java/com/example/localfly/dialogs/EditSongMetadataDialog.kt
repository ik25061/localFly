package com.example.localfly.dialogs

// Cuadro de edición de metadatos del Administrador: nombre mostrado, álbum,
// géneros, año y estado de ánimo. Se guarda en SongAdminStore y se conserva
// aunque la biblioteca se reescanee.

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.example.localfly.R
import com.example.localfly.network.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.*

object EditSongMetadataDialog {

    private val PREDEFINED_MOODS = listOf(
        "Feliz", "Triste", "Energética", "Calmada",
        "Romántica", "Melancólica", "Agresiva", "Relajada",
        "Épica", "Oscura", "Brillante", "Misteriosa"
    )

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

        // Referencia del servidor
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

        etTitle.setText(existing?.title ?: serverSong.title ?: "")
        etAlbum.setText(existing?.album ?: serverSong.album ?: "")
        etYear.setText((existing?.year ?: serverSong.year)?.toString() ?: "")
        
        // Cargar géneros del servidor si no hay edición local
        val initialGenres = if (existing != null) existing.genres else serverSong.genre ?: emptyList()
        etGenres.setText(initialGenres.joinToString(", "))

        val chipGroupMoods = view.findViewById<ChipGroup>(R.id.chipGroupEditMoods)
        val selectedMoodNames = (existing?.moods ?: serverSong.moods?.map { it.name } ?: emptyList()).toMutableSet()
        
        // Compatibilidad con campo 'mood' antiguo
        existing?.mood?.let { selectedMoodNames.add(it) }

        fun addMoodChip(name: String) {
            val chip = Chip(context)
            chip.text = name
            chip.isCheckable = true
            chip.isChecked = name in selectedMoodNames
            
            // Estilo
            chip.chipBackgroundColor = ColorStateList.valueOf(if (chip.isChecked) Color.parseColor("#1DB954") else Color.parseColor("#333333"))
            chip.setTextColor(if (chip.isChecked) Color.BLACK else Color.WHITE)
            
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedMoodNames.add(name) else selectedMoodNames.remove(name)
                chip.chipBackgroundColor = ColorStateList.valueOf(if (isChecked) Color.parseColor("#1DB954") else Color.parseColor("#333333"))
                chip.setTextColor(if (isChecked) Color.BLACK else Color.WHITE)
            }
            chipGroupMoods.addView(chip)
        }

        // Poblar con predefinidos primero
        PREDEFINED_MOODS.forEach { addMoodChip(it) }
        
        // Cargar moods adicionales del servidor
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resp = RetrofitClient.api.getMoods()
                if (resp.isSuccessful) {
                    val serverMoods = resp.body()?.moods ?: emptyList()
                    serverMoods.forEach { m ->
                        if (m.name !in PREDEFINED_MOODS) {
                            addMoodChip(m.name)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        view.findViewById<ImageButton>(R.id.btnCloseEditDialog).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnCancelEdit).setOnClickListener { dialog.dismiss() }

        val btnRevert = view.findViewById<MaterialButton>(R.id.btnRevertEdit)
        btnRevert.visibility = if (existing == null) View.GONE else View.VISIBLE
        btnRevert.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Revertir metadatos")
                .setMessage("¿Volver a los datos del servidor? Se eliminará la edición local.")
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

            val year = yearRaw.toIntOrNull()

            SongAdminStore.saveEdit(
                SongEdit(
                    songId = serverSong.id,
                    title = if (titleRaw.isEmpty()) null else titleRaw,
                    album = if (albumRaw.isEmpty()) null else albumRaw,
                    genres = genresRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    year = year,
                    mood = if (selectedMoodNames.isEmpty()) null else selectedMoodNames.first(),
                    moods = selectedMoodNames.toList(),
                    originalTitle = serverSong.title,
                    originalArtist = serverSong.artist,
                    originalAlbum = serverSong.album,
                    originalYear = serverSong.year
                )
            )
            Toast.makeText(context, "Metadatos guardados localmente", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved()
        }

        dialog.setContentView(view)
        dialog.show()
    }
}
