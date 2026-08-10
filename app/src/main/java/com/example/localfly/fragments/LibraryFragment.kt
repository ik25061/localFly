package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.PlaybackService
import com.example.localfly.R
import com.example.localfly.SongAdapter
import com.example.localfly.network.*
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private lateinit var rvSongs: RecyclerView
    private lateinit var adapter: SongAdapter
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager
    
    private lateinit var tvSongCountInfo: android.widget.TextView
    private lateinit var btnDownloadAll: com.google.android.material.button.MaterialButton
    private lateinit var etSearch: android.widget.EditText
    
    private var currentOffset = 0
    private val limit = 100
    private var isLoading = false
    private var hasMore = true

    // Lista completa para búsqueda local si se desea, o para el contador
    private var fullSongsList: MutableList<Song> = mutableListOf()

    // Base URL del servidor (debe coincidir con RetrofitClient/ApiConfig)
    private val serverBaseUrl = ApiConfig.BASE_URL

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        downloadHelper = DownloadManagerHelper(requireContext())
        sessionManager = SessionManager(requireContext())

        rvSongs = view.findViewById(R.id.rvLibrarySongs)
        tvSongCountInfo = view.findViewById(R.id.tvSongCountInfo)
        btnDownloadAll = view.findViewById(R.id.btnDownloadAll)
        etSearch = view.findViewById(R.id.etSearch)

        // Inicializar adaptador
        adapter = SongAdapter(
            songs = mutableListOf(),
            serverBaseUrl = serverBaseUrl,
            downloadHelper = downloadHelper,
            onSongClick = { song, position ->
                val allSongs = adapter.currentSongs()
                val localPaths = allSongs.map { downloadHelper.getLocalFilePath(it.id) }
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.setQueueAndPlay(allSongs, position, localPaths)
            },
            onLikeClick = { song, position -> toggleLike(song, position) },
            onDislikeClick = { song, position -> hideSong(song, position) },
            onDownloadClick = { song -> toggleDownload(song) },
            onDeleteClick = { song, position -> deleteSong(song, position) },
            onPlayNextClick = { song ->
                val activity = requireActivity() as? MainActivity
                activity?.playbackService?.playNext(song)
            },
            onPlaylistAddClick = { song ->
                Toast.makeText(requireContext(), "Añadir a playlist: ${song.title}", Toast.LENGTH_SHORT).show()
            }
        )

        rvSongs.layoutManager = LinearLayoutManager(requireContext())
        rvSongs.adapter = adapter
        
        btnDownloadAll.setOnClickListener {
            val songsToDownload = fullSongsList.filter { !downloadHelper.isDownloaded(it.id) }
            if (songsToDownload.isEmpty()) {
                Toast.makeText(requireContext(), "Todas las canciones ya están descargadas", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            Toast.makeText(requireContext(), "Iniciando descarga de ${songsToDownload.size} canciones...", Toast.LENGTH_SHORT).show()
            
            // Usamos el scope de la actividad para que persista al cambiar de fragmento
            activity?.lifecycleScope?.launch {
                var count = 0
                for (song in songsToDownload) {
                    val audioUrl = "$serverBaseUrl/audio/${song.id}"
                    if (downloadHelper.download(song, audioUrl)) {
                        count++
                        // Si el fragmento sigue visible, refrescamos el contador y la lista
                        if (isAdded && !isDetached) {
                            adapter.refreshDownloadStates()
                            updateDownloadAllButton()
                        }
                    }
                }
                if (isAdded && !isDetached) {
                    Toast.makeText(requireContext(), "Se descargaron $count canciones", Toast.LENGTH_SHORT).show()
                }
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSongs(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        rvSongs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                val totalItemCount = layoutManager.itemCount

                // Solo cargamos más si no estamos filtrando por búsqueda
                if (etSearch.text.isNullOrBlank() && !isLoading && hasMore && lastVisibleItem >= totalItemCount - 5) {
                    loadLibrary(isNextPage = true)
                }
            }
        })

        // Cargar biblioteca
        loadLibrary()
    }

    private fun filterSongs(query: String) {
        if (query.isEmpty()) {
            adapter.updateSongs(fullSongsList)
        } else {
            val filtered = fullSongsList.filter { 
                it.title.contains(query, ignoreCase = true) || 
                (it.artist?.contains(query, ignoreCase = true) == true)
            }
            adapter.updateSongs(filtered)
        }
    }

    private fun updateDownloadAllButton() {
        val pendingCount = fullSongsList.count { !downloadHelper.isDownloaded(it.id) }
        btnDownloadAll.text = pendingCount.toString()
        // Si no hay nada pendiente, ocultamos o cambiamos icono? El usuario dijo "cuantas canciones puedo descargar"
    }

    private fun loadLibrary(isNextPage: Boolean = false) {
        if (isLoading) return
        
        val userId = sessionManager.getUserId()
        if (userId == null) {
            Toast.makeText(requireContext(), "Usuario no autenticado", Toast.LENGTH_SHORT).show()
            return
        }

        if (isNextPage) {
            currentOffset += limit
        } else {
            currentOffset = 0
            hasMore = true
            fullSongsList.clear()
        }

        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch

                val response = RetrofitClient.api.getLibrary(
                    userId = userId,
                    limit = limit,
                    offset = currentOffset
                )

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val songs = body.songs
                    
                    fullSongsList.addAll(songs)
                    
                    if (isNextPage) {
                        adapter.addSongs(songs)
                    } else {
                        adapter.updateSongs(songs)
                    }
                    
                    hasMore = body.pagination?.hasMore ?: (songs.size >= limit)
                    
                    // Actualizar UI del header
                    val totalCount = body.pagination?.total ?: adapter.itemCount
                    tvSongCountInfo.text = "$totalCount canciones"
                    updateDownloadAllButton()
                } else {
                    Toast.makeText(requireContext(), "Error al cargar biblioteca", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun deleteSong(song: Song, position: Int) {
        // Por ahora lo tratamos como ocultar, o podrías llamar a un endpoint de borrado real
        hideSong(song, position)
        Toast.makeText(requireContext(), "Canción eliminada", Toast.LENGTH_SHORT).show()
    }

    private fun toggleLike(song: Song, position: Int) {
        val newLiked = !song.liked
        adapter.updateSongAt(position, song.copy(liked = newLiked))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.likeSong(
                    song.id,
                    LikeRequest(sessionManager.getUserId(), newLiked)
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error al actualizar like", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hideSong(song: Song, position: Int) {
        adapter.removeAt(position)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                RetrofitClient.api.hideSong(
                    song.id,
                    HideRequest(sessionManager.getUserId())
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error al ocultar canción", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleDownload(song: Song) {
        if (downloadHelper.isDownloaded(song.id)) {
            downloadHelper.removeDownload(song.id)
            Toast.makeText(requireContext(), "Descarga eliminada", Toast.LENGTH_SHORT).show()
            adapter.refreshDownloadStates()
        } else {
            Toast.makeText(requireContext(), "Descargando \"${song.title}\"...", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                val audioUrl = "$serverBaseUrl/audio/${song.id}"
                val success = downloadHelper.download(song, audioUrl)
                if (success) {
                    Toast.makeText(requireContext(), "Descarga completa", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Error al descargar", Toast.LENGTH_SHORT).show()
                }
                adapter.refreshDownloadStates()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshDownloadStates()
    }
}