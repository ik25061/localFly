package com.example.localfly.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gestor del modo "Radio": un usuario (host) emite lo que está escuchando y
 * otros usuarios (oyentes) se sincronizan con él.
 *
 * - HOST: publica periódicamente (cada [PUBLISH_INTERVAL_MS]) qué canción
 *   suena, en qué posición y si está reproduciendo o en pausa.
 * - OYENTE: consulta periódicamente (cada [POLL_INTERVAL_MS]) el estado del
 *   host. Si el host cambió de canción, se carga aquí; si hay desfase, se
 *   corrige la posición; respeta play/pausa.
 *
 * El manager no toca el ExoPlayer directamente: expone callbacks que
 * [PlaybackService] conecta con su reproductor.
 */
object RadioManager {

    private const val TAG = "RadioManager"
    private const val PUBLISH_INTERVAL_MS = 5000L
    private const val POLL_INTERVAL_MS = 4000L

    /** true si este dispositivo es el que emite (host). */
    @Volatile
    var isHost: Boolean = false
        private set

    /** true si este dispositivo está escuchando la radio de otro usuario. */
    @Volatile
    var isListener: Boolean = false
        private set

    /** true si el host al que escuchamos tiene el micrófono activo. */
    @Volatile
    var isVoiceActive: Boolean = false
        private set

    /** Host al que estamos escuchando (null si no somos oyentes). */
    @Volatile
    var currentHostId: String? = null
        private set

    // --- Callbacks que conecta PlaybackService ---

    /** HOST: devuelve el estado actual de reproducción local para publicarlo. */
    var hostStateProvider: (() -> RadioPublishRequest?)? = null

    /** OYENTE: carga una canción concreta (del host) en el reproductor. */
    var onListenerSongChange: ((songId: String, title: String?, artist: String?, startMs: Long, play: Boolean) -> Unit)? = null

    /** OYENTE: sincronizar posición (seek), play/pausa y volumen (voz activa). */
    var onListenerSync: ((positionMs: Long, play: Boolean, voiceActive: Boolean) -> Unit)? = null

    /** Notifica cambios de modo (para actualizar la UI). */
    var onRadioStateChanged: ((hosting: Boolean, listening: Boolean) -> Unit)? = null

    /**
     * El manager corre en el hilo principal (los callbacks tocan el
     * ExoPlayer, que exige main thread); las llamadas Retrofit son suspend y
     * son main-safe.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var publishJob: Job? = null
    private var listenJob: Job? = null

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** HOST: empezar a emitir lo que escucho. */
    fun startHosting() {
        if (isHost) return
        stopListeningInternal()
        isHost = true
        publishJob = scope.launch {
            while (isActive && isHost) {
                try {
                    val state = hostStateProvider?.invoke()
                    if (state != null) {
                        RetrofitClient.api.publishRadioState(state)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "publishRadioState falló: ${e.message}")
                }
                delay(PUBLISH_INTERVAL_MS)
            }
        }
        onRadioStateChanged?.invoke(true, false)
    }

    /** HOST: dejar de emitir. */
    fun stopHosting() {
        if (!isHost) return
        isHost = false
        publishJob?.cancel()
        publishJob = null
        val session = SessionManager(appContext)
        scope.launch {
            try {
                // hostId = el propio usuario (deja de emitir su radio)
                RetrofitClient.api.leaveRadio(RadioJoinRequest(session.getUserId(), session.getUserId() ?: ""))
            } catch (_: Exception) {}
        }
        onRadioStateChanged?.invoke(false, false)
    }

    /** OYENTE: unirme a la radio de un host. */
    fun joinAsListener(hostId: String) {
        stopHosting()
        stopListeningInternal()
        isListener = true
        currentHostId = hostId
        listenJob = scope.launch {
            val session = SessionManager(appContext)
            try {
                RetrofitClient.api.joinRadio(RadioJoinRequest(session.getUserId(), hostId))
            } catch (_: Exception) {}

            var lastSongId: String? = null
            while (isActive && isListener && currentHostId == hostId) {
                try {
                    val status = RetrofitClient.api.getRadioStatus(hostId).body()
                    if (status == null || status.songId == null) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    // Compensar el tiempo transcurrido desde la publicación
                    val ageMs = (System.currentTimeMillis() - status.updatedAt).coerceAtLeast(0L)
                    val expectedPos = status.positionMs + if (status.isPlaying) ageMs else 0L

                    isVoiceActive = status.isVoiceActive

                    if (status.songId != lastSongId) {
                        lastSongId = status.songId
                        onListenerSongChange?.invoke(
                            status.songId, status.title, status.artist,
                            expectedPos, status.isPlaying
                        )
                    } else {
                        // Misma canción: corregir desfase, seguir play/pausa y volumen
                        onListenerSync?.invoke(expectedPos, status.isPlaying, isVoiceActive)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getRadioStatus falló: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        onRadioStateChanged?.invoke(false, true)
    }

    /** OYENTE: dejar de escuchar. */
    fun stopListening() {
        if (!isListener) return
        val hostId = currentHostId
        stopListeningInternal()
        if (hostId != null) {
            val session = SessionManager(appContext)
            scope.launch {
                try {
                    RetrofitClient.api.leaveRadio(RadioJoinRequest(session.getUserId(), hostId))
                } catch (_: Exception) {}
            }
        }
        onRadioStateChanged?.invoke(false, false)
    }

    /** HOST: alternar el estado del micrófono. */
    fun setVoiceActive(active: Boolean) {
        if (!isHost) return
        isVoiceActive = active
        onRadioStateChanged?.invoke(true, false)
    }

    private fun stopListeningInternal() {
        isListener = false
        currentHostId = null
        listenJob?.cancel()
        listenJob = null
    }
}

