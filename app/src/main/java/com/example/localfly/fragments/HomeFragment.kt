package com.example.localfly.fragments

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.adapters.HorizontalCardAdapter
import com.example.localfly.adapters.LikedSongsAdapter
import com.example.localfly.databinding.FragmentHomeBinding
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.network.*
import com.example.localfly.utils.CoverPlaceholder
import com.example.localfly.utils.GenreUtils
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.util.Calendar

@androidx.media3.common.util.UnstableApi
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var sessionManager: SessionManager
    private lateinit var downloadHelper: DownloadManagerHelper
    private var selectedMoodIndex = 0

    // Base URL del servidor (debe coincidir con RetrofitClient/ApiConfig)
    private val serverBaseUrl = RetrofitClient.getBaseUrl()

    // Adaptadores
    private lateinit var likedAdapter: LikedSongsAdapter
    private lateinit var playlistAdapter: HorizontalCardAdapter
    private lateinit var podcastAdapter: HorizontalCardAdapter
    private lateinit var albumAdapter: HorizontalCardAdapter
    private lateinit var artistAdapter: HorizontalCardAdapter
    private lateinit var genreAdapter: HorizontalCardAdapter
    private lateinit var yearAdapter: HorizontalCardAdapter
    private lateinit var recommendationsAdapter: LikedSongsAdapter
    private lateinit var librarySectionAdapter: LikedSongsAdapter

    private val moods = listOf(
        "Energético" to "⚡",
        "Relajado" to "🌊",
        "Feliz" to "☀️",
        "Melancólico" to "☁️",
        "Romántico" to "💜",
        "Intenso" to "🔥",
        "Épico" to "🏔️",
        "Nocturno" to "🌙"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())
        downloadHelper = DownloadManagerHelper.getInstance(requireContext())

        setupGreeting()
        setupMoods()
        setupAdapters()
        setupSettingsButton()
        loadData()

        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnNotifications.setOnClickListener {
            Toast.makeText(requireContext(), "No hay notificaciones nuevas", Toast.LENGTH_SHORT).show()
        }

        // Listeners "Ver todo"
        binding.tvSeeAllLiked.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, LikedSongsFragment())
                .addToBackStack(null)
                .commit()
        }
        // Configurar secciones estándar
        setupSection(binding.sectionPlaylists.root, "Mis Playlists") { parentFragmentManager.beginTransaction().replace(R.id.container, PlaylistsFragment()).addToBackStack(null).commit() }
        setupSection(binding.sectionPodcasts.root, "Podcasts") { parentFragmentManager.beginTransaction().replace(R.id.container, PodcastsFragment()).addToBackStack(null).commit() }
        setupSection(binding.sectionAlbums.root, "Álbumes") { openSeeAll(CollectionListFragment.Type.ALBUM) }
        setupSection(binding.sectionArtists.root, "Artistas") { openSeeAll(CollectionListFragment.Type.ARTIST) }
        setupSection(binding.sectionGenres.root, "Géneros") { openSeeAll(CollectionListFragment.Type.GENRE) }
        setupSection(binding.sectionYears.root, "Por Año") { openSeeAll(CollectionListFragment.Type.YEAR) }
        setupSection(binding.sectionLibrary.root, "Tu Biblioteca") { parentFragmentManager.beginTransaction().replace(R.id.container, LibraryFragment()).addToBackStack(null).commit() }

        // Recomendaciones: no hay pantalla dedicada para "ver todo", se oculta el enlace.
        binding.sectionRecommendations.tvSectionTitle.text = "Recomendaciones para ti"
        binding.sectionRecommendations.tvSectionSeeAll.visibility = View.GONE
    }

    private fun setupSection(include: View, title: String, onSeeAll: () -> Unit) {
        include.findViewById<TextView>(R.id.tvSectionTitle).text = title
        include.findViewById<TextView>(R.id.tvSectionSeeAll).setOnClickListener { onSeeAll() }
    }

    private fun setupMoods() {
        binding.layoutHomeMoods.removeAllViews()
        moods.forEachIndexed { index, (name, emoji) ->
            val moodView = layoutInflater.inflate(R.layout.item_home_mood, binding.layoutHomeMoods, false)
            val emojiText = moodView.findViewById<TextView>(R.id.tvMoodEmoji)
            val nameText = moodView.findViewById<TextView>(R.id.tvMoodName)
            val selected = index == selectedMoodIndex
            emojiText.text = emoji
            nameText.text = name
            val card = moodView as? com.google.android.material.card.MaterialCardView
            card?.setCardBackgroundColor(if (selected) Color.parseColor("#1DB954") else Color.parseColor("#1B1B1F"))
            card?.strokeColor = if (selected) Color.parseColor("#7EF6AB") else Color.parseColor("#32FFFFFF")
            nameText.setTextColor(if (selected) Color.WHITE else Color.parseColor("#F3F3F3"))
            moodView.setOnClickListener {
                selectedMoodIndex = index
                setupMoods()
                Toast.makeText(requireContext(), "Filtrando por $name...", Toast.LENGTH_SHORT).show()
            }
            binding.layoutHomeMoods.addView(moodView)
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

    private fun updateFeatured(song: Song) {
        val featured = binding.cardFeatured
        featured.tvFeaturedTitle.text = song.title
        featured.tvFeaturedArtist.text = song.artist ?: "Artista desconocido"
        
        val coverUrl = "$serverBaseUrl/cover/${song.id}"
        Glide.with(this)
            .load(coverUrl)
            .placeholder(CoverPlaceholder.drawable(song.id))
            .centerCrop()
            .into(featured.ivFeaturedCover)
            
        featured.fabFeaturedPlay.setOnClickListener {
            (requireActivity() as? MainActivity)?.playbackService?.playSong(song)
        }
    }

    private fun setupCategories(genres: List<Genre>) {
        binding.chipGroupHomeCategories.removeAllViews()

        val allChip = Chip(requireContext())
        allChip.text = "Todo"
        allChip.isCheckable = true
        allChip.isChecked = true
        allChip.setTextColor(Color.WHITE)
        allChip.setChipBackgroundColorResource(android.R.color.transparent)
        binding.chipGroupHomeCategories.addView(allChip)

        genres.take(10).forEach { genre ->
            val chip = Chip(requireContext())
            chip.text = genre.name
            chip.isCheckable = true
            chip.setTextColor(Color.WHITE)
            chip.setChipBackgroundColorResource(android.R.color.transparent)
            binding.chipGroupHomeCategories.addView(chip)
        }
    }

    private fun updateLastPodcast(episode: Song) {
        // Buscar el contenedor en binding por su ID de include
        val layout = binding.root.findViewById<View>(R.id.layoutContinuePodcast) ?: return
        layout.visibility = View.VISIBLE
        
        val tvTitle = layout.findViewById<TextView>(R.id.tvLastPodcastTitle)
        val tvAuthor = layout.findViewById<TextView>(R.id.tvLastPodcastAuthor)
        val pb = layout.findViewById<ProgressBar>(R.id.pbLastPodcast)
        val ivCover = layout.findViewById<ImageView>(R.id.ivLastPodcastCover)
        val btnPlay = layout.findViewById<ImageButton>(R.id.btnLastPodcastPlay)

        tvTitle?.text = episode.title
        tvAuthor?.text = episode.artist ?: "Podcast"
        
        val progress = if (episode.duration != null && episode.duration > 0) {
            ((episode.lastPositionMs / 1000.0) / episode.duration * 100).toInt()
        } else 0
        pb?.progress = progress
        
        if (ivCover != null) {
            Glide.with(this)
                .load("$serverBaseUrl/cover/${episode.id}")
                .placeholder(CoverPlaceholder.drawable(episode.id))
                .into(ivCover)
        }
            
        btnPlay?.setOnClickListener {
            (requireActivity() as? MainActivity)?.playbackService?.playSong(episode)
        }
    }

    private fun setupAdapters() {
        val activity = requireActivity() as? MainActivity

        // Canciones que me gustan (Lista Vertical)
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

        // Mis Playlists
        playlistAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.sectionPlaylists.rvSectionContent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.sectionPlaylists.rvSectionContent.adapter = playlistAdapter

        // Podcasts
        podcastAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> if (item is Podcast) openPodcastDetail(item) }
        )
        binding.sectionPodcasts.rvSectionContent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.sectionPodcasts.rvSectionContent.adapter = podcastAdapter

        // Álbumes
        albumAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.sectionAlbums.rvSectionContent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.sectionAlbums.rvSectionContent.adapter = albumAdapter

        // Artistas
        artistAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.sectionArtists.rvSectionContent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.sectionArtists.rvSectionContent.adapter = artistAdapter

        // Géneros
        genreAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.sectionGenres.rvSectionContent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.sectionGenres.rvSectionContent.adapter = genreAdapter

        // Años
        yearAdapter = HorizontalCardAdapter(
            emptyList(),
            onItemClick = { item -> openCollection(item) }
        )
        binding.sectionYears.rvSectionContent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.sectionYears.rvSectionContent.adapter = yearAdapter

        // Recomendaciones para ti (lista vertical, como en el diseño original)
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
        binding.sectionRecommendations.rvSectionContent.layoutManager = LinearLayoutManager(requireContext())
        binding.sectionRecommendations.rvSectionContent.adapter = recommendationsAdapter

        // Tu Biblioteca (previsualización vertical)
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
        binding.sectionLibrary.rvSectionContent.layoutManager = LinearLayoutManager(requireContext())
        binding.sectionLibrary.rvSectionContent.adapter = librarySectionAdapter

    }

    private fun loadData() {
        val userId = sessionManager.getUserId()
        if (userId == null) {
            Toast.makeText(requireContext(), "Usuario no autenticado", Toast.LENGTH_SHORT).show()
            return
        }

        fun applyHomeSongs(songs: List<Song>) {
            if (songs.isEmpty()) return
            likedAdapter.updateSongs(songs.take(20)) // Mostrar más canciones en el Home
        }

        viewLifecycleOwner.lifecycleScope.launch {
            Log.d("HomeFragment", "Loading data for userId: $userId")
            // 1. Canciones que me gustan (Limite aumentado a 50 para asegurar contenido)
            try {
                val likedResp = RetrofitClient.api.getLikedSongs(userId = userId, limit = 50)
                if (likedResp.isSuccessful && likedResp.body() != null) {
                    val likedSongs = likedResp.body()!!.songs
                    Log.d("HomeFragment", "Loaded ${likedSongs.size} liked songs")
                    if (likedSongs.isNotEmpty()) {
                        applyHomeSongs(likedSongs)
                    } else {
                        val libraryResp = RetrofitClient.api.getLibrary(userId = userId, limit = 50)
                        if (libraryResp.isSuccessful && libraryResp.body() != null) {
                            Log.d("HomeFragment", "Liked empty, fallback to library: ${libraryResp.body()!!.songs.size} songs")
                            applyHomeSongs(libraryResp.body()!!.songs)
                        }
                    }
                } else {
                    Log.w("HomeFragment", "Liked songs request failed: ${likedResp.code()}")
                    val libraryResp = RetrofitClient.api.getLibrary(userId = userId, limit = 50)
                    if (libraryResp.isSuccessful && libraryResp.body() != null) {
                        applyHomeSongs(libraryResp.body()!!.songs)
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Error loading liked songs", e)
                try {
                    val libraryResp = RetrofitClient.api.getLibrary(userId = userId, limit = 50)
                    if (libraryResp.isSuccessful && libraryResp.body() != null) {
                        applyHomeSongs(libraryResp.body()!!.songs)
                    }
                } catch (_: Exception) { }
            }

            // 2. Playlists
            try {
                val playlistsResp = RetrofitClient.api.getPlayLists(userId = userId)
                if (playlistsResp.isSuccessful && playlistsResp.body() != null) {
                    playlistAdapter.updateItems(playlistsResp.body()!!.playlists)
                }
            } catch (e: Exception) { }

            // 2c. Podcasts (Ahora agrupados por el servidor)
            try {
                val podcastsResp = RetrofitClient.api.getPodcasts(userId = userId)
                if (podcastsResp.isSuccessful && podcastsResp.body() != null) {
                    val podcasts = podcastsResp.body()!!.podcasts
                    podcastAdapter.updateItems(podcasts)
                }
            } catch (e: Exception) { 
                Log.e("HomeFragment", "Error loading podcasts", e)
            }

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
                    val flattenedGenres = GenreUtils.flattenLegacyGenres(rawGenres)
                    genreAdapter.updateItems(flattenedGenres.shuffled().take(20))
                    if (isAdded) setupCategories(flattenedGenres)
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
                if (isAdded) recommendationsAdapter.updateSongs(recommendations)
            } catch (e: Exception) { }

            // 8. Tu Biblioteca (previsualización de los primeros 10)
            try {
                val libResp = RetrofitClient.api.getLibrary(userId = userId, limit = 100)
                if (libResp.isSuccessful && libResp.body() != null) {
                    val songs = libResp.body()!!.songs
                    librarySectionAdapter.updateSongs(songs.take(10))

                    if (isAdded && songs.isNotEmpty()) {
                        updateFeatured(songs.shuffled().first())

                        // Buscar último podcast escuchado
                        val lastEp = songs.find { it.isEpisode && it.lastPositionMs > 0 }
                        if (lastEp != null) updateLastPodcast(lastEp)
                    }
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
        // Registrar localmente para que el admin pueda revisarla y, si quiere,
        // borrarla por completo del disco.
        SongAdminStore.recordDislikedSong(song)
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
            is Podcast -> {
                PodcastDetailFragment.newInstance(item.id, item.title)
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

    /** Abre una lista pública ajena: sin botones de administración (borrar/visibilidad). */
    private fun openPublicPlaylist(playlist: Playlist) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, PlaylistDetailFragment.newInstance(playlist.id, playlist.name, isOwner = false))
            .addToBackStack(null)
            .commit()
    }

    private fun openPodcastDetail(podcast: Podcast) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, PodcastDetailFragment.newInstance(podcast.id, podcast.title))
            .addToBackStack(null)
            .commit()
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