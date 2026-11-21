package com.example.localfly.utils

/**
 * Filtra una cola offline sin alterar el orden elegido por el usuario.
 * La disponibilidad la decide quien llama (archivo válido, no solo registro).
 */
object OfflineQueuePolicy {
    fun <T> filterAvailableInOriginalOrder(
        items: List<T>,
        isAvailable: (T) -> Boolean
    ): List<T> = items.filter(isAvailable)
}
