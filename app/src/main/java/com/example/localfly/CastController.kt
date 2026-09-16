package com.example.localfly

import android.content.Context
import android.net.Uri
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

/**
 * Controlador de Chromecast para la pantalla de reproduccion.
 *
 * Emite la cancion actual hacia el receptor mediante [RemoteMediaClient],
 * mantiene sincronizado el estado de reproduccion (play/pausa) de forma minima,
 * pausa el reproductor local mientras hay una sesion activa (para no duplicar
 * el audio) y lo reanuda al desconectar.
 */
class CastController(context: Context) : SessionManagerListener<CastSession> {

    /** Datos minimos de la cancion a transmitir (URL ya resuelta). */
    data class CastSong(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val artUrl: String?,
        val streamUrl: String,
        val startMs: Long,
        val durationMs: Long = 0L,
        val contentType: String = "audio/mpeg"
    )

    /** Provee la cancion actual (null si no hay nada que emitir). */
    var songProvider: (() -> CastSong?)? = null

    /** Provee si el reproductor local esta sonando. */
    var playingProvider: (() -> Boolean)? = null

    /** Notifica si hay una sesion de cast activa (para resaltar el boton). */
    var onCastingChanged: ((Boolean) -> Unit)? = null

    /** Se invoca al iniciarse una sesion (para pausar el audio local). */
    var onCastStarted: (() -> Unit)? = null

    /** Se invoca al reanudarse una sesion existente (evitar audio duplicado). */
    var onCastResumed: (() -> Unit)? = null

    /** Se invoca al finalizar la sesion (para reanudar el audio local). */
    var onCastEnded: (() -> Unit)? = null

    private val appContext: Context = context.applicationContext
    private val castContext: CastContext = CastContext.getSharedInstance(appContext)
    private val sessionManager = castContext.sessionManager

    /** Ultima URL emitida, para evitar reemitir la misma cancion repetidamente. */
    private var lastSentUrl: String? = null

    /** Estado deseado de reproduccion en el receptor (fuente de verdad del cast). */
    private var remoteShouldPlay = false

    val isCasting: Boolean
        get() = sessionManager.currentCastSession != null

    /** true si el receptor esta reproduciendo (o cargando) contenido. */
    val isRemotePlaying: Boolean
        get() = remoteClient()?.let { remoteIsPlaying(it) } ?: false

    /** Posicion aproximada de reproduccion en el receptor (ms). */
    val remotePositionMs: Long
        get() = runCatching { remoteClient()?.approximateStreamPosition ?: 0L }.getOrDefault(0L)

    /** Duracion del contenido cargado en el receptor (ms). */
    val remoteDurationMs: Long
        get() = runCatching { remoteClient()?.mediaStatus?.mediaInfo?.streamDuration ?: 0L }
            .getOrDefault(0L)

    init {
        sessionManager.addSessionManagerListener(this, CastSession::class.java)
    }

    /** Prepara el boton de media route (icono cast) en la UI. */
    fun setUpMediaRouteButton(button: MediaRouteButton) {
        CastButtonFactory.setUpMediaRouteButton(appContext, button)
    }

    /**
     * Refleja en la UI una sesion que ya estaba activa al abrir la pantalla
     * (el listener no dispara eventos en ese caso).
     */
    fun refreshSessionState() {
        onCastingChanged?.invoke(isCasting)
    }

    private fun remoteClient(): RemoteMediaClient? =
        sessionManager.currentCastSession?.remoteMediaClient

    /** true si el receptor esta reproduciendo (o cargando) contenido. */
    private fun remoteIsPlaying(remote: RemoteMediaClient): Boolean {
        val status = remote.mediaStatus ?: return false
        return status.playerState == MediaStatus.PLAYER_STATE_PLAYING ||
            status.playerState == MediaStatus.PLAYER_STATE_BUFFERING
    }

    /** true si el receptor tiene contenido cargado del que conocemos la URL. */
    private fun hasSentMedia(remote: RemoteMediaClient): Boolean =
        lastSentUrl != null && remote.mediaStatus?.mediaInfo != null

