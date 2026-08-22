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
    suspend fun getRecommendations(
        limit: Int = 10,
        seedSong: Song? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId() ?: return@withContext emptyList()
        val favArtistIds = sessionManager.getFavoriteArtists()

        // 1. Canciones que le gustan (para extraer patrones profundos)
        val likedResp = RetrofitClient.api.getLikedSongs(userId, limit = 100)
        val likedSongs = if (likedResp.isSuccessful) likedResp.body()?.songs ?: emptyList() else emptyList()

        // 2. Toda la biblioteca disponible para recomendar
        val libResp = RetrofitClient.api.getLibrary(userId, limit = 500)
        val allSongs = if (libResp.isSuccessful) libResp.body()?.songs ?: emptyList() else emptyList()

        if (allSongs.isEmpty()) return@withContext emptyList()

        // Mapear IDs de artistas favoritos a nombres
        val favArtistNames = resolveFavoriteArtistNames(userId, favArtistIds).toSet()
        val likedIds = likedSongs.mapNotNull { it.id }.toSet()

        // Análisis de gustos por década de las canciones con "Like"
        val preferredDecades = mutableMapOf<Int, Int>()

        likedSongs.forEach { song ->
            song.year?.let { y ->
                val decade = (y / 10) * 10
                preferredDecades[decade] = (preferredDecades[decade] ?: 0) + 1
            }
        }

        val topDecades = preferredDecades.entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet()

        // Análisis específico: ¿Qué géneros/estilos le gustan de sus artistas favoritos?
        val likedArtists = likedSongs.mapNotNull { it.artist }.toSet()

        // Candidatos: canciones que aún no le gustan
        val candidates = allSongs.filter { it.id !in likedIds }

        fun score(song: Song): Int {
            var sc = 0
            val isFavArtist = song.artist in favArtistNames
            val hasLikedOtherSongsFromThisArtist = song.artist in likedArtists
            
            // Puntuación base
            if (isFavArtist) {
                // Si es un artista favorito, tiene un peso importante pero no definitivo
                sc += 25 
            }
            
            if (hasLikedOtherSongsFromThisArtist) {
                // Si ya le gustan otras canciones de este artista, es una señal fuerte de gusto por su estilo
                sc += 15
            }
            
            // Si hay una canción semilla (p.ej. la que está sonando y ya
            // no tiene más canciones propias en la cola), priorizar
            // mismo artista y años cercanos como aproximación de "mismo
            // estilo" (no hay campo de género disponible en el modelo).
            if (seedSong != null) {
                if (song.artist != null && song.artist == seedSong.artist) sc += 40
                val seedYear = seedSong.year
                val songYear = song.year
                if (seedYear != null && songYear != null && kotlin.math.abs(songYear - seedYear) <= 3) {
                    sc += 15
                }
            }
            
            // El peso de la "era" musical (década) es fundamental para filtrar canciones que no encajan
            val songDecade = if (song.year != null) (song.year / 10) * 10 else -1
            if (topDecades.contains(songDecade)) {
                // Si la canción es de una década que le gusta, sumamos puntos.
                // Si además es un artista que le gusta, el bonus es mayor.
                sc += if (isFavArtist || hasLikedOtherSongsFromThisArtist) 20 else 10
            }
            
            // Bonus por tener portada (calidad visual)
            if (song.hasCover) sc += 5

            // Aleatoriedad ligera (0-5) para que las recomendaciones varíen un poco
            sc += (Math.random() * 5).toInt()
            
            return sc
        }

        val scored = candidates.map { it to score(it) }
            .sortedByDescending { it.second }

        val diversified = mutableListOf<Song>()
        val artistCounts = mutableMapOf<String, Int>()
        for ((song, _) in scored) {
            val key = song.artist ?: song.id
            val count = artistCounts[key] ?: 0
            if (count < 2) { 
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
