package com.example.localfly.dialogs

// Cuadro de edición de metadatos de la canción: título, artista, álbum, año,
// géneros y estado de ánimo.
//
// Funciona igual con o sin conexión:
//  - Precarga los datos que ya conoce el teléfono (edición previa guardada o
//    los metadatos de la canción, que las descargas conservan offline).
//  - Guarda en SongAdminStore (local e inmediato) y lo envía al servidor con
//    MetadataSyncManager; si no hay servidor, queda en cola y se sincroniza al
//    recuperar la conexión.
//  - Los géneros se eligen en un desplegable con autocompletado (escribir
//    "roc" muestra "Rock clásico", "Rock en español"…), se admiten varios y los
//    que ya tiene la canción aparecen como chips que se pueden quitar.

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
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

    /** Sugerencias base de géneros: el desplegable siempre tiene opciones,
     *  incluso sin servidor. */
    private val PREDEFINED_GENRES = listOf(
        "Rock clásico", "Rock en español", "Heavy Rock", "Rock alternativo",
        "Pop", "Pop en español", "Balada", "Indie", "Metal", "Punk",
        "Cumbia", "Salsa", "Bachata", "Merengue", "Vallenato", "Bolero",
        "Mariachi", "Norteño", "Banda", "Corrido", "Reggaetón", "Trap",
        "Electrónica", "House", "Jazz", "Blues", "Soul", "Funk", "Reggae",
        "Clásica", "Instrumental", "Country", "Hip Hop", "Rap", "Tango",
        "Flamenco", "Infantil", "Navideña", "Cristiana", "Regional mexicano"
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
        val etSearchGenres = view.findViewById<AppCompatAutoCompleteTextView>(R.id.etSearchGenres)
        val chipGroupGenres = view.findViewById<ChipGroup>(R.id.chipGroupEditGenres)
        val chipGroupMoods = view.findViewById<ChipGroup>(R.id.chipGroupEditMoods)

        etTitle.setText(existing?.title ?: displayedSong.title ?: serverSong.title ?: "")
        etArtist.setText(existing?.originalArtist ?: displayedSong.artist ?: serverSong.artist ?: "")
        etAlbum.setText(existing?.album ?: displayedSong.album ?: serverSong.album ?: "")

        val yearToShow = existing?.year ?: displayedSong.year ?: serverSong.year
        etYear.setText(yearToShow?.toString() ?: "")

        // Géneros de la canción (se conservan hasta que el usuario los quite)
        val selectedGenreNames = LinkedHashSet<String>()
        (existing?.genres
            ?: displayedSong.genre
            ?: serverSong.genre
            ?: emptyList()).forEach { g ->
            g.trim().takeIf { it.isNotEmpty() }?.let { selectedGenreNames.add(it) }
        }
        val selectedMoodNames = (existing?.moods ?: displayedSong.moods?.map { it.name } ?: serverSong.moods?.map { it.name } ?: emptyList()).toMutableSet()
        existing?.mood?.let { selectedMoodNames.add(it) }

        var allServerGenres = emptyList<Genre>()

        // ===== GÉNEROS: chips de los que ya tiene + desplegable con autocompletado =====

        /** Sugerencias del desplegable: servidor + locales + base, sin repetir. */
        fun updateGenreSuggestions(filter: String = "") {
            val localGenres = SongAdminStore.getGenres().map { it.name }
            val base = (allServerGenres.map { it.name } + localGenres + PREDEFINED_GENRES + selectedGenreNames)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
            val filtered = if (filter.isBlank()) base
            else base.filter { it.contains(filter.trim(), ignoreCase = true) }
            etSearchGenres.setAdapter(
                ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, filtered)
            )
            if (filter.isNotBlank() && filtered.isNotEmpty() && etSearchGenres.isFocused) {
                etSearchGenres.showDropDown()
            }
        }

        fun addGenreChip(name: String) {
            val chip = Chip(context)
            chip.text = name
            chip.isCheckable = false
            chip.isCloseIconVisible = true
            chip.setCloseIconTint(ColorStateList.valueOf(Color.WHITE))
            chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#1DB954"))
            chip.setTextColor(Color.BLACK)
            chip.setOnCloseIconClickListener {
                selectedGenreNames.remove(name)
                chipGroupGenres.removeView(chip)
                updateGenreSuggestions(etSearchGenres.text.toString())
            }
            chipGroupGenres.addView(chip)
        }

        fun refreshGenreChips() {
            chipGroupGenres.removeAllViews()
            selectedGenreNames.forEach { addGenreChip(it) }
        }

        /** Añade el género escrito/elegido (o lo deja fuera si ya estaba). */
        fun commitGenre(raw: String) {
            val name = raw.trim()
            if (name.isEmpty()) return
            if (selectedGenreNames.none { it.equals(name, ignoreCase = true) }) {
                selectedGenreNames.add(name)
                refreshGenreChips()
            }
            etSearchGenres.setText("")
            updateGenreSuggestions()
        }

        refreshGenreChips()
        updateGenreSuggestions()

        etSearchGenres.setOnItemClickListener { _, _, position, _ ->
            val picked = etSearchGenres.adapter?.getItem(position) as? String
            if (picked != null) commitGenre(picked)
        }
        etSearchGenres.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                commitGenre(etSearchGenres.text.toString())
                true
            } else false
        }
        etSearchGenres.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateGenreSuggestions(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Géneros del servidor (si responde; sin conexión quedan los locales y
        // las sugerencias base, así el desplegable sigue funcionando).
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resp = RetrofitClient.api.getGenres(userId = sessionManager.getUserId(), limit = 1000)
                if (resp.isSuccessful) allServerGenres = resp.body()?.items ?: emptyList()
            } catch (_: Exception) { }
            updateGenreSuggestions(etSearchGenres.text.toString())
        }

        // ===== ESTADO DE ÁNIMO =====
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
            // Si el usuario dejó un género escrito sin confirmar, se incluye.
            val pending = etSearchGenres.text.toString().trim()
            if (pending.isNotEmpty() && selectedGenreNames.none { it.equals(pending, ignoreCase = true) }) {
                selectedGenreNames.add(pending)
            }

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

            // Sincronización con el servidor. Sin conexión la edición queda
            // guardada en el teléfono y se enviará automáticamente al reconectar.
            CoroutineScope(Dispatchers.Main).launch {
                val synced = if (ServerReachability.isOnline) {
                    try { MetadataSyncManager.syncPendingEdits(sessionManager) } catch (e: Exception) { false }
                } else false
                val msg = if (synced) "Metadatos guardados y actualizados en el servidor"
                else "Cambios guardados en el teléfono; se actualizarán en el servidor al recuperar la conexión"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
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
