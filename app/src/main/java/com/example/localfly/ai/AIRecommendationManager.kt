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
class AIRecommendationManager(
    private val sessionManager: SessionManager,
    private val weightsStore: com.example.localfly.ai.AIWeightsStore? = null
) {

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

        val w = weightsStore?.getWeights() ?: emptyMap()
        fun weight(factor: String): Float = w[factor] ?: 1.0f

        // Devuelve la puntuación total Y qué factores influyeron (para
        // poder atribuir feedback más adelante si esta canción termina
        // siendo elegida).
        fun scoreWithFactors(song: Song): Pair<Int, List<String>> {
            var sc = 0f
            val firedFactors = mutableListOf<String>()
            val isFavArtist = song.artist in favArtistNames
            val hasLikedOtherSongsFromThisArtist = song.artist in likedArtists

            if (isFavArtist) {
                sc += 25 * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_FAV_ARTIST)
                firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_FAV_ARTIST
            }

            if (hasLikedOtherSongsFromThisArtist) {
                sc += 15 * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_LIKED_ARTIST)
                firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_LIKED_ARTIST
            }

            if (seedSong != null) {
                if (song.artist != null && song.artist == seedSong.artist) {
                    sc += 40 * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_SEED_ARTIST)
                    firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_SEED_ARTIST
                }
                val seedYear = seedSong.year
                val songYear = song.year
                if (seedYear != null && songYear != null && kotlin.math.abs(songYear - seedYear) <= 3) {
                    sc += 15 * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_SEED_DECADE)
                    firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_SEED_DECADE
                }
            }

            val songDecade = if (song.year != null) (song.year / 10) * 10 else -1
            if (topDecades.contains(songDecade)) {
                val base = if (isFavArtist || hasLikedOtherSongsFromThisArtist) 20 else 10
                sc += base * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_DECADE_MATCH)
                firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_DECADE_MATCH
            }

            if (song.hasCover) {
                sc += 5 * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_HAS_COVER)
                firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_HAS_COVER
            }

            sc += (Math.random() * 5).toFloat()

            return sc.toInt() to firedFactors
        }

        val scoredWithFactors = candidates.map { song ->
            val (sc, factors) = scoreWithFactors(song)
            Triple(song, sc, factors)
        }.sortedByDescending { it.second }

        val scored = scoredWithFactors.map { it.first to it.second }
        val factorsBySongId = scoredWithFactors.associate { it.first.id to it.third }

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

        val result = diversified.take(limit)
        if (weightsStore != null) {
            for (song in result) {
                val factors = factorsBySongId[song.id]
                if (!factors.isNullOrEmpty()) {
                    weightsStore.recordAttribution(song.id, factors)
                }
            }
        }
        return@withContext result
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
