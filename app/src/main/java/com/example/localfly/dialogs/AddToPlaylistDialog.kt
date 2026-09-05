package com.example.localfly.dialogs

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.PlaylistPickAdapter
import com.example.localfly.network.CreatePlaylistRequest
import com.example.localfly.network.PendingPlaylistCreation
import com.example.localfly.network.Playlist
import com.example.localfly.network.PlaylistSongRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bottom sheet "Añadir a lista", equivalente Android de AddToPlayListModal.jsx
 * (mirepo web). Permite añadir [song] a una lista existente o crear una nueva
 * lista al vuelo y añadirla directamente.
 */
object AddToPlaylistDialog {

    fun show(
        context: Context,
        scope: CoroutineScope,
        song: Song,
        sessionManager: SessionManager,
        onAdded: (() -> Unit)? = null
    ) {
        showList(context, scope, listOf(song), song.title, sessionManager, onAdded)
    }

    fun showList(
        context: Context,
        scope: CoroutineScope,
        songs: List<Song>,
        previewTitle: String,
        sessionManager: SessionManager,
        onAdded: (() -> Unit)? = null
    ) {
        if (songs.isEmpty()) return
        
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(R.layout.dialog_add_to_playlist)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvPreviewTitle)
        val tvArtist = dialog.findViewById<TextView>(R.id.tvPreviewArtist)
        val progress = dialog.findViewById<ProgressBar>(R.id.progressPlaylists)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvNoPlaylists)
        val rv = dialog.findViewById<RecyclerView>(R.id.rvPlaylistsPick)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseAddToPlaylist)
        val btnShowForm = dialog.findViewById<MaterialButton>(R.id.btnShowNewPlaylistForm)
        val layoutForm = dialog.findViewById<LinearLayout>(R.id.layoutNewPlaylistForm)
        val etName = dialog.findViewById<EditText>(R.id.etNewPlaylistName)
        val btnCreateAndAdd = dialog.findViewById<MaterialButton>(R.id.btnCreateAndAdd)
        val btnCancelForm = dialog.findViewById<MaterialButton>(R.id.btnCancelNewPlaylist)

        if (songs.size == 1) {
            tvTitle?.text = songs[0].title
            tvArtist?.text = songs[0].artist ?: "Artista desconocido"
        } else {
            tvTitle?.text = previewTitle
            tvArtist?.text = "${songs.size} canciones"
        }

        btnClose?.setOnClickListener { dialog.dismiss() }

        val userId = sessionManager.getUserId()
        // Bandera para evitar que tocar varias veces "Crear y añadir" duplique listas.
        var creatingPlaylist = false

        val adapter = PlaylistPickAdapter(emptyList()) { playlist ->
            scope.launch {
                // Lista creada offline y aún sin sincronizar: encolar como parte de esa creación pendiente.
                if (playlist.id.startsWith("local_")) {
                    val pending = sessionManager.getPendingPlaylistCreations().toMutableList()
                    val idx = pending.indexOfFirst { it.localId == playlist.id }
                    if (idx >= 0) {
                        val current = pending[idx]
                        songs.forEach { song -> if (song.id !in current.songIds) current.songIds.add(song.id) }
                        sessionManager.updatePendingPlaylistCreation(current.localId, current)
                    }
                    Toast.makeText(context, "Añadidas a \"${playlist.name}\" (se subirá al reconectar)", Toast.LENGTH_SHORT).show()
                    onAdded?.invoke()
                    dialog.dismiss()
                    return@launch
                }

                try {
                    var successCount = 0
                    for (song in songs) {
                        val response = RetrofitClient.api.addSongToPlayList(
                            playlist.id,
                            PlaylistSongRequest(song.id)
                        )
                        if (response.isSuccessful) successCount++
                    }
                    
                    if (successCount > 0) {
                        Toast.makeText(context, "Añadidas $successCount canciones a \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                        onAdded?.invoke()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "Error al añadir a la lista", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    // Sin conexión: encolar para sincronizar y actualizar la caché local optimistamente.
                    songs.forEach { song -> sessionManager.addPendingPlaylistSongAdd(playlist.id, song.id) }
                    val cache = sessionManager.getPlaylistsCache().toMutableList()
                    val idx = cache.indexOfFirst { it.id == playlist.id }
                    if (idx >= 0) {
                        val updatedIds = (cache[idx].songIds + songs.map { it.id }).distinct()
                        cache[idx] = cache[idx].copy(songIds = updatedIds)
                        sessionManager.savePlaylistsCache(cache)
                    }
                    Toast.makeText(context, "Sin conexión: se añadirá a \"${playlist.name}\" al reconectar", Toast.LENGTH_SHORT).show()
                    onAdded?.invoke()
                    dialog.dismiss()
                }
            }
        }
        rv?.layoutManager = LinearLayoutManager(context)
        rv?.adapter = adapter

        btnShowForm?.setOnClickListener {
            layoutForm?.visibility = View.VISIBLE
            btnShowForm.visibility = View.GONE
            etName?.requestFocus()
        }
        btnCancelForm?.setOnClickListener {
            layoutForm?.visibility = View.GONE
            btnShowForm?.visibility = View.VISIBLE
            etName?.setText("")
        }
        btnCreateAndAdd?.setOnClickListener {
            val name = etName?.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(context, "Ponle un nombre a la lista", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Evita que tocar varias veces seguidas cree listas duplicadas (mismo nombre y canción).
            if (creatingPlaylist) return@setOnClickListener
            creatingPlaylist = true
            btnCreateAndAdd.isEnabled = false
            scope.launch {
                try {
                    val createResponse = RetrofitClient.api.createPlayList(
                        CreatePlaylistRequest(name, null, userId)
                    )
                    val playlist = createResponse.body()?.playlist
                    if (createResponse.isSuccessful && playlist != null) {
                        var successCount = 0
                        for (song in songs) {
                            val addResponse = RetrofitClient.api.addSongToPlayList(
                                playlist.id,
                                PlaylistSongRequest(song.id)
                            )
                            if (addResponse.isSuccessful) successCount++
                        }
                        
                        if (successCount > 0) {
                            Toast.makeText(context, "Lista creada y $successCount canciones añadidas", Toast.LENGTH_SHORT).show()
                            onAdded?.invoke()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(context, "Lista creada, pero no se pudieron añadir canciones", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Error al crear la lista", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    // Sin conexión: crear la lista solo localmente y encolarla para el próximo sync.
                    val localId = "local_" + java.util.UUID.randomUUID().toString()
                    val creation = PendingPlaylistCreation(
                        localId = localId,
                        name = name,
                        description = null,
                        songIds = songs.map { it.id }.toMutableList()
                    )
                    sessionManager.addPendingPlaylistCreation(creation)
                    val cache = sessionManager.getPlaylistsCache().toMutableList()
                    if (cache.none { it.id == localId }) {
                        cache.add(Playlist(id = localId, name = name, description = null, songIds = creation.songIds))
                    }
                    sessionManager.savePlaylistsCache(cache)
                    Toast.makeText(context, "Sin conexión: \"$name\" se creará al reconectar", Toast.LENGTH_SHORT).show()
                    onAdded?.invoke()
                    dialog.dismiss()
                } finally {
                    creatingPlaylist = false
                    btnCreateAndAdd.isEnabled = true
                }
            }
        }

        // Cargar listas existentes (con caché + pendientes offline como respaldo).
        // 1) Se pintan al instante las listas guardadas localmente para que el
        //    diálogo no se quede en blanco esperando el timeout de red cuando
        //    no hay conexión. 2) Después se intenta refrescar con el servidor.
        scope.launch {
            val cached = sessionManager.getPlaylistsCache()
            val pendingLocal = sessionManager.getPendingPlaylistCreations().map {
                Playlist(id = it.localId, name = it.name, description = it.description, songIds = it.songIds)
            }
            val merged = cached.filter { c -> pendingLocal.none { p -> p.id == c.id } } + pendingLocal
            if (merged.isNotEmpty()) {
                adapter.updatePlaylists(merged)
                tvEmpty?.visibility = View.GONE
            }
            // Indicador de carga: visible mientras se intenta alcanzar el servidor.
            progress?.visibility = View.VISIBLE

            try {
                val response = RetrofitClient.api.getPlayLists(userId)
                progress?.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val serverPlaylists = response.body()!!.playlists
                    val pending = sessionManager.getPendingPlaylistCreations().map {
                        Playlist(id = it.localId, name = it.name, description = it.description, songIds = it.songIds)
                    }
                    val playlists = serverPlaylists + pending
                    sessionManager.savePlaylistsCache(playlists)
                    adapter.updatePlaylists(playlists)
                    tvEmpty?.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    showCachedPlaylists(adapter, tvEmpty, sessionManager)
                }
            } catch (e: Exception) {
                progress?.visibility = View.GONE
                showCachedPlaylists(adapter, tvEmpty, sessionManager)
            }
        }

        dialog.show()
    }

    private fun showCachedPlaylists(
        adapter: PlaylistPickAdapter,
        tvEmpty: TextView?,
        sessionManager: SessionManager
    ) {
        val cached = sessionManager.getPlaylistsCache()
        val pendingLocal = sessionManager.getPendingPlaylistCreations().map {
            Playlist(id = it.localId, name = it.name, description = it.description, songIds = it.songIds)
        }
        val merged = cached.filter { c -> pendingLocal.none { p -> p.id == c.id } } + pendingLocal
        adapter.updatePlaylists(merged)
        if (merged.isEmpty()) {
            tvEmpty?.text = "Sin conexión y todavía no hay listas guardadas"
            tvEmpty?.visibility = View.VISIBLE
        } else {
            tvEmpty?.visibility = View.GONE
        }
    }
}