package com.example.localfly.utils

import com.example.localfly.network.Song
import kotlin.math.abs

/**
 * Utilidad para reordenar listas de canciones basándose en BPM y clave musical (armonía).
 * Intenta imitar una sesión de DJ profesional con transiciones suaves.
 */
object SmartReorderUtils {

    /**
     * Reordena la lista de canciones para que las transiciones de BPM sean progresivas
     * y las claves musicales sean compatibles (Rueda de Camelot).
     */
    fun reorder(songs: List<Song>): List<Song> {
        if (songs.size <= 1) return songs

        val mutableSongs = songs.toMutableList()
        val result = mutableListOf<Song>()
        
        // Empezar con una canción aleatoria o la primera
        var current = mutableSongs.removeAt(0)
        result.add(current)

        while (mutableSongs.isNotEmpty()) {
            val next = findBestNext(current, mutableSongs)
            result.add(next)
            mutableSongs.remove(next)
            current = next
        }
        
        return result
    }

    private fun findBestNext(current: Song, candidates: List<Song>): Song {
        return candidates.minByOrNull { candidate ->
            calculatePenalty(current, candidate)
        } ?: candidates[0]
    }

    /**
     * Calcula la "penalización" entre dos canciones. A menor penalización, mejor transición.
     * Considera diferencia de BPM (peso 60%) y compatibilidad de Clave (peso 40%).
     */
    private fun calculatePenalty(a: Song, b: Song): Double {
        var penalty = 0.0

        // 1. Penalización por BPM (Diferencia absoluta)
        val bpmA = a.bpm ?: 120.0
        val bpmB = b.bpm ?: 120.0
        penalty += abs(bpmA - bpmB) * 2.0 // Cada 1 BPM de diferencia suma 2 puntos

        // 2. Penalización por Clave (Camelot Wheel)
        val keyA = a.key ?: "unknown"
        val keyB = b.key ?: "unknown"
        
        if (keyA != "unknown" && keyB != "unknown") {
            penalty += calculateKeyPenalty(keyA, keyB)
        } else {
            penalty += 15.0 // Penalización media si no hay datos
        }

        return penalty
    }

    private fun calculateKeyPenalty(keyA: String, keyB: String): Double {
        // Camelot keys format: "1A", "1B", "2A", etc.
        // Rule: same key = 0, +/- 1 hour = 5, change A/B = 5, mixed = 10+
        if (keyA == keyB) return 0.0

        val numA = keyA.filter { it.isDigit() }.toIntOrNull() ?: 0
        val modeA = keyA.filter { it.isLetter() }
        val numB = keyB.filter { it.isDigit() }.toIntOrNull() ?: 0
        val modeB = keyB.filter { it.isLetter() }

        val diffNum = abs(numA - numB).let { if (it > 6) 12 - it else it }

        return when {
            diffNum == 0 && modeA != modeB -> 3.0 // Cambio de modo (A <-> B)
            diffNum == 1 && modeA == modeB -> 5.0 // Movimiento lateral (1A -> 2A)
            diffNum == 1 && modeA != modeB -> 8.0 // Movimiento diagonal (1A -> 2B)
            else -> 20.0 // Salto brusco
        }
    }
}
