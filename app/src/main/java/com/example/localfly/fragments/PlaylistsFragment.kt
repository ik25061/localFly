package com.example.localfly.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.PlaylistAdapter
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment() {

    private lateinit var rvPlaylists: RecyclerView
    private lateinit var adapter: PlaylistAdapter
    private lateinit var sessionManager: SessionManager
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var btnNewPlaylist: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        rvPlaylists = view.findViewById(R.id.rvPlaylists)
        progressBar = view.findViewById(R.id.progressPlaylistsList)
        tvEmpty = view.findViewById(R.id.tvEmptyPlaylists)
        btnNewPlaylist = view.findViewById(R.id.btnNewPlaylist)

        adapter = PlaylistAdapter(
            playlists = emptyList(),
            onClick = { playlist ->
                val fragment = PlaylistDetailFragment.newInstance(playlist.id, playlist.name)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            onDeleteClick = { playlist -> confirmDelete(playlist) }
        )
        rvPlaylists.layoutManager = LinearLayoutManager(requireContext())
        rvPlaylists.adapter = adapter

        btnNewPlaylist.setOnClickListener { showCreateDialog() }

        loadPlaylists()
    }

    override fun onResume() {
        super.onResume()
        // Por si se creó/eliminó una playlist desde el detalle
        loadPlaylists()
    }

    private fun loadPlaylists() {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getPlayLists(sessionManager.getUserId())
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                if (response.isSuccessful && response.body() != null) {
                    val playlists = response.body()!!.playlists
                    adapter.updatePlaylists(playlists)
                    tvEmpty.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(requireContext(), "Error al cargar tus listas", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext())
        input.hint = "Nombre de la lista"

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva lista")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) createPlaylist(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createPlaylist(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.createPlayList(
                    CreatePlaylistRequest(name, null, sessionManager.getUserId())
                )
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Lista creada", Toast.LENGTH_SHORT).show()
                    loadPlaylists()
                } else {
                    Toast.makeText(requireContext(), "Error al crear la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDelete(playlist: Playlist) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar lista")
            .setMessage("¿Seguro que quieres eliminar \"${playlist.name}\"? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ -> deletePlaylist(playlist) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deletePlaylist(playlist: Playlist) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.deletePlayList(playlist.id)
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Lista eliminada", Toast.LENGTH_SHORT).show()
                    loadPlaylists()
                } else {
                    Toast.makeText(requireContext(), "Error al eliminar la lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}