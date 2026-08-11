package com.example.localfly.ai

import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Motor de IA "local" (en el dispositivo), inspirado en el motor de la versión web
 * (src/services/RecommendationEngine.js + AIWriter.js).
 *
 * No requiere API KEY ni conexión a un servicio de IA en la nube: analiza localmente
 * los gustos del usuario (likes, artistas favoritos, años y portadas) y genera
 * recomendaciones y un resumen de texto personalizado.
 */
class AIRecommendationManager(private val sessionManager: SessionManager) {

    /**
     * Devuelve hasta [limit] canciones recomendadas usando puntuación local:
     *  - Bonus por artista favorito
     *  - Bonus por año cercano a los que más le gustan
     *  - Bonus por tener portada
     *  - Diversidad: máximo 2 canciones por artista
     */
    suspend fun getRecommendations(limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId() ?: return@withContext emptyList()
        val favArtistIds = sessionManager.getFavoriteArtists()

        // 1. Canciones que le gustan (para extraer patrones)
        val likedResp = RetrofitClient.api.getLikedSongs(userId, limit = 50)
        val likedSongs = if (likedResp.isSuccessful) likedResp.body()?.songs ?: emptyList() else emptyList()

        // 2. Toda la biblioteca disponible para recomendar
        val libResp = RetrofitClient.api.getLibrary(userId, limit = 500)
        val allSongs = if (libResp.isSuccessful) libResp.body()?.songs ?: emptyList() else emptyList()

        if (allSongs.isEmpty()) return@withContext emptyList()

        // Mapear IDs de artistas favoritos a nombres (para emparejar con las canciones)
        val favArtistNames = resolveFavoriteArtistNames(userId, favArtistIds).toSet()
        val likedIds = likedSongs.mapNotNull { it.id }.toSet()

        // Sin likes: recomendar de artistas favoritos o, si no hay, aleatorias (como la web)
        if (likedIds.isEmpty()) {
            val favSongs = allSongs.filter { it.artist in favArtistNames }
            val pool = if (favSongs.isNotEmpty()) favSongs else allSongs
            return@withContext diversify(pool.shuffled(), limit)
        }

        // Años favoritos (frecuencia)
        val yearCount = mutableMapOf<String, Int>()
        likedSongs.forEach { s ->
            val y = s.year?.toString() ?: "0"
            yearCount[y] = (yearCount[y] ?: 0) + 1
        }
        val topYears = yearCount.entries.sortedByDescending { it.value }.take(3).map { it.key }.toSet()

        // Candidatos: canciones que aún no le gustan
        val candidates = allSongs.filter { it.id !in likedIds }

        val favCandidates = candidates.filter { it.artist in favArtistNames }
        val otherCandidates = candidates.filter { it.artist !in favArtistNames }

        fun score(song: Song, isFavorite: Boolean): Int {
            var sc = if (isFavorite) 50 else 0        // bonus por artista favorito
            if (topYears.contains(song.year?.toString())) sc += if (isFavorite) 10 else 15
            if (song.hasCover) sc += 5                 // tiene portada
            return sc
        }

        val scored = (favCandidates.map { it to score(it, true) } +
                      otherCandidates.map { it to score(it, false) })
            .sortedWith(compareByDescending<Pair<Song, Int>> { it.second + (Math.random() * 10) })

        val diversified = mutableListOf<Song>()
        val artistCounts = mutableMapOf<String, Int>()
        for ((song, _) in scored) {
            val key = song.artist ?: song.id
            val count = artistCounts[key] ?: 0
            if (count < 2) { // diversidad: máx. 2 por artista
                diversified.add(song)
                artistCounts[key] = count + 1
            }
        }

        return@withContext diversified.take(limit)
    }
    /**
     * Genera localmente un resumen de texto personalizado (sin conexión),
     * equivalente al "fallback summary" / AIWriter de la versión web.
     */
    suspend fun generateRecommendationSummary(): String = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId()
        val favArtistIds = sessionManager.getFavoriteArtists()

        val recommendations = getRecommendations(10)
        val favNames = if (userId != null) resolveFavoriteArtistNames(userId, favArtistIds) else emptyList()

        if (recommendations.isEmpty()) {
            return@withContext "🎵 Todavía no tengo suficiente información. Selecciona algunos artistas favoritos para que la IA local pueda recomendarte música."
        }

        val topArtist = favNames.firstOrNull() ?: recommendations.first().artist
        val topSong = recommendations.first()

        return@withContext "🤖 IA local: analicé tus gustos y seleccioné ${recommendations.size} canciones nuevas para ti. " +
            "Por artistas como \"$topArtist\" te sugiero empezar con \"${topSong.title}\" de ${topSong.artist ?: "desconocido"}. " +
            "¡Explóralas en tu inicio!"
    }

    /** Convierte los IDs de artistas favoritos guardados en sus nombres usando la API. */
    private suspend fun resolveFavoriteArtistNames(userId: String?, favIds: Set<String>): List<String> {
        if (favIds.isEmpty()) return emptyList()
        return try {
            val resp = RetrofitClient.api.getArtists(userId, limit = 1000)
            if (resp.isSuccessful && resp.body() != null) {
                resp.body()!!.items
                    .filter { it.id in favIds }
                    .map { it.name }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Limita la lista respetando un máximo de canciones por artista. */
    private fun diversify(songs: List<Song>, limit: Int): List<Song> {
        val result = mutableListOf<Song>()
        val artistCounts = mutableMapOf<String, Int>()
        for (song in songs) {
            val key = song.artist ?: song.id
            val count = artistCounts[key] ?: 0
            if (count < 2) {
                result.add(song)
                artistCounts[key] = count + 1
            }
            if (result.size >= limit) break
        }
        return result
    }

}
