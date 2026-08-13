package com.example.localfly.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.localfly.network.*
import kotlinx.coroutines.launch

class PlaylistSelectionDialogFragment : DialogFragment() {

    private var songId: String? = null
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        songId = arguments?.getString(ARG_SONG_ID)
        sessionManager = SessionManager(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog)
        builder.setTitle("Añadir a lista")
        
        val listView = ListView(requireContext())
        builder.setView(listView)

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getPlaylists(sessionManager.getUserId())
                if (response.isSuccessful && response.body() != null) {
                    val playlists = response.body()!!.playlists
                    val names = playlists.map { it.name }.toMutableList()
                    names.add(0, "+ Nueva lista")
                    
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
                    listView.adapter = adapter
                    
                    listView.setOnItemClickListener { _, _, position, _ ->
                        if (position == 0) {
                            showCreatePlaylistDialog()
                        } else {
                            val playlist = playlists[position - 1]
                            addSongToPlaylist(playlist.id)
                        }
                        dismiss()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al cargar listas", Toast.LENGTH_SHORT).show()
            }
        }

        return builder.create()
    }

    private fun showCreatePlaylistDialog() {
        val input = android.widget.EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("Nueva lista")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) createPlaylist(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createPlaylist(name: String) {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.createPlaylist(
                    CreatePlaylistRequest(name, null, sessionManager.getUserId())
                )
                if (response.isSuccessful && response.body() != null) {
                    addSongToPlaylist(response.body()!!.id)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al crear lista", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongToPlaylist(playlistId: String) {
        val sId = songId ?: return
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.addSongToPlaylist(
                    playlistId,
                    AddSongToPlaylistRequest(sId)
                )
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Añadida correctamente", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Error al añadir", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error de red", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ARG_SONG_ID = "song_id"

        fun newInstance(songId: String) = PlaylistSelectionDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SONG_ID, songId)
            }
        }
    }
}