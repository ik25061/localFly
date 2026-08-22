package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.network.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Locale

class CollectionDetailFragment : Fragment() {

    private var itemId: String? = null
    private var itemName: String? = null
    private var itemType: String? = null
    private var coverId: String? = null

    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    private lateinit var ivCover: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvType: TextView
    private lateinit var tvInfo: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnAddPlaylist: ImageButton
    private lateinit var btnHideArtist: ImageButton

    private var currentSongs: List<Song> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            itemId = it.getString(ARG_ID)
            itemName = it.getString(ARG_NAME)
            itemType = it.getString(ARG_TYPE)
            coverId = it.getString(ARG_COVER_ID)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_collection_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())
        sessionManager = SessionManager(requireContext())

        ivCover = view.findViewById(R.id.ivCollectionCover)
        tvName = view.findViewById(R.id.tvCollectionName)
        tvType = view.findViewById(R.id.tvCollectionType)
        tvInfo = view.findViewById(R.id.tvCollectionInfo)
        btnPlay = view.findViewById(R.id.btnPlayCollection)
        btnDownload = view.findViewById(R.id.btnDownloadCollection)
        btnAddPlaylist = view.findViewById(R.id.btnAddCollectionToPlaylist)
        btnHideArtist = view.findViewById(R.id.btnHideArtist)
        rvSongs = view.findViewById(R.id.rvCollectionSongs)

        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        tvName.text = itemName
        tvType.text = when (itemType) {
            "ARTIST" -> {
                btnHideArtist.visibility = View.VISIBLE
                "ARTISTA"
            }
            "GENRE" -> "GÉNERO"
            "YEAR" -> "AÑO"
            else -> "COLECCIÓN"
        }

        btnHideArtist.setOnClickListener {
            hideCurrentArtist()
        }

        btnAddPlaylist.setOnClickListener {
            if (currentSongs.isNotEmpty()) {
                AddToPlaylistDialog.showList(
                    requireContext(),
                    viewLifecycleOwner.lifecycleScope,
                    currentSongs,
                    itemName ?: "Colección",
                    sessionManager
                )
            }
        }
        
        val serverBaseUrl = ApiConfig.BASE_URL
        val coverUrl = when (itemType) {
            "ARTIST" -> {
                // El servidor sirve la foto del artista en /artist-cover/{nombre}
                val encoded = java.net.URLEncoder.encode(itemName ?: "", "UTF-8").replace("+", "%20")
                "$serverBaseUrl/artist-cover/$encoded"
            }
            else -> "$serverBaseUrl/cover/$coverId"
        }

        Glide.with(this)
            .load(coverUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .error(
                Glide.with(this).load("$serverBaseUrl/cover/$coverId").centerCrop()
            )
            .into(ivCover)

        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = serverBaseUrl,
            downloadHelper = downloadHelper,
            onSongClick = { _, position ->
                val activity = requireActivity() as? MainActivity
                val localPaths = currentSongs.map { downloadHelper.getLocalFilePath(it.id) }
                activity?.playbackService?.setQueueAndPlay(currentSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { _, _ -> },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song ->
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.playNext(song)
                Toast.makeText(requireContext(), "Se reproducirá a continuación", Toast.LENGTH_SHORT).show()
            },
            onPlaylistAddClick = { song ->
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.addToQueue(song)
                Toast.makeText(requireContext(), "Añadida al final de la cola", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager)
            }
        )

        rvSongs.layoutManager = LinearLayoutManager(requireContext())
        rvSongs.adapter = adapter

        btnPlay.setOnClickListener {
            if (currentSongs.isNotEmpty()) {
                val activity = requireActivity() as? MainActivity
                val localPaths = currentSongs.map { downloadHelper.getLocalFilePath(it.id) }
                activity?.playbackService?.setQueueAndPlay(currentSongs, 0, localPaths)
            }
        }

        btnDownload.setOnClickListener {
            val songsToDownload = currentSongs.filter { !downloadHelper.isDownloaded(it.id) }
            if (songsToDownload.isEmpty()) {
                Toast.makeText(requireContext(), "Todas las canciones están descargadas", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(requireContext(), "Descargando...", Toast.LENGTH_SHORT).show()
            activity?.lifecycleScope?.launch {
                for (song in songsToDownload) {
                    val success = downloadHelper.download(song, "$serverBaseUrl/audio/${song.id}")
                    if (success) {
                        adapter.refreshDownloadStates()
                        btnDownload.text = currentSongs.count { !downloadHelper.isDownloaded(it.id) }.toString()
                    }
                }
            }
        }

        loadSongs()
    }

    private fun hideCurrentArtist() {
        val aId = itemId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.hideArtist(aId, HideArtistRequest(sessionManager.getUserId()))
                Toast.makeText(requireContext(), "Artista ocultado", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al ocultar artista", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSongs() {
        val id = itemId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = when (itemType) {
                    "ARTIST" -> RetrofitClient.api.getArtistSongs(id, sessionManager.getUserId())
                    "GENRE" -> RetrofitClient.api.getGenreSongs(id, sessionManager.getUserId())
                    "YEAR" -> RetrofitClient.api.getYearSongs(id.toInt(), sessionManager.getUserId())
                    else -> return@launch
                }
                if (response.isSuccessful && response.body() != null) {
                    val songs = response.body()!!.songs
                    // Garantizar que el artista se muestre correctamente si estamos en modo ARTISTA
                    currentSongs = if (itemType == "ARTIST") {
                        songs.map { it.copy(artist = it.artist ?: itemName) }
                    } else {
                        songs
                    }
                    adapter.updateSongs(currentSongs)
                    
                    val totalDuration = currentSongs.sumOf { it.duration ?: 0.0 }
                    tvInfo.text = "${currentSongs.size} canciones · ${formatDuration(totalDuration)}"
                    btnDownload.text = currentSongs.count { !downloadHelper.isDownloaded(it.id) }.toString()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al cargar canciones: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatDuration(durationSeconds: Double): String {
        val totalSeconds = durationSeconds.toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
                if (!response.isSuccessful) {
                    sessionManager.addPendingLike(song.id, newLiked)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar para sincronizar al volver al servidor
                sessionManager.addPendingLike(song.id, newLiked)
            }
        }
    }

    private fun toggleDownload(song: Song) {
        val serverBaseUrl = ApiConfig.BASE_URL
        if (downloadHelper.isDownloaded(song.id)) {
            downloadHelper.removeDownload(song.id)
            adapter.refreshDownloadStates()
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val success = downloadHelper.download(song, "$serverBaseUrl/audio/${song.id}")
                if (success) adapter.refreshDownloadStates()
            }
        }
    }

    companion object {
        private const val ARG_ID = "item_id"
        private const val ARG_NAME = "item_name"
        private const val ARG_TYPE = "item_type"
        private const val ARG_COVER_ID = "cover_id"

        fun newInstance(id: String, name: String, type: String, coverId: String?) =
            CollectionDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, id)
                    putString(ARG_NAME, name)
                    putString(ARG_TYPE, type)
                    putString(ARG_COVER_ID, coverId)
                }
            }
    }
}