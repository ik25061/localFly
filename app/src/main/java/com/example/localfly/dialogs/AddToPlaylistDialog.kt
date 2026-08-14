package com.example.localfly.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.PlaylistPickAdapter
import com.example.localfly.network.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

object AddToPlaylistDialog {

    fun show(context: Context, scope: LifecycleCoroutineScope, song: Song, sessionManager: SessionManager) {
        val dialog = BottomSheetDialog(context, com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_to_playlist, null)
        dialog.setContentView(view)

        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseAddToPlaylist)
        val tvTitle = view.findViewById<TextView>(R.id.tvPreviewTitle)
        val tvArtist = view.findViewById<TextView>(R.id.tvPreviewArtist)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressPlaylists)
        val tvNoPlaylists = view.findViewById<TextView>(R.id.tvNoPlaylists)
        val rvPlaylists = view.findViewById<RecyclerView>(R.id.rvPlaylistsPick)
        
        val layoutForm = view.findViewById<View>(R.id.layoutNewPlaylistForm)
        val etNewName = view.findViewById<EditText>(R.id.etNewPlaylistName)
        val btnCreateAndAdd = view.findViewById<MaterialButton>(R.id.btnCreateAndAdd)
        val btnCancelForm = view.findViewById<MaterialButton>(R.id.btnCancelNewPlaylist)
        val btnShowForm = view.findViewById<MaterialButton>(R.id.btnShowNewPlaylistForm)

        tvTitle.text = song.title
        tvArtist.text = song.artist ?: "Artista desconocido"

        btnClose.setOnClickListener { dialog.dismiss() }

        var playlistAdapter: PlaylistPickAdapter? = null
        playlistAdapter = PlaylistPickAdapter(emptyList()) { playlist ->
            addSongToPlaylist(context, scope, song.id, playlist.id, dialog, playlistAdapter!!)
        }
        rvPlaylists.layoutManager = LinearLayoutManager(context)
        rvPlaylists.adapter = playlistAdapter

        btnShowForm.setOnClickListener {
            layoutForm.visibility = View.VISIBLE
            btnShowForm.visibility = View.GONE
        }

        btnCancelForm.setOnClickListener {
            layoutForm.visibility = View.GONE
            btnShowForm.visibility = View.VISIBLE
        }

        btnCreateAndAdd.setOnClickListener {
            val name = etNewName.text.toString().trim()
            if (name.isNotEmpty()) {
                createAndAdd(context, scope, name, song.id, sessionManager, dialog)
            }
        }

        // Load playlists
        scope.launch {
            try {
                val response = RetrofitClient.api.getPlayLists(sessionManager.getUserId())
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val playlists = response.body()!!.playlists
                    playlistAdapter?.updatePlaylists(playlists)
                    tvNoPlaylists.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(context, "Error al cargar listas", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun addSongToPlaylist(context: Context, scope: LifecycleCoroutineScope, songId: String, playlistId: String, dialog: BottomSheetDialog, adapter: PlaylistPickAdapter) {
        adapter.setAdding(playlistId)
        scope.launch {
            try {
                val response = RetrofitClient.api.addSongToPlayList(playlistId, PlaylistSongRequest(songId))
                if (response.isSuccessful) {
                    Toast.makeText(context, "Añadida a la lista", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    adapter.setAdding(null)
                    Toast.makeText(context, "Error al añadir", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                adapter.setAdding(null)
                Toast.makeText(context, "Error de red", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createAndAdd(context: Context, scope: LifecycleCoroutineScope, name: String, songId: String, sessionManager: SessionManager, dialog: BottomSheetDialog) {
        scope.launch {
            try {
                val response = RetrofitClient.api.createPlayList(CreatePlaylistRequest(name, null, sessionManager.getUserId()))
                if (response.isSuccessful && response.body() != null) {
                    val newPlaylist = response.body()!!
                    val addResponse = RetrofitClient.api.addSongToPlayList(newPlaylist.id, PlaylistSongRequest(songId))
                    if (addResponse.isSuccessful) {
                        Toast.makeText(context, "Lista creada y canción añadida", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "Lista creada, pero error al añadir canción", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                } else {
                    Toast.makeText(context, "No se pudo crear la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error de red", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
