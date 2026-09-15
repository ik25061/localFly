package com.example.localfly.dialogs

// Cuadro de edición de metadatos del Administrador: nombre mostrado, álbum,
// géneros, año y estado de ánimo. Se guarda en SongAdminStore y se conserva
// aunque la biblioteca se reescanee.

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
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

        val sessionManager = SessionManager(context)
        val existing = SongAdminStore.getEdit(serverSong.id)

        // Referencia del servidor para depuración visual
        val serverParts = mutableListOf<String>()
        serverSong.title?.let { serverParts.add("T: $it") }
        serverSong.artist?.let { serverParts.add("A: $it") }
        serverSong.album?.let { serverParts.add("Al: $it") }
        serverSong.year?.let { serverParts.add("Y: $it") }
        if (serverParts.isNotEmpty()) {
            val tvServer = view.findViewById<TextView>(R.id.tvServerInfo)
            tvServer.text = "Servidor: ${serverParts.joinToString(" · ")}"
            tvServer.visibility = View.VISIBLE
        }

        val etTitle = view.findViewById<EditText>(R.id.etEditTitle)
        val etArtist = view.findViewById<EditText>(R.id.etEditArtist)
        val etAlbum = view.findViewById<EditText>(R.id.etEditAlbum)
        val etYear = view.findViewById<EditText>(R.id.etEditYear)
        val etSearchGenres = view.findViewById<EditText>(R.id.etSearchGenres)
        val chipGroupGenres = view.findViewById<ChipGroup>(R.id.chipGroupEditGenres)
        val chipGroupMoods = view.findViewById<ChipGroup>(R.id.chipGroupEditMoods)

        etTitle.setText(existing?.title ?: displayedSong.title ?: "")
        etArtist.setText(existing?.originalArtist ?: displayedSong.artist ?: "")
        etAlbum.setText(existing?.album ?: displayedSong.album ?: "")
        
        // El año a veces viene null si no se ha configurado, pero el user dice que no aparece
        // aun habiéndolo seleccionado de una lista de 1997.
        val yearToShow = existing?.year ?: displayedSong.year ?: serverSong.year
        etYear.setText(yearToShow?.toString() ?: "")

        val selectedGenreNames = (existing?.genres ?: displayedSong.genre ?: serverSong.genre ?: emptyList()).toMutableSet()
        val selectedMoodNames = (existing?.moods ?: displayedSong.moods?.map { it.name } ?: serverSong.moods?.map { it.name } ?: emptyList()).toMutableSet()
        existing?.mood?.let { selectedMoodNames.add(it) }

        var allServerGenres = emptyList<Genre>()

        fun populateGenres(filter: String = "") {
            chipGroupGenres.removeAllViews()
            
            // Unir géneros del servidor con los seleccionados localmente que podrían no estar en el top del servidor
            val baseList = if (filter.isBlank()) {
                (selectedGenreNames.map { Genre(id = it, name = it, coverId = null, songCount = 0) } + allServerGenres).distinctBy { it.name }
            } else {
                allServerGenres.filter { it.name.contains(filter, ignoreCase = true) }
            }

            baseList.forEach { genre ->
                val chip = Chip(context)
                chip.text = genre.name
                chip.isCheckable = true
                chip.isChecked = genre.name in selectedGenreNames
                updateChipStyle(chip, chip.isChecked)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedGenreNames.add(genre.name) else selectedGenreNames.remove(genre.name)
                    updateChipStyle(chip, isChecked)
                }
                chipGroupGenres.addView(chip)
            }
        }

        // Cargar géneros del servidor
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resp = RetrofitClient.api.getGenres(userId = sessionManager.getUserId(), limit = 1000)
                if (resp.isSuccessful) {
                    allServerGenres = resp.body()?.items ?: emptyList()
                    populateGenres()
                }
            } catch (_: Exception) {}
        }

        etSearchGenres.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { populateGenres(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        fun addMoodChip(name: String) {
            val chip = Chip(context)
            chip.text = name
            chip.isCheckable = true
            chip.isChecked = name in selectedMoodNames
            updateChipStyle(chip, chip.isChecked)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedMoodNames.add(name) else selectedMoodNames.remove(name)
                updateChipStyle(chip, isChecked)
            }
            chipGroupMoods.addView(chip)
        }

        PREDEFINED_MOODS.forEach { addMoodChip(it) }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resp = RetrofitClient.api.getMoods()
                if (resp.isSuccessful) {
                    resp.body()?.moods?.forEach { m -> if (m.name !in PREDEFINED_MOODS) addMoodChip(m.name) }
                }
            } catch (_: Exception) {}
        }

        view.findViewById<ImageButton>(R.id.btnCloseEditDialog).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnCancelEdit).setOnClickListener { dialog.dismiss() }

        val btnRevert = view.findViewById<MaterialButton>(R.id.btnRevertEdit)
        btnRevert.visibility = if (existing == null) View.GONE else View.VISIBLE
        btnRevert.setOnClickListener {
            SongAdminStore.removeEdit(serverSong.id)
            dialog.dismiss()
            onSaved()
        }

        view.findViewById<MaterialButton>(R.id.btnSaveEdit).setOnClickListener {
            val title = etTitle.text.toString().trim().takeIf { it.isNotEmpty() }
            val artist = etArtist.text.toString().trim().takeIf { it.isNotEmpty() }
            val album = etAlbum.text.toString().trim().takeIf { it.isNotEmpty() }
            val year = etYear.text.toString().toIntOrNull()

            SongAdminStore.saveEdit(
                SongEdit(
                    songId = serverSong.id,
                    title = title,
                    artist = artist,
                    album = album,
                    genres = selectedGenreNames.toList(),
                    year = year,
                    mood = selectedMoodNames.firstOrNull(),
                    moods = selectedMoodNames.toList(),
                    originalTitle = serverSong.title,
                    originalArtist = serverSong.artist,
                    originalAlbum = serverSong.album,
                    originalYear = serverSong.year
                )
            )
            dialog.dismiss()
            onSaved()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun updateChipStyle(chip: Chip, isChecked: Boolean) {
        if (isChecked) {
            chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#1DB954"))
            chip.setTextColor(Color.BLACK)
        } else {
            chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#333333"))
            chip.setTextColor(Color.WHITE)
        }
    }
}
