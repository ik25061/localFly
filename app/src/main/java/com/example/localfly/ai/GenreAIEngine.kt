package com.example.localfly.ai

import com.example.localfly.network.CustomGenre
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Sugerencia de la IA local: canción + puntuación + motivos legibles. */
data class GenreSuggestion(
    val song: Song,
    val score: Int = 0,
    val reasons: List<String> = emptyList()
)

/**
 * Motor de IA LOCAL (en el dispositivo, sin API KEY ni nube) para evaluar qué
 * canciones de la biblioteca pertenecen a un género creado por el administrador.
 *
 * Con canciones semilla puntúa por similitud (artista, álbum, año/década, BPM,
 * tonalidad). Sin semillas usa palabras clave del nombre/descripción del género
 * contra título/artista/álbum. Mismo enfoque local que AIRecommendationManager.
 */
object GenreAIEngine {

    private const val SCORE_ARTIST = 40
    private const val SCORE_ALBUM = 25
    private const val SCORE_YEAR_EXACT = 12
    private const val SCORE_DECADE_CLOSE = 10
    private const val SCORE_BPM = 10
    private const val SCORE_KEY = 5
    private const val SCORE_KEYWORD = 8
    private const val SCORE_COVER = 3
    private const val SCORE_MOOD = 7

    /**
     * Analiza toda la biblioteca (vía API) y devuelve las [limit] canciones que
     * más probablemente pertenezcan al género [genre], ordenadas de mejor a peor.
     */
    suspend fun suggest(
        session: SessionManager,
        genre: CustomGenre,
        limit: Int = 15
    ): List<GenreSuggestion> = withContext(Dispatchers.IO) {
        val userId = session.getUserId()
        val libResp = try {
            RetrofitClient.api.getLibrary(userId, limit = 5000)
        } catch (e: Exception) {
            null
        }
        val dislikedIds = com.example.localfly.network.SongAdminStore.getDislikedSongs().mapNotNull { it.songId }.toSet()
        val allSongs = (if (libResp?.isSuccessful == true) libResp!!.body()?.songs ?: emptyList() else emptyList())
            .filter { it.id !in dislikedIds }
        if (allSongs.isEmpty()) return@withContext emptyList()

        val seedIds = genre.songIds.toSet()
        val seeds = allSongs.filter { it.id in seedIds }
        val seedArtists = seeds.mapNotNull { it.artist }.map(::normalizeText).toSet()
        val seedAlbums = seeds.mapNotNull { it.album }.map(::normalizeText).toSet()
        val seedYears = seeds.mapNotNull { it.year }.toSet()
        val seedKeys = seeds.mapNotNull { it.key }.map(::keyRoot).toSet()
        val seedMoods = seeds.flatMap { it.moods.orEmpty() }.map { normalizeText(it.name) }.toSet()
        val bpmValues = seeds.mapNotNull { it.bpm }
        val seedBpmAvg: Double? = if (bpmValues.isEmpty()) null else bpmValues.sumOf { it } / bpmValues.size.toDouble()
        val genreTokens = tokenize("${genre.name} ${genre.description ?: ""}")

        val results = mutableListOf<GenreSuggestion>()

        for (song in allSongs) {
            if (song.id in seedIds) continue

            val textTokens = tokenize("${song.title ?: ""} ${song.artist ?: ""} ${song.album ?: ""}")
            val matchedKeywords = genreTokens.filter { it in textTokens }

            var score = 0
            val reasons = mutableListOf<String>()

            // 1) Similitud con las canciones que ya pertenecen al género.
            if (seedArtists.isNotEmpty() && song.artist != null && normalizeText(song.artist) in seedArtists) {
                score += SCORE_ARTIST
                reasons.add("Mismo artista: ${song.artist}")
            }
            if (seedAlbums.isNotEmpty() && song.album != null && normalizeText(song.album) in seedAlbums) {
                score += SCORE_ALBUM
                reasons.add("Mismo álbum: ${song.album}")
            }
            val songYear = song.year
            if (seedYears.isNotEmpty() && songYear != null) {
                if (songYear in seedYears) {
                    score += SCORE_YEAR_EXACT
                    reasons.add("Mismo año: $songYear")
                } else if (seedYears.any { Math.abs(it - songYear) < 10 }) {
                    score += SCORE_DECADE_CLOSE
                    reasons.add("Año cercano: $songYear")
                }
            }
            val bpm = song.bpm
            if (seedBpmAvg != null && bpm != null) {
                val raw = Math.abs(bpm - seedBpmAvg)
                val distance = minOf(raw, Math.abs(bpm * 2 - seedBpmAvg), Math.abs(bpm - seedBpmAvg * 2))
                if (distance <= 8.0) {
                    score += SCORE_BPM
                    reasons.add("BPM similar (${bpm.toInt()})")
                }
            }
            if (seedKeys.isNotEmpty() && song.key != null) {
                val candidateRoot = keyRoot(song.key)
                val compatible = seedKeys.any { seedRoot ->
                    val distance = keyDistance(seedRoot, candidateRoot)
                    distance == 0 || distance == 5 || distance == 7
                }
                if (compatible) {
                    score += SCORE_KEY
                    reasons.add("Tonalidad compatible: ${song.key}")
                }
            }
            val songMoods = song.moods.orEmpty().map { normalizeText(it.name) }.toSet()
            if (seedMoods.isNotEmpty() && songMoods.any { it in seedMoods }) {
                score += SCORE_MOOD
                reasons.add("Estado de ánimo similar")
            }

            // 2) Coincidencia de palabras clave del nombre del género.
            if (matchedKeywords.isNotEmpty()) {
                score += matchedKeywords.size * SCORE_KEYWORD
                reasons.add("Coincide: ${matchedKeywords.take(3).joinToString(", ")}")
            }

            // Sin semillas: solo proponer canciones con coincidencia clara de texto.
            if (seeds.isEmpty() && matchedKeywords.isEmpty()) continue

            if (song.hasCover) score += SCORE_COVER
            if (score > 0) {
                results.add(GenreSuggestion(song, score, reasons.distinct().take(3)))
            }
        }

        val ranked = results.sortedWith(compareByDescending<GenreSuggestion> { it.score }.thenBy { it.song.id })
        val diversified = mutableListOf<GenreSuggestion>()
        val artistCounts = mutableMapOf<String, Int>()
        for (item in ranked) {
            val artist = normalizeText(item.song.artist).ifBlank { item.song.id }
            if ((artistCounts[artist] ?: 0) >= 2) continue
            diversified += item
            artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
            if (diversified.size >= limit) break
        }
        if (diversified.size < limit) {
            val used = diversified.map { it.song.id }.toSet()
            diversified.addAll(ranked.filter { it.song.id !in used }.take(limit - diversified.size))
        }
        diversified.take(limit)
    }

