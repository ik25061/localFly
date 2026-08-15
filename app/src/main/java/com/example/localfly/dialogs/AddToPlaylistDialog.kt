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

        val adapter = PlaylistPickAdapter(emptyList()) { playlist ->
            scope.launch {
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
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Cargar listas existentes
        scope.launch {
            try {
                val response = RetrofitClient.api.getPlayLists(userId)
                progress?.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val playlists = response.body()!!.playlists
                    adapter.updatePlaylists(playlists)
                    tvEmpty?.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    tvEmpty?.text = "No se pudieron cargar las listas"
                    tvEmpty?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                progress?.visibility = View.GONE
                tvEmpty?.text = "Error al cargar listas: ${e.message}"
                tvEmpty?.visibility = View.VISIBLE
            }
        }

        dialog.show()
    }
}