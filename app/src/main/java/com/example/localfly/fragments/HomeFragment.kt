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
import com.example.localfly.R
import com.example.localfly.adapters.HorizontalCardAdapter
import com.example.localfly.adapters.LikedSongsAdapter
import com.example.localfly.databinding.FragmentHomeBinding
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var sessionManager: SessionManager
    private lateinit var downloadHelper: DownloadManagerHelper

    // Base URL del servidor (debe coincidir con RetrofitClient/ApiConfig)
    private val serverBaseUrl = ApiConfig.BASE_URL

    // Adaptadores
    private lateinit var likedAdapter: LikedSongsAdapter
    private lateinit var albumAdapter: HorizontalCardAdapter
    private lateinit var artistAdapter: HorizontalCardAdapter
    private lateinit var genreAdapter: HorizontalCardAdapter
    private lateinit var yearAdapter: HorizontalCardAdapter
    private lateinit var recommendationsAdapter: LikedSongsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())
        downloadHelper = DownloadManagerHelper(requireContext())

        setupGreeting()
        setupAdapters()
        loadData()

        // Listeners "Ver todo" (por ahora solo toast)
        binding.tvSeeAllAlbums.setOnClickListener {
            Toast.makeText(requireContext(), "Ver todos los álbumes", Toast.LENGTH_SHORT).show()
        }
        binding.tvSeeAllArtists.setOnClickListener {
            Toast.makeText(requireContext(), "Ver todos los artistas", Toast.LENGTH_SHORT).show()
        }
        binding.tvSeeAllGenres.setOnClickListener {
            Toast.makeText(requireContext(), "Ver todos los géneros", Toast.LENGTH_SHORT).show()
        }
        binding.tvSeeAllYears.setOnClickListener {
            Toast.makeText(requireContext(), "Ver todos los años", Toast.LENGTH_SHORT).show()
        }
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
        // Canciones que me gustan
        likedAdapter = LikedSongsAdapter(
            mutableListOf(),
            downloadHelper,
            onLikeClick = { song -> toggleLike(song) },
            onDislikeClick = { song -> hideSong(song) },
            onItemClick = { song -> playSong(song) },
            onDownloadClick = { song -> toggleDownload(song) }
        )
        binding.rvLikedSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLikedSongs.adapter = likedAdapter

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
            onItemClick = { song -> playSong(song) },
            onDownloadClick = { song -> toggleDownload(song) }
        )
        binding.rvRecommendations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecommendations.adapter = recommendationsAdapter
    }

    private fun loadData() {
        val userId = sessionManager.getUserId()
        if (userId == null) {
            Toast.makeText(requireContext(), "Usuario no autenticado", Toast.LENGTH_SHORT).show()
            return
        }

        // Usar viewLifecycleOwner para que se cancele al destruir la vista
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Verificar que el fragmento sigue adjunto
                if (!isAdded) return@launch

                // 1. Canciones que me gustan
                val likedResp = RetrofitClient.api.getLikedSongs(userId = userId, limit = 20)
                if (likedResp.isSuccessful && likedResp.body() != null) {
                    likedAdapter.updateSongs(likedResp.body()!!.songs)
                }

                // 2. Álbumes
                val albumsResp = RetrofitClient.api.getAlbums(userId = userId, limit = 20)
                if (albumsResp.isSuccessful && albumsResp.body() != null) {
                    albumAdapter.updateItems(albumsResp.body()!!.items)
                }

                // 3. Artistas
                val artistsResp = RetrofitClient.api.getArtists(userId = userId, limit = 20)
                if (artistsResp.isSuccessful && artistsResp.body() != null) {
                    artistAdapter.updateItems(artistsResp.body()!!.items)
                }

                // 4. Géneros
                val genresResp = RetrofitClient.api.getGenres(userId = userId, limit = 20)
                if (genresResp.isSuccessful && genresResp.body() != null) {
                    genreAdapter.updateItems(genresResp.body()!!.items)
                }

                // 5. Años
                val yearsResp = RetrofitClient.api.getYears(userId = userId, limit = 20)
                if (yearsResp.isSuccessful && yearsResp.body() != null) {
                    yearAdapter.updateItems(yearsResp.body()!!.items)
                }

                // 6. Recomendaciones (simuladas)
                val libraryResp = RetrofitClient.api.getLibrary(userId = userId, limit = 100, offset = 0)
                if (libraryResp.isSuccessful && libraryResp.body() != null) {
                    val allSongs = libraryResp.body()!!.songs
                    val unliked = allSongs.filter { !it.liked }
                    val shuffled = unliked.shuffled().take(10)
                    recommendationsAdapter.updateSongs(shuffled)
                }

                // 7. Resumen mensual (placeholder)
                if (isAdded) {
                    val count = libraryResp.body()?.songs?.size ?: 0
                    binding.tvMonthlySummary.text = "Este mes has escuchado $count canciones."
                }

            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error al cargar datos: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // Funciones de interacción (delegar a la actividad o al servicio)
    private fun toggleLike(song: Song) {
        // Implementar llamada a API y actualizar UI
        // Por ahora solo notificamos
        Toast.makeText(requireContext(), "Like toggled: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun hideSong(song: Song) {
        // Implementar ocultar canción
        Toast.makeText(requireContext(), "Ocultar: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun playSong(song: Song) {
        // Iniciar reproducción (usar PlaybackService)
        Toast.makeText(requireContext(), "Reproducir: ${song.title}", Toast.LENGTH_SHORT).show()
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

    /** Refresca el icono de descarga de las listas visibles. */
    private fun refreshDownloadStates() {
        if (::likedAdapter.isInitialized) likedAdapter.refreshDownloadStates()
        if (::recommendationsAdapter.isInitialized) recommendationsAdapter.refreshDownloadStates()
    }

    override fun onResume() {
        super.onResume()
        // Refresca por si se descargó/borró una canción desde otra pantalla
        refreshDownloadStates()
    }

    private fun openCollection(item: Any) {
        // Abrir vista de colección (álbum, artista, etc.)
        Toast.makeText(requireContext(), "Abrir colección: $item", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}