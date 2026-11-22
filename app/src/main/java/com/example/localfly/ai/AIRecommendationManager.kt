package com.example.localfly.ai

import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        seedSong: Song? = null,
        mood: String? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        val userId = sessionManager.getUserId() ?: return@withContext emptyList()
        val favArtistIds = sessionManager.getFavoriteArtists()
        val mixingEnabled = sessionManager.isPodcastMixingEnabled()

        // ... resto de la lógica inicial ...
        val includeMusic: Boolean
        val includePodcasts: Boolean

        if (mixingEnabled) {
            includeMusic = true
            includePodcasts = true
        } else {
            if (seedSong != null) {
                includeMusic = !seedSong.isEpisode
                includePodcasts = seedSong.isEpisode
            } else {
                includeMusic = true
                includePodcasts = false
            }
        }

        // Canciones marcadas como "No me gusta": nunca se recomiendan de nuevo
        // (bug: reaparecían en la cola aunque el usuario las hubiera descartado).
        val dislikedIds = com.example.localfly.network.SongAdminStore.getDislikedSongs()
            .mapNotNull { it.songId }.toSet()

        // 1. Canciones que le gustan
        val likedResp = RetrofitClient.api.getLikedSongs(userId, limit = 100)
        val likedSongs = (if (likedResp.isSuccessful) likedResp.body()?.songs ?: emptyList() else emptyList())
            .filter { it.id !in dislikedIds }

        // 2. Obtener candidatos
        val musicSongs = (if (includeMusic) {
            val libResp = RetrofitClient.api.getLibrary(userId, limit = 5000)
            if (libResp.isSuccessful) libResp.body()?.songs ?: emptyList() else emptyList()
        } else emptyList()).filter { it.id !in dislikedIds }

        val podcastEpisodes = if (includePodcasts) {
            try {
                val podcastsResp = RetrofitClient.api.getPodcasts(userId, limit = 20)
                if (podcastsResp.isSuccessful) {
                    podcastsResp.body()?.podcasts.orEmpty().take(20).chunked(5).flatMap { batch ->
                        coroutineScope {
                            batch.map { podcast -> async {
                                try {
                                    val pResp = RetrofitClient.api.getPodcast(podcast.id, userId)
                                    if (pResp.isSuccessful) {
                                        pResp.body()?.episodes?.map { ep ->
                                            Song(
                                                id = ep.id, title = ep.title, artist = podcast.author, album = podcast.title,
                                                year = null, duration = ep.duration, bpm = null, key = null, liked = false,
                                                hasCover = true, isEpisode = true, lastPositionMs = ep.lastPositionMs,
                                                subtitleUrl = ep.subtitleUrl
                                            )
                                        } ?: emptyList()
                                    } else emptyList()
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    emptyList()
                                }
                            } }.awaitAll().flatten()
                        }
                    }
                } else emptyList()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        } else emptyList()

        var allSongs = (musicSongs + podcastEpisodes).filter { it.id !in dislikedIds }

        if (allSongs.isEmpty()) return@withContext emptyList()

        // Mapear IDs de artistas favoritos a nombres
        val favArtistNames = resolveFavoriteArtistNames(userId, favArtistIds).map { it.trim().lowercase() }.toSet()
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
        val likedArtists = likedSongs.mapNotNull { it.artist }.map { it.trim().lowercase() }.toSet()

        // Perfil musical aprendido a partir de los likes.
        val genreWeights = mutableMapOf<String, Float>()
        val moodWeights = mutableMapOf<String, Float>()
        val likedBpms = mutableListOf<Double>()
        val likedKeys = mutableListOf<String>()
        likedSongs.forEach { liked ->
            liked.genre.orEmpty().forEach { genre ->
                val key = genre.trim().lowercase()
                if (key.isNotBlank()) genreWeights[key] = (genreWeights[key] ?: 0f) + 1f
            }
            liked.moods.orEmpty().forEach { item ->
                val key = item.name.trim().lowercase()
                if (key.isNotBlank()) moodWeights[key] = (moodWeights[key] ?: 0f) + 1f
            }
            liked.bpm?.takeIf { it > 0 }?.let(likedBpms::add)
            liked.key?.takeIf { it.isNotBlank() }?.let(likedKeys::add)
        }
        val targetBpm = seedSong?.bpm?.takeIf { it > 0 }
            ?: likedBpms.takeIf { it.isNotEmpty() }?.average()
        val targetKey = seedSong?.key ?: likedKeys.groupingBy { it.trim().lowercase() }.eachCount().maxByOrNull { it.value }?.key
        val playedIds = sessionManager.getPlayedSongIds()
        val requestedMood = mood?.trim()?.lowercase().orEmpty()

        // Candidatos: canciones que aún no le gustan
        val candidates = allSongs.filter { it.id !in likedIds && it.id != seedSong?.id }

        val w = weightsStore?.getWeights() ?: emptyMap()
        fun weight(factor: String): Float = w[factor] ?: 1.0f

        // Devuelve la puntuación total Y qué factores influyeron (para
        // poder atribuir feedback más adelante si esta canción termina
        // siendo elegida).
        fun scoreWithFactors(song: Song): Pair<Int, List<String>> {
            var sc = 0f
            val firedFactors = mutableListOf<String>()
            val normalizedArtist = song.artist?.trim()?.lowercase()
            val isFavArtist = normalizedArtist != null && normalizedArtist in favArtistNames
            val hasLikedOtherSongsFromThisArtist = normalizedArtist != null && normalizedArtist in likedArtists

            if (isFavArtist) {
                sc += 25 * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_FAV_ARTIST)
                firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_FAV_ARTIST
            }

            if (hasLikedOtherSongsFromThisArtist) {
                sc += 15 * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_LIKED_ARTIST)
                firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_LIKED_ARTIST
            }

            if (seedSong != null) {
                if (song.artist != null && song.artist.equals(seedSong.artist, ignoreCase = true)) {
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

            // Coincidencia de géneros aprendidos (no depende solo del artista).
            val matchingGenres = song.genre.orEmpty().map { it.trim().lowercase() }
                .filter { (genreWeights[it] ?: 0f) > 0f }
            if (matchingGenres.isNotEmpty()) {
                val points = (matchingGenres.sumOf { (genreWeights[it] ?: 0f).toDouble() } * 5.0).toFloat().coerceAtMost(25f)
                sc += points * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_GENRE_MATCH)
                firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_GENRE_MATCH
            }

            // BPM: admite tempos dobles/mitad, habituales en análisis musical.
            val bpm = song.bpm?.takeIf { it > 0 }
            if (targetBpm != null && bpm != null) {
                val raw = kotlin.math.abs(bpm - targetBpm)
                val distance = minOf(raw, kotlin.math.abs(bpm * 2 - targetBpm), kotlin.math.abs(bpm - targetBpm * 2))
                val points = when {
                    distance <= 4 -> 14f
                    distance <= 8 -> 10f
                    distance <= 15 -> 6f
                    distance <= 24 -> 2f
                    else -> 0f
                }
                if (points > 0) {
                    sc += points * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_BPM_MATCH)
                    firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_BPM_MATCH
                }
            }

            // Compatibilidad básica de tonalidad: exacta o misma raíz musical.
            if (!targetKey.isNullOrBlank() && !song.key.isNullOrBlank()) {
                val targetRoot = targetKey.trim().lowercase().substringBefore(" ")
                val songRoot = song.key.trim().lowercase().substringBefore(" ")
                if (targetRoot == songRoot) {
                    sc += 9f * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_KEY_MATCH)
                    firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_KEY_MATCH
                }
            }

            // El estado de ánimo suma puntos, pero no elimina canciones si faltan etiquetas.
            if (requestedMood.isNotBlank()) {
                val matchesMood = song.moods.orEmpty().any { it.name.trim().lowercase() == requestedMood }
                if (matchesMood) {
                    sc += 18f * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_MOOD_MATCH)
                    firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_MOOD_MATCH
                } else if (!song.moods.isNullOrEmpty()) sc -= 4f
            } else {
                val moodPoints = song.moods.orEmpty().sumOf { (moodWeights[it.name.trim().lowercase()] ?: 0f).toDouble() }.toFloat()
                if (moodPoints > 0) {
                    sc += minOf(8f, moodPoints * 2f) * weight(com.example.localfly.ai.AIWeightsStore.FACTOR_MOOD_MATCH)
                    firedFactors += com.example.localfly.ai.AIWeightsStore.FACTOR_MOOD_MATCH
                }
            }

            if (song.id in playedIds) sc -= 7f
            if (song.hasCover) sc += 1f
            if (song.hasLyrics && !song.isEpisode) sc += 1f

            return sc.toInt() to firedFactors
        }

        val scoredWithFactors = candidates.map { song ->
            val (sc, factors) = scoreWithFactors(song)
            Triple(song, sc, factors)
        }.sortedWith(compareByDescending<Triple<Song, Int, List<String>>> { it.second }.thenBy { it.first.id })

        val scored = scoredWithFactors.map { it.first to it.second }
        val factorsBySongId = scoredWithFactors.associate { it.first.id to it.third }

        val diversified = mutableListOf<Song>()
        val artistCounts = mutableMapOf<String, Int>()
        // Ajustar límite por artista según el total pedido para no quedarnos cortos
        val maxPerArtist = if (limit > 50) (limit / 20).coerceAtLeast(5) else 2

        for ((song, _) in scored) {
            val key = song.artist ?: song.id
            val count = artistCounts[key] ?: 0
            if (count < maxPerArtist) { 
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