    /**
     * Se llama en cada refresh de la UI: si la cancion cambio mientras hay una
     * sesion activa, la emite al Chromecast.
     */
    fun autoCast() {
        val song = songProvider?.invoke() ?: return
        val remote = remoteClient() ?: return
        if (lastSentUrl == song.streamUrl && hasSentMedia(remote)) return
        val localPlaying = playingProvider?.invoke() ?: false
        sendSong(remote, song, localPlaying)
    }

    /** Emite la cancion indicada al receptor activo. */
    private fun sendSong(remote: RemoteMediaClient, song: CastSong, localPlaying: Boolean) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, song.title)
            putString(MediaMetadata.KEY_ARTIST, song.artist)
            song.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
            song.artUrl?.let { runCatching { addImage(WebImage(Uri.parse(it))) } }
        }

        val builder = MediaInfo.Builder(song.streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(song.contentType)
            .setMetadata(metadata)
        if (song.durationMs > 0) builder.setStreamDuration(song.durationMs)

        val options = MediaLoadOptions.Builder()
            .setAutoplay(localPlaying)
            .setPlayPosition(song.startMs.coerceAtLeast(0L))
            .build()

        lastSentUrl = song.streamUrl
        remoteShouldPlay = localPlaying
        remote.load(builder.build(), options)
    }

    /** Fuerza la reemision de la cancion actual (p. ej. al iniciar sesion de cast). */
    fun castCurrentSong(localPlaying: Boolean) {
        val song = songProvider?.invoke() ?: return
        val remote = remoteClient() ?: return
        sendSong(remote, song, localPlaying)
    }

    /** Sincroniza play/pausa con el reproductor local cuando hay sesion activa. */
    fun syncPlayPause(localPlaying: Boolean) {
        val remote = remoteClient() ?: return
        if (!hasSentMedia(remote)) return
        remoteShouldPlay = localPlaying
        if (remoteIsPlaying(remote) == localPlaying) return
        applyPlayPause(remote, localPlaying)
    }

    /** Alterna play/pausa en el receptor cuando hay una sesion activa. */
    fun toggleRemotePlayback() {
        val remote = remoteClient() ?: return
        if (!hasSentMedia(remote)) {
            // Aun no se ha emitido nada: emitir la cancion actual en reproduccion
            castCurrentSong(!remoteShouldPlay)
            return
        }
        remoteShouldPlay = !remoteShouldPlay
        applyPlayPause(remote, remoteShouldPlay)
    }

    private fun applyPlayPause(remote: RemoteMediaClient, playing: Boolean) {
        runCatching { if (playing) remote.play() else remote.pause() }
    }

    /** Sincroniza la posicion de reproduccion con el receptor. */
    fun seekTo(positionMs: Long) {
        val remote = remoteClient() ?: return
        if (!hasSentMedia(remote)) return
        runCatching {
            remote.seek(
                MediaSeekOptions.Builder()
                    .setPosition(positionMs.coerceAtLeast(0L))
                    .build()
            )
        }
    }

    /** Detiene la reproduccion en el Chromecast (la sesion sigue activa). */
    fun stopCasting() {
        val remote = remoteClient() ?: return
        lastSentUrl = null
        runCatching { remote.stop() }
    }

    fun release() {
        runCatching { sessionManager.removeSessionManagerListener(this, CastSession::class.java) }
    }
// ---------- Sesion ----------

    override fun onSessionStarted(session: CastSession, sessionId: String) {
        onCastingChanged?.invoke(true)
        // La actividad pausa el audio local y emite la cancion actual al receptor.
        onCastStarted?.invoke()
    }

    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
        onCastingChanged?.invoke(true)
        // El receptor ya tiene su propio contenido: solo evitamos el audio duplicado.
        onCastResumed?.invoke()
    }

    override fun onSessionEnded(session: CastSession, error: Int) {
        lastSentUrl = null
        onCastingChanged?.invoke(false)
        onCastEnded?.invoke()
    }

    override fun onSessionStartFailed(session: CastSession, sessionError: Int) {
        onCastingChanged?.invoke(false)
    }

    override fun onSessionResumeFailed(session: CastSession, sessionError: Int) {
        onCastingChanged?.invoke(false)
    }
    override fun onSessionStarting(session: CastSession) {}
    override fun onSessionResuming(session: CastSession, sessionId: String) {}
    override fun onSessionSuspended(session: CastSession, reason: Int) {}
    override fun onSessionEnding(session: CastSession) {}
}