    private fun normalizeText(value: String?): String =
        Normalizer.normalize(value.orEmpty().lowercase(), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .trim()

    private fun keyRoot(value: String): String =
        normalizeText(value).replace(" ", "").takeWhile { it.isLetter() || it == '#' || it == 'b' }

    private fun keyDistance(a: String, b: String): Int? {
        val notes = listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b")
        val enharmonic = mapOf("db" to "c#", "eb" to "d#", "gb" to "f#", "ab" to "g#", "bb" to "a#")
        val x = notes.indexOf(enharmonic[a] ?: a)
        val y = notes.indexOf(enharmonic[b] ?: b)
        if (x < 0 || y < 0) return null
        val distance = kotlin.math.abs(x - y)
        return minOf(distance, 12 - distance)
    }

    /** Normaliza texto: minúsculas, sin acentos y solo caracteres alfanuméricos. */
    private fun tokenize(text: String): Set<String> {
        val clean = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9\\s]".toRegex(), " ")
        return clean.split("\\s+".toRegex())
            .map { it.trim() }
            .filter { it.length >= 3 && it !in FILLER_WORDS }
            .toSet()
    }

    private val FILLER_WORDS = setOf(
        "para", "por", "con", "los", "las", "que", "del", "the", "and", "música",
        "musica", "cancion", "canción", "canciones", "songs", "song", "genre", "genero"
    )
}