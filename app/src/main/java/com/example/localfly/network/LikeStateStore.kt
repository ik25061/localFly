package com.example.localfly.network

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Estado global de "me gusta" de cada canción.
 *
 * Problema que resuelve: cuando el usuario marca "me gusta" desde el mini
 * reproductor (o desde la pantalla completa), el servicio solo actualiza sus
 * propios objetos `Song` (la cola). Las listas de la interfaz (artista,
 * playlist, biblioteca...) mantienen OTRAS instancias de `Song` cargadas al
 * abrirlas, así que su corazón seguía mostrando el valor antiguo.
 *
 * Aquí se guarda el último estado conocido por id y se notifica a todos los
 * adaptadores suscritos para que repinten.
 */
object LikeStateStore {

    /** Último estado conocido por id de canción (vacío = "usar el de la Song"). */
    private val states = HashMap<String, Boolean>()

    /** Listos notificados cuando cambia el "me gusta" de una canción. */
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Estado de "me gusta" de [song]: usa el último cambio registrado aquí y,
     * si no existe, el que traiga la propia canción (cargado del servidor).
     */
    fun isLiked(song: Song): Boolean = states[song.id] ?: song.liked

    /** Registra un cambio de "me gusta" y avisa a todas las listas visibles. */
    fun set(songId: String, liked: Boolean) {
        if (songId.isBlank()) return
        val known = states[songId]
        if (known == liked) return
        states[songId] = liked
        for (listener in listeners) {
            try { listener(songId) } catch (_: Exception) { }
        }
    }

    fun addListener(listener: (String) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}