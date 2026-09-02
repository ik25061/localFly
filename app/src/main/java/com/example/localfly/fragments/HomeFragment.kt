package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.adapters.HorizontalCardAdapter
import com.example.localfly.adapters.LikedSongsAdapter
import com.example.localfly.databinding.FragmentHomeBinding
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.network.*
import com.example.localfly.utils.GenreUtils
import kotlinx.coroutines.launch
import java.util.Calendar

@androidx.media3.common.util.UnstableApi
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var sessionManager: SessionManager
    private lateinit var downloadHelper: DownloadManagerHelper

    // Base URL del servidor (debe coincidir con RetrofitClient/ApiConfig)
    private val serverBaseUrl = ApiConfig.BASE_URL

    // Adaptadores
    private lateinit var likedAdapter: LikedSongsAdapter
    private lateinit var playlistAdapter: HorizontalCardAdapter
    private lateinit var albumAdapter: HorizontalCardAdapter
    private lateinit var artistAdapter: HorizontalCardAdapter
    private lateinit var genreAdapter: HorizontalCardAdapter
    private lateinit var yearAdapter: HorizontalCardAdapter
    private lateinit var recommendationsAdapter: LikedSongsAdapter
    private lateinit var librarySectionAdapter: LikedSongsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())

        setupGreeting()
        setupAdapters()
        setupSettingsButton()
        loadData()

        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Listeners "Ver todo"
        binding.tvSeeAllLiked.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, LikedSongsFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.tvSeeAllAlbums.setOnClickListener {
            openSeeAll(CollectionListFragment.Type.ALBUM)
        }
        binding.tvSeeAllArtists.setOnClickListener {
            openSeeAll(CollectionListFragment.Type.ARTIST)
        }
        binding.tvSeeAllGenres.setOnClickListener {
            openSeeAll(CollectionListFragment.Type.GENRE)
        }
        binding.tvSeeAllYears.setOnClickListener {
            openSeeAll(CollectionListFragment.Type.YEAR)
        }
        binding.tvSeeAllLibrary.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, LibraryFragment())
                .addToBackStack(null)
                .commit()
        }

        // Observar progreso de descargas (especialmente para la auto-descarga de 500 temas)
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManagerHelper.downloadProgress.collect { progress ->
                if (progress.isDownloading) {
                    binding.tvMonthlySummary.text = "🔄 Auto-descargando: ${progress.songTitle} (${progress.current}/${progress.total})"
                } else {
                    val count = downloadHelper.getDownloadedSongs().size
                    binding.tvMonthlySummary.text = "✅ Biblioteca offline: $count de 500 temas recomendados."
                }
            }
        }
    }

    private fun openSeeAll(type: CollectionListFragment.Type) {
        val fragment = CollectionListFragment.newInstance(type)
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun setupGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 0..5 -> "Buenas noches"
            in 6..11 -> "Buenos días"
            in 12..19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        binding.tvGreeting.text = greeting
    }

    private fun setupAdapters() {
        val activity = requireActivity() as? MainActivity

        // Canciones que me gustan
        likedAdapter = LikedSongsAdapter(
            mutableListOf(),
            downloadHelper,
            onLikeClick = { song -> toggleLike(song) },
            onDislikeClick = { song -> hideSong(song) },
            onItemClick = { song -> 
                activity?.playbackService?.playSong(song)
            },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song ->
                activity?.playbackService?.playNext(song)
                Toast.makeText(requireContext(), "Se reproducirá a continuación", Toast.LENGTH_SHORT).show()
            },
            onAddToQueueClick = { song ->
                activity?.playbackService?.addToQueue(song)
                Toast.makeText(requireContext(), "Añadida al final de la cola", Toast.LENGTH_SHORT).show()
            },
            onDeleteClick = { song -> removeSongFromHome(song) },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager)
            }
        )
        binding.rvLikedSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLikedSongs.adapter = likedAdapter

        // Playlists
        playlistAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.rvPlaylists.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvPlaylists.adapter = playlistAdapter

        // Álbumes
        albumAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.rvAlbums.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvAlbums.adapter = albumAdapter

        // Artistas
        artistAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.rvArtists.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvArtists.adapter = artistAdapter

        // Géneros
        genreAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.rvGenres.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvGenres.adapter = genreAdapter

        // Años
        yearAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.rvYears.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvYears.adapter = yearAdapter

        // Recomendaciones
        recommendationsAdapter = LikedSongsAdapter(
            mutableListOf(),
            downloadHelper,
            onLikeClick = { song -> toggleLike(song) },
            onDislikeClick = { song -> hideSong(song) },
            onItemClick = { song -> 
                activity?.playbackService?.playSong(song)
            },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song ->
                activity?.playbackService?.playNext(song)
                Toast.makeText(requireContext(), "Se reproducirá a continuación", Toast.LENGTH_SHORT).show()
            },
            onAddToQueueClick = { song ->
                activity?.playbackService?.addToQueue(song)
                Toast.makeText(requireContext(), "Añadida al final de la cola", Toast.LENGTH_SHORT).show()
            },
            onDeleteClick = { song -> removeSongFromHome(song) },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager)
            }
        )
        binding.rvRecommendations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecommendations.adapter = recommendationsAdapter

        // Nueva Sección: Tu Biblioteca
        librarySectionAdapter = LikedSongsAdapter(
            mutableListOf(),
            downloadHelper,
            onLikeClick = { song -> toggleLike(song) },
            onDislikeClick = { song -> hideSong(song) },
            onItemClick = { song -> 
                activity?.playbackService?.playSong(song)
            },
            onDownloadClick = { song -> toggleDownload(song) },
            onPlayNextClick = { song ->
                activity?.playbackService?.playNext(song)
            },
            onAddToQueueClick = { song ->
                activity?.playbackService?.addToQueue(song)
            },
            onDeleteClick = { song -> removeSongFromHome(song) },
            onAddToPlaylistClick = { song ->
                AddToPlaylistDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, song, sessionManager)
            }
        )
        binding.rvLibrarySection.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLibrarySection.adapter = librarySectionAdapter
    }

    private fun loadData() {
        val userId = sessionManager.getUserId()
        if (userId == null) {
            Toast.makeText(requireContext(), "Usuario no autenticado", Toast.LENGTH_SHORT).show()
            return
        }

        // Usar viewLifecycleOwner para que se cancele al destruir la vista
        viewLifecycleOwner.lifecycleScope.launch {
            // 1. Canciones que me gustan
            try {
                val likedResp = RetrofitClient.api.getLikedSongs(userId = userId, limit = 20)
                if (likedResp.isSuccessful && likedResp.body() != null) {
                    likedAdapter.updateSongs(likedResp.body()!!.songs)
                }
            } catch (e: Exception) { }

            // 2. Playlists
            try {
                val playlistsResp = RetrofitClient.api.getPlayLists(userId = userId)
                if (playlistsResp.isSuccessful && playlistsResp.body() != null) {
                    playlistAdapter.updateItems(playlistsResp.body()!!.playlists)
                }
            } catch (e: Exception) { }

            // 3. Álbumes
            try {
                val albumsResp = RetrofitClient.api.getAlbums(userId = userId, limit = 100)
                if (albumsResp.isSuccessful && albumsResp.body() != null) {
                    albumAdapter.updateItems(albumsResp.body()!!.items.shuffled().take(20))
                }
            } catch (e: Exception) { }

            // 4. Artistas
            try {
                val artistsResp = RetrofitClient.api.getArtists(userId = userId, limit = 100)
                if (artistsResp.isSuccessful && artistsResp.body() != null) {
                    artistAdapter.updateItems(artistsResp.body()!!.items.shuffled().take(20))
                }
            } catch (e: Exception) { }

            // 5. Géneros
            try {
                val genresResp = RetrofitClient.api.getGenres(userId = userId, limit = 100)
                if (genresResp.isSuccessful && genresResp.body() != null) {
                    val rawGenres = genresResp.body()!!.items
                    // Usar utilidad compartida para aplanar géneros legacy
                    val flattenedGenres = com.example.localfly.utils.GenreUtils.flattenLegacyGenres(rawGenres)
                    genreAdapter.updateItems(flattenedGenres.shuffled().take(20))
                }
            } catch (e: Exception) { }

            // 6. Años
            try {
                val yearsResp = RetrofitClient.api.getYears(userId = userId, limit = 100)
                if (yearsResp.isSuccessful && yearsResp.body() != null) {
                    yearAdapter.updateItems(yearsResp.body()!!.items.shuffled().take(20))
                }
            } catch (e: Exception) { }

            // 7. Recomendaciones con IA
            try {
                val aiManager = com.example.localfly.ai.AIRecommendationManager(sessionManager, com.example.localfly.ai.AIWeightsStore(requireContext()))
                val recommendations = aiManager.getRecommendations()
                if (isAdded) {
                    recommendationsAdapter.updateSongs(recommendations)
                }
            } catch (e: Exception) { }

            // 8. Tu Biblioteca (previsualización de los primeros 10)
            try {
                val libResp = RetrofitClient.api.getLibrary(userId = userId, limit = 10)
                if (libResp.isSuccessful && libResp.body() != null) {
                    librarySectionAdapter.updateSongs(libResp.body()!!.songs)
                }
            } catch (e: Exception) { }

            // 9. Resumen mensual (se maneja vía DownloadManagerHelper.downloadProgress)
        }
    }
    // Funciones de interacción (delegar a la actividad o al servicio)
    private fun toggleLike(song: Song) {
        val newLiked = !song.liked
        val updated = song.copy(liked = newLiked)

        if (::likedAdapter.isInitialized) {
            val idxLiked = likedAdapter.indexOf(song.id)
            if (idxLiked != -1) likedAdapter.updateSongAt(idxLiked, updated)
        }
        if (::recommendationsAdapter.isInitialized) {
            val idxRec = recommendationsAdapter.indexOf(song.id)
            if (idxRec != -1) recommendationsAdapter.updateSongAt(idxRec, updated)
        }
        if (::librarySectionAdapter.isInitialized) {
            val idxLib = librarySectionAdapter.indexOf(song.id)
            if (idxLib != -1) librarySectionAdapter.updateSongAt(idxLib, updated)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.likeSong(
                    song.id,
                    LikeRequest(sessionManager.getUserId(), newLiked)
                )
                if (!response.isSuccessful) {
                    sessionManager.addPendingLike(song.id, newLiked)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar para sincronizar al volver al servidor
                sessionManager.addPendingLike(song.id, newLiked)
            }
        }
    }

    private fun hideSong(song: Song) {
        if (::likedAdapter.isInitialized) likedAdapter.removeSongById(song.id)
        if (::recommendationsAdapter.isInitialized) recommendationsAdapter.removeSongById(song.id)
        if (::librarySectionAdapter.isInitialized) librarySectionAdapter.removeSongById(song.id)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.hideSong(
                    song.id,
                    HideRequest(sessionManager.getUserId())
                )
                if (!response.isSuccessful) {
                    sessionManager.addPendingDislike(song.id)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar para sincronizar al volver al servidor
                sessionManager.addPendingDislike(song.id)
            }
        }
    }

    /** Descarga la canción si no está descargada; si ya lo está, la elimina. */
    private fun toggleDownload(song: Song) {
        if (downloadHelper.isDownloaded(song.id)) {
            downloadHelper.removeDownload(song.id)
            Toast.makeText(requireContext(), "Descarga eliminada", Toast.LENGTH_SHORT).show()
            refreshDownloadStates()
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
                refreshDownloadStates()
            }
        }
    }

    /** Elimina la canción de la lista del Home (si aparece en alguna). */
    private fun removeSongFromHome(song: Song) {
        if (::likedAdapter.isInitialized) likedAdapter.removeSongById(song.id)
        if (::recommendationsAdapter.isInitialized) recommendationsAdapter.removeSongById(song.id)
        if (::librarySectionAdapter.isInitialized) librarySectionAdapter.removeSongById(song.id)
        Toast.makeText(requireContext(), "Canción eliminada de la lista", Toast.LENGTH_SHORT).show()
    }

    /** Refresca el icono de descarga de las listas visibles. */
    private fun refreshDownloadStates() {
        if (::likedAdapter.isInitialized) likedAdapter.refreshDownloadStates()
        if (::recommendationsAdapter.isInitialized) recommendationsAdapter.refreshDownloadStates()
        if (::librarySectionAdapter.isInitialized) librarySectionAdapter.refreshDownloadStates()
    }

    override fun onResume() {
        super.onResume()
        // Refresca por si se descargó/borró una canción desde otra pantalla
        refreshDownloadStates()
    }

    private fun openCollection(item: Any) {
        val fragment = when (item) {
            is Playlist -> {
                PlaylistDetailFragment.newInstance(item.id, item.name)
            }
            is Album -> {
                AlbumDetailFragment.newInstance(item.id, item.name, item.artist, item.coverId)
            }
            is com.example.localfly.network.Artist -> {
                CollectionDetailFragment.newInstance(item.id, item.name, "ARTIST", item.coverId)
            }
            is com.example.localfly.network.Genre -> {
                CollectionDetailFragment.newInstance(item.id, item.name, "GENRE", item.coverId)
            }
            is com.example.localfly.network.Year -> {
                CollectionDetailFragment.newInstance(item.year.toString(), item.year.toString(), "YEAR", item.coverId)
            }
            else -> null
        }

        fragment?.let {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, it)
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setupSettingsButton() {
        val username = sessionManager.getUsername() ?: "User"
        val firstLetter = username.take(1).uppercase()
        binding.tvSettingsAvatar.text = firstLetter
        
        // Generar un color persistente basado en el nombre
        val colors = listOf("#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#009688", "#4CAF50", "#FF9800", "#FF5722")
        val colorIndex = Math.abs(username.hashCode()) % colors.size
        val color = android.graphics.Color.parseColor(colors[colorIndex])
        
        val background = binding.btnSettings.background as android.graphics.drawable.GradientDrawable
        background.setColor(color)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}