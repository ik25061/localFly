package com.example.localfly.utils

/**
 * Arma la "cola de revisión" de canciones marcadas como "No me gusta".
 *
 * Regla pedida por el usuario: cuando está sonando una canción marcada, la
 * lista de reproducción ("a continuación") debe contener SOLO las demás
 * canciones marcadas pendientes de revisión, para poder eliminarlas o dejarlas
 * una detrás de otra.
 *
 * Es lógica pura (sin Android) para poder probarla con tests unitarios: solo
 * recibe la lista de pendientes, sus ids y qué está disponible para sonar.
 */
object DislikedReviewQueue {

    /**
     * Devuelve el orden en el que deben sonar las canciones marcadas.
     *
     * - Nunca incluye [currentId] (es la que ya está sonando).
     * - Solo incluye lo que [isAvailable] acepte (por ejemplo, sin conexión:
     *   únicamente lo descargado al teléfono).
     * - Prioriza las que aún no han sonado en la sesión ([alreadyPlayed]); si
     *   ya sonaron todas, se permite repetir el ciclo para poder seguir
     *   revisando.
     * - Si no queda ninguna otra canción marcada, devuelve una lista vacía: en
     *   ese caso NO se debe cambiar la cola (no tiene sentido dejar la sesión
     *   sin siguiente canción).
     */
    fun <T> order(
        pending: List<T>,
        idOf: (T) -> String,
        currentId: String?,
        isAvailable: (T) -> Boolean = { true },
        alreadyPlayed: Set<String> = emptySet()
    ): List<T> {
        val candidates = pending.filter { idOf(it) != currentId && isAvailable(it) }
        if (candidates.isEmpty()) return emptyList()
        val fresh = candidates.filter { idOf(it) !in alreadyPlayed }
        return if (fresh.isNotEmpty()) fresh else candidates
    }
}
