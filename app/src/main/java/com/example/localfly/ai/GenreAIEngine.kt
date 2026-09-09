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
        val allSongs = if (libResp?.isSuccessful == true) libResp!!.body()?.songs ?: emptyList() else emptyList()
        if (allSongs.isEmpty()) return@withContext emptyList()

        val seedIds = genre.songIds.toSet()
        val seeds = allSongs.filter { it.id in seedIds }
        val seedArtists = seeds.mapNotNull { it.artist }.toSet()
        val seedAlbums = seeds.mapNotNull { it.album }.toSet()
        val seedYears = seeds.mapNotNull { it.year }.toSet()
        val seedKeys = seeds.mapNotNull { it.key }.toSet()
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
            if (seedArtists.isNotEmpty() && song.artist != null && song.artist in seedArtists) {
                score += SCORE_ARTIST
                reasons.add("Mismo artista: ${song.artist}")
            }
            if (seedAlbums.isNotEmpty() && song.album != null && song.album in seedAlbums) {
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
            if (seedBpmAvg != null && bpm != null && Math.abs(bpm - seedBpmAvg) <= 8.0) {
                score += SCORE_BPM
                reasons.add("BPM similar (${"%.0f".format(bpm)})")
            }
            if (seedKeys.isNotEmpty() && song.key != null && song.key in seedKeys) {
                score += SCORE_KEY
                reasons.add("Misma tonalidad: ${song.key}")
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

        results.sortedByDescending { it.score }.take(limit)
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