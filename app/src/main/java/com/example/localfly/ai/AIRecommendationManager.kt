package com.example.localfly.ai

import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AIRecommendationManager(private val sessionManager: SessionManager) {

    suspend fun getRecommendations(): List<Song> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId() ?: return@withContext emptyList()
        val favArtists = sessionManager.getFavoriteArtists()
        
        // 1. Obtener canciones que le gustan
        val likedResp = RetrofitClient.api.getLikedSongs(userId, limit = 50)
        val likedSongs = if (likedResp.isSuccessful) likedResp.body()?.songs ?: emptyList() else emptyList()
        
        // 2. Obtener toda la biblioteca disponible para recomendar
        val libResp = RetrofitClient.api.getLibrary(userId, limit = 500)
        val allSongs = if (libResp.isSuccessful) libResp.body()?.songs ?: emptyList() else emptyList()
        
        if (allSongs.isEmpty()) return@withContext emptyList()

        // 3. Lógica de "IA": 
        // Si no tenemos API KEY real, usamos una lógica algorítmica avanzada que simula IA:
        // Mezclamos canciones de artistas favoritos + canciones de géneros similares a las que le gustan.
        
        val likedArtists = likedSongs.mapNotNull { it.artist }.toSet()
        val searchPool = likedArtists + favArtists
        
        val recommendations = mutableListOf<Song>()
        
        // Prioridad 1: Canciones de artistas favoritos que NO tengan LIKE aún
        val favArtistSongs = allSongs.filter { it.artist in searchPool && !it.liked }
        recommendations.addAll(favArtistSongs.shuffled().take(10))
        
        // Prioridad 2: Si sobran espacios, rellenar con canciones aleatorias para descubrimiento
        val otherSongs = allSongs.filter { it !in recommendations && !it.liked }
        recommendations.addAll(otherSongs.shuffled().take(10 - recommendations.size))
        
        return@withContext recommendations.take(10)
    }
}