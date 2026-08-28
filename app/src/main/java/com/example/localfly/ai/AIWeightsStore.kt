package com.example.localfly.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Pesos ajustables del motor de recomendaciones + aprendizaje en línea
 * ligero: cada vez que dás una señal real (like, dislike, quitar de una
 * lista de IA), los pesos de las reglas que "acertaron" o "fallaron" en
 * esa sugerencia se ajustan al instante — sin lotes, sin reentrenamiento,
 * eso es justo lo que hace "en línea" a este aprendizaje.
 */
class AIWeightsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ai_weights", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val FACTOR_FAV_ARTIST = "fav_artist"
        const val FACTOR_LIKED_ARTIST = "liked_artist"
        const val FACTOR_DECADE_MATCH = "decade_match"
        const val FACTOR_SEED_ARTIST = "seed_artist"
        const val FACTOR_SEED_DECADE = "seed_decade"
        const val FACTOR_HAS_COVER = "has_cover"

        val ALL_FACTORS = listOf(
            FACTOR_FAV_ARTIST, FACTOR_LIKED_ARTIST, FACTOR_DECADE_MATCH,
            FACTOR_SEED_ARTIST, FACTOR_SEED_DECADE, FACTOR_HAS_COVER
        )

        private const val MIN_WEIGHT = 0.2f
        private const val MAX_WEIGHT = 2.5f
        private const val LEARNING_RATE = 0.06f
        private const val MAX_TRACKED_ATTRIBUTIONS = 300
    }

    // --- Ajustes generales ---

    fun isOnlineLearningEnabled(): Boolean = prefs.getBoolean("online_learning_enabled", true)

    fun setOnlineLearningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("online_learning_enabled", enabled).apply()
    }

    fun getPlaylistSongCount(): Int = prefs.getInt("ai_playlist_song_count", 20)

    fun setPlaylistSongCount(count: Int) {
        prefs.edit().putInt("ai_playlist_song_count", count).apply()
    }

    // --- Pesos por regla (multiplicador, 1.0 = comportamiento original) ---

    fun getWeights(): Map<String, Float> {
        val json = prefs.getString("weights", null)
        val stored: Map<String, Float> = if (json != null) {
            try {
                val type = object : TypeToken<Map<String, Float>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        } else emptyMap()

        return ALL_FACTORS.associateWith { stored[it] ?: 1.0f }
    }

    fun getWeight(factor: String): Float = getWeights()[factor] ?: 1.0f

    fun setWeight(factor: String, value: Float) {
        val weights = getWeights().toMutableMap()
        weights[factor] = value.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        saveWeights(weights)
    }

    fun resetWeights() {
        prefs.edit().remove("weights").remove("attributions").apply()
    }

    private fun saveWeights(weights: Map<String, Float>) {
        prefs.edit().putString("weights", gson.toJson(weights)).apply()
    }

    // --- Atribución: qué reglas influyeron en cada canción sugerida ---

    /** Llamar cuando el motor sugiere [songId] gracias a [factors]. */
    fun recordAttribution(songId: String, factors: List<String>) {
        if (factors.isEmpty()) return
        val attributions = getAttributions().toMutableMap()
        attributions[songId] = factors
        // Evitar que esto crezca sin límite: nos quedamos con las últimas
        // MAX_TRACKED_ATTRIBUTIONS entradas.
        val trimmed = if (attributions.size > MAX_TRACKED_ATTRIBUTIONS) {
            attributions.entries.toList().takeLast(MAX_TRACKED_ATTRIBUTIONS).associate { it.key to it.value }
        } else attributions
        prefs.edit().putString("attributions", gson.toJson(trimmed)).apply()
    }

    private fun getAttributions(): Map<String, List<String>> {
        val json = prefs.getString("attributions", null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Feedback real sobre [songId]: [reward] positivo (+1, ej. "me gusta")
     * o negativo (-1, ej. "no me gusta" / quitar de una lista de IA). Si la
     * canción no fue sugerida por la IA (no hay atribución registrada), no
     * hace nada. Si el aprendizaje en línea está desactivado, tampoco.
     */
    fun reinforce(songId: String, reward: Float) {
        if (!isOnlineLearningEnabled()) return
        val factors = getAttributions()[songId] ?: return

        val weights = getWeights().toMutableMap()
        for (factor in factors) {
            val current = weights[factor] ?: 1.0f
            val updated = (current + LEARNING_RATE * reward).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
            weights[factor] = updated
        }
        saveWeights(weights)
    }
}
