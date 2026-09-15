package com.example.localfly.fragments

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.network.DeleteSongRequest
import com.example.localfly.network.DislikedSong
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.SongAdminStore
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Pestaña "Canciones que no me gustan" del administrador. Combina el registro
 * local (SongAdminStore) con lo que el SERVIDOR tiene marcado como oculto
 * (GET /api/hidden-songs): cualquier canción oculta en el servidor que no
 * estuviera ya en el registro local se "adopta" automáticamente la primera
 * vez que se abre esta pantalla, para que ambas fuentes queden sincronizadas.
 */
class DislikedSongsAdminFragment : Fragment() {

    private lateinit var layoutDisliked: LinearLayout
    private lateinit var tvCount: TextView
    private lateinit var tvNoDisliked: TextView
    private lateinit var sessionManager: SessionManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_disliked_admin, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        layoutDisliked = view.findViewById(R.id.layoutDisliked)
        tvCount = view.findViewById(R.id.tvDislikedCount)
        tvNoDisliked = view.findViewById(R.id.tvNoDisliked)

        view.findViewById<ImageButton>(R.id.btnBackDisliked).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        if (parentFragment is SettingsFragment) {
            view.findViewById<View>(R.id.btnBackDisliked).visibility = View.GONE
            view.background = ColorDrawable(Color.TRANSPARENT)
        }

        view.findViewById<MaterialButton>(R.id.btnClearDisliked).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Vaciar lista local")
                .setMessage("Se quitarán todas las canciones de esta lista local. No se borrará ningún archivo del disco.")
                .setPositiveButton("Vaciar") { _, _ ->
                    SongAdminStore.clearDislikedSongs()
                    refresh()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        refresh()
        syncFromServerThenRefresh()
    }

    /** Trae lo que el servidor tiene oculto y adopta en el registro local
     *  cualquier canción que faltara, sin duplicar las que ya están. */
    private fun syncFromServerThenRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getHiddenSongs(sessionManager.getUserId(), limit = 200)
                if (response.isSuccessful) {
                    val serverHidden = response.body()?.songs ?: emptyList()
                    val localIds = SongAdminStore.getDislikedSongs().map { it.songId }.toSet()
                    var addedAny = false
                    serverHidden.forEach { song ->
                        if (song.id !in localIds) {
                            SongAdminStore.recordDislikedSong(song)
                            addedAny = true
                        }
                    }
                    if (addedAny && isAdded) refresh()
                }
            } catch (e: Exception) {
                // Sin conexión: se muestra igualmente lo que ya hay en local.
            }
        }
    }

    private fun refresh() {
        layoutDisliked.removeAllViews()
        val songs = SongAdminStore.getDislikedSongs()
        tvCount.text = if (songs.size == 1) "1 canción" else "${songs.size} canciones"
        tvNoDisliked.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        for (song in songs) {
            if (isAdded) layoutDisliked.addView(buildRow(song))
        }
    }

    private fun buildRow(song: DislikedSong): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_disliked_admin, null)

        row.findViewById<TextView>(R.id.tvDlSongTitle).text = song.title

        val parts = mutableListOf<String>()
        song.artist?.let { parts.add(it) }
        song.album?.let { parts.add(it) }
        song.year?.let { parts.add(it.toString()) }
        row.findViewById<TextView>(R.id.tvDlSongArtist).text =
            if (parts.isEmpty()) "Artista desconocido" else parts.joinToString(" · ")

        row.findViewById<MaterialButton>(R.id.btnRemoveFromList).setOnClickListener {
            SongAdminStore.removeDislikedSong(song.songId)
            refresh()
            Toast.makeText(requireContext(), "Quitada de la lista local", Toast.LENGTH_SHORT).show()
        }

        row.findViewById<MaterialButton>(R.id.btnDeleteFromDisk).setOnClickListener {
            confirmDeleteFromDisk(song)
        }

        row.findViewById<MaterialButton>(R.id.btnPlayDisliked).setOnClickListener {
            playSong(song)
        }

        return row
    }

    @OptIn(UnstableApi::class)
    private fun playSong(disliked: DislikedSong) {
        val activity = requireActivity() as? MainActivity
        if (activity?.playbackService == null) {
            Toast.makeText(requireContext(), "Servicio de música no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Obtener detalles completos de la canción desde el servidor para poder reproducirla
                val response = RetrofitClient.api.getSongsByIds(disliked.songId, sessionManager.getUserId())
                if (response.isSuccessful && response.body() != null) {
                    val songs = response.body()!!.songs
                    if (songs.isNotEmpty()) {
                        val fullSong = songs[0]
                        activity.playbackService?.playSong(fullSong)
                        Toast.makeText(requireContext(), "Escuchando: ${fullSong.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Canción no encontrada en el catálogo", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e("DislikedAdmin", "Error server: ${response.code()} ${response.errorBody()?.string()}")
                    Toast.makeText(requireContext(), "Error del servidor al cargar audio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("DislikedAdmin", "Error playback", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteFromDisk(song: DislikedSong) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar del disco")
            .setMessage("¿Eliminar \"${song.title}\" por completo del disco? Se borrará el archivo del servidor y no se podrá recuperar.")
            .setPositiveButton("Eliminar") { _, _ -> deleteFromDisk(song) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun deleteFromDisk(song: DislikedSong) {
        val activity = requireActivity() as? MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.deleteSong(
                    DeleteSongRequest(id = song.songId, userId = sessionManager.getUserId())
                )
                if (response.isSuccessful) {
                    // Si la canción está sonando ahora mismo, saltar a la siguiente
                    if (activity?.playbackService?.currentSong?.id == song.songId) {
                        activity.playbackService?.next()
                    }
                    
                    val helper = DownloadManagerHelper.getInstance(requireContext())
                    if (helper.isDownloaded(song.songId)) {
                        helper.removeDownload(song.songId)
                    }
                    SongAdminStore.removeDislikedSong(song.songId)
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Canción eliminada del disco y saltada", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                } else if (isAdded) {
                    Toast.makeText(requireContext(), "No se pudo eliminar del disco", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Sin conexión: no se pudo eliminar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
