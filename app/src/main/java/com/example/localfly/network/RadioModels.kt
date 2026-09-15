package com.example.localfly.network

import com.google.gson.annotations.SerializedName

/**
 * Modelos y peticiones del modo "Radio": un usuario (host) emite lo que está
 * escuchando y otros usuarios (oyentes) se conectan para escuchar lo mismo,
 * sincronizados con la posición de reproducción del host.
 */

/** Estado publicado por el host: qué suena y por dónde va. */
data class RadioPublishRequest(
    val hostId: String?,
    val hostName: String?,
    val songId: String,
    val title: String,
    val artist: String?,
    val positionMs: Long,
    val isPlaying: Boolean
)

/** Una radio activa que aparece en la lista para poder unirse. */
data class RadioStation(
    val hostId: String,
    val hostName: String?,
    val songId: String,
    val title: String,
    val artist: String?,
    val listeners: Int = 0
)

data class RadioStationsResponse(
    val stations: List<RadioStation> = emptyList()
)

/** Estado actual de una radio concreta (lo que consulta cada oyente). */
data class RadioStatusResponse(
    val hostId: String?,
    val songId: String?,
    val title: String?,
    val artist: String?,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val listeners: Int = 0,
    /** epoch (ms) del servidor en que se tomó la posición, para compensar el retardo. */
    val updatedAt: Long = 0L
)

data class RadioJoinRequest(
    val userId: String?,
    val hostId: String
)
