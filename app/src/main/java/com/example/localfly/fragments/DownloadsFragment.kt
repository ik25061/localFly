package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.DownloadedSong
import com.example.localfly.DownloadedSongAdapter
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.network.SessionManager

class DownloadsFragment : Fragment() {

    private lateinit var rvDownloads: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvDownloadCountHeader: TextView
    private lateinit var tvStorageInfo: TextView
    private lateinit var swAutoDelete: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var swCrossfade: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnBack: android.widget.ImageButton
    private lateinit var ivInfo: android.widget.ImageView
    private lateinit var btnDeleteList: android.widget.ImageButton
    private lateinit var btnDeleteRed: android.widget.ImageButton
    
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager
    private lateinit var adapter: DownloadedSongAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_downloads, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())
        
        rvDownloads = view.findViewById(R.id.rvDownloads)
        tvEmpty = view.findViewById(R.id.tvEmptyDownloads)
        tvDownloadCountHeader = view.findViewById(R.id.tvDownloadCountHeader)
        tvStorageInfo = view.findViewById(R.id.tvStorageInfo)
        swAutoDelete = view.findViewById(R.id.swAutoDelete)
        swCrossfade = view.findViewById(R.id.swCrossfade)
        btnBack = view.findViewById(R.id.btnBack)
        ivInfo = view.findViewById(R.id.ivInfo)
        btnDeleteList = view.findViewById(R.id.btnDeleteList)
        btnDeleteRed = view.findViewById(R.id.btnDeleteRed)

        adapter = DownloadedSongAdapter(
            items = mutableListOf(),
            serverBaseUrl = com.example.localfly.network.ApiConfig.BASE_URL,
            onItemClick = { downloaded -> playDownloaded(downloaded) },
            onDeleteClick = { downloaded ->
                downloadHelper.removeDownload(downloaded.id)
                loadDownloads()
            },
            onAddToPlaylistClick = { downloaded ->
                val song = com.example.localfly.network.Song(
                    id = downloaded.id, title = downloaded.title, artist = downloaded.artist,
                    album = null, year = null, duration = downloaded.duration, bpm = downloaded.bpm,
                    key = downloaded.key, liked = downloaded.liked, hasCover = downloaded.hasCover,
                    hasLyrics = downloaded.hasLyrics
                )
                com.example.localfly.dialogs.AddToPlaylistDialog.show(
                    requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager
                )
            }
        )
        rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        rvDownloads.adapter = adapter

        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        
        swAutoDelete.isChecked = sessionManager.isAutoDeleteEnabled()
        swAutoDelete.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setAutoDeleteEnabled(isChecked)
        }

        swCrossfade.isChecked = sessionManager.isCrossfadeEnabled()
        swCrossfade.setOnCheckedChangeListener { _, isChecked ->
            val activity = requireActivity() as? MainActivity
            activity?.playbackService?.setCrossfadeEnabled(isChecked)
                ?: sessionManager.setCrossfadeEnabled(isChecked) // por si el servicio aún no está conectado
        }

        ivInfo.setOnClickListener {
            Toast.makeText(requireContext(), "Si el toggle está activo la canción se eliminará después de reproducir", Toast.LENGTH_LONG).show()
        }

        btnDeleteList.setOnClickListener { confirmDeleteAll() }
        btnDeleteRed.setOnClickListener { confirmDeleteAll() }

        loadDownloads()
    }

    /** Pide confirmación antes de borrar todas las descargas. */
    private fun confirmDeleteAll() {
        if (downloadHelper.getDownloadedSongs().isEmpty()) {
            Toast.makeText(requireContext(), "No hay descargas para eliminar", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar todas las descargas")
            .setMessage("¿Seguro que quieres borrar todas las canciones descargadas de tu dispositivo?")
            .setPositiveButton("Eliminar") { _, _ ->
                downloadHelper.removeAllDownloads()
                loadDownloads()
                Toast.makeText(requireContext(), "Descargas eliminadas", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadDownloads() {
        val items = downloadHelper.getDownloadedSongs()
        adapter.updateItems(items)
        
        val totalSongs = items.size
        val totalSizeMb = items.sumOf { it.fileSize } / (1024 * 1024)
        
        tvDownloadCountHeader.text = "$totalSongs canciones descargadas"
        tvStorageInfo.text = "$totalSizeMb MB - $totalSongs canciones"
        
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvDownloads.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun playDownloaded(downloaded: DownloadedSong) {
        (requireActivity() as? MainActivity)?.playDownloadedSong(downloaded)
    }

    override fun onResume() {
        super.onResume()
        // Refresca por si se borró/descargó algo desde otra pantalla
        loadDownloads()
    }
}