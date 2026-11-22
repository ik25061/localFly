package com.example.localfly

import android.content.Context
import android.net.Uri
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/**
 * Controlador de Chromecast para la pantalla de reproduccion.
 *
 * Emite la cancion actual hacia el receptor mediante [RemoteMediaClient],
 * mantiene sincronizado el estado de reproduccion (play/pausa) de forma minima,
 * pausa el reproductor local mientras hay una sesion activa (para no duplicar
 * el audio) y lo reanuda al desconectar.
 */
class CastController(context: Context) : SessionManagerListener<CastSession> {

    data class LyricsTrack(
        val id: Long,
        val url: String,
        val mimeType: String = "text/vtt",
        val language: String = "es",
        val label: String = "Letra"
    )

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
        val contentType: String = "audio/mpeg",
        /**
         * URL WebVTT con la letra. Si viene informada, el receptor muestra la
         * letra como subtítulos (karaoke) y [streamUrl] es un audio SILENCIOSO:
         * la música real no sale del teléfono.
         */
        val lyricsUrl: String? = null
    ) {
        /** Pista de letra lista para enviar al receptor, o null si no hay letra. */
        fun lyricsTrack(): LyricsTrack? =
            lyricsUrl?.takeIf { it.isNotBlank() }?.let { LyricsTrack(LYRICS_TRACK_ID, it) }
    }

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

    /** Se llama al terminar una pista remota; el sender avanza su cola local. */
    var onCastTrackFinished: (() -> Unit)? = null

    /**
     * Se invoca cuando el receptor NO acepta la carga o pierde el medio (el TV
     * se queda en su pantalla de reposo mostrando solo el icono de Cast). Antes
     * de esto la app no mostraba nada: el fallo era invisible.
     */
    var onCastError: ((String) -> Unit)? = null

    /** Último error avisado, para no repetirlo en cada refresco de estado. */
    private var lastErrorReported: String? = null

    /** Cliente del receptor cuya callback de estado ya está registrada. */
    private var callbackRemote: RemoteMediaClient? = null

    /** Avisa de los errores de medio que reporta el receptor. */
    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onMediaError(mediaError: MediaError) {
            lastSentUrl = null
            lastSentLyricsUrl = null
            pendingLoadKey = null
            reportCastError(explainCastError(mediaError), mediaErrorDetail(mediaError))
        }

        override fun onStatusUpdated() {
            val status = remoteClient()?.mediaStatus ?: return
            when (status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING,
                MediaStatus.PLAYER_STATE_BUFFERING -> remoteShouldPlay = true
                MediaStatus.PLAYER_STATE_PAUSED -> remoteShouldPlay = false
            }
            if (status.playerState != MediaStatus.PLAYER_STATE_IDLE) return
            when (status.idleReason) {
                MediaStatus.IDLE_REASON_ERROR -> {
                    // Medio perdido en el receptor (fallo de red/decodificación).
                    lastSentUrl = null
                    lastSentLyricsUrl = null
                    pendingLoadKey = null
                    remoteShouldPlay = false
                    reportCastError("El televisor perdió el audio", "idle=error")
                }
                MediaStatus.IDLE_REASON_FINISHED -> {
                    // En salida TV el player del teléfono está pausado. Avanzar
                    // desde la Activity para no dejar el Chromecast en reposo.
                    val finishedId = status.mediaInfo?.contentId
                    if (pendingLoadKey == null && finishedId != null &&
                        finishedId != lastFinishedContentId) {
                        lastFinishedContentId = finishedId
                        lastSentUrl = null
                        lastSentLyricsUrl = null
                        onCastTrackFinished?.invoke()
                    }
                }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val castContext: CastContext = CastContext.getSharedInstance(appContext)
    private val sessionManager = castContext.sessionManager

    /** Ultima URL emitida, para evitar reemitir la misma cancion repetidamente. */
    private var lastSentUrl: String? = null
    /** La letra forma parte de la identidad del medio: puede llegar después del audio. */
    private var lastSentLyricsUrl: String? = null
    /** Evita lanzar varias cargas iguales mientras el receptor aún responde. */
    private var pendingLoadKey: String? = null
    /** Último medio finalizado ya procesado (evita avanzar varias canciones). */
    private var lastFinishedContentId: String? = null

    /** Estado deseado de reproduccion en el receptor (fuente de verdad del cast). */
    private var remoteShouldPlay = false

    companion object {
        /** Id de la pista de subtítulos (letra) añadida al cargar en el receptor. */
        private const val LYRICS_TRACK_ID = 1L
        private const val KARAOKE_NAMESPACE = "urn:x-cast:com.example.localfly.karaoke"

        /**
         * Escala de fuente de los subtitulos (1.0 = tamaño normal). A 2.2 la
         * letra se lee desde el sofa; el receptor por defecto usa 1.0.
         */
        private const val LYRICS_FONT_SCALE = 2.2f

        /** Fondo negro (con algo de alpha) para que resalte sobre la portada. */
        private const val LYRICS_BACKGROUND_COLOR = 0xCC000000.toInt()

        /** Contorno negro del texto (EDGE_TYPE_OUTLINE). */
        private const val LYRICS_EDGE_COLOR = 0xFF000000.toInt()

        /** Texto blanco puro. */
        private const val LYRICS_FOREGROUND_COLOR = 0xFFFFFFFF.toInt()
    }

    val isCasting: Boolean
        get() = sessionManager.currentCastSession != null

    /** true si el receptor esta reproduciendo (o cargando) contenido. */
    val isRemotePlaying: Boolean
        get() = remoteClient()?.let { remoteIsPlaying(it) } ?: false

    /** Estado que debe heredar la siguiente canción de la cola remota. */
    val shouldContinuePlayback: Boolean
        get() = remoteShouldPlay

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

    private fun loadKey(song: CastSong): String = song.streamUrl + "\n" + (song.lyricsUrl ?: "")

    /** Adopta una pista ya activa al recrearse la Activity, sin reiniciar el TV. */
    /** Adopta una pista activa sin reiniciarla; si terminó, permite cargar la siguiente. */
    private fun adoptRemoteMediaIfMatching(remote: RemoteMediaClient, song: CastSong): Boolean {
        val status = remote.mediaStatus
        val info = status?.mediaInfo ?: return false
        if (info.contentId != song.streamUrl) return false
        val hasExpectedLyrics = song.lyricsUrl.isNullOrBlank() ||
            info.mediaTracks?.any { it.contentId == song.lyricsUrl } == true
        if (!hasExpectedLyrics) return false

        attachRemoteCallback(remote)
        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
            // El medio remoto ya terminó. No adoptarlo como activo: el callback
            // avanza la cola y autoCast cargará el nuevo medio (o repetirá el
            // actual si el usuario eligió Repetir una).
            remoteShouldPlay = true
            if (info.contentId != lastFinishedContentId) {
                lastFinishedContentId = info.contentId
                lastSentUrl = null
                lastSentLyricsUrl = null
                pendingLoadKey = null
                onCastTrackFinished?.invoke()
            }
            return false
        }

        lastSentUrl = song.streamUrl
        lastSentLyricsUrl = song.lyricsUrl
        pendingLoadKey = null
        remoteShouldPlay = remoteIsPlaying(remote)
        return true
    }

    /** true si el receptor tiene contenido cargado del que conocemos la URL. */
    private fun hasSentMedia(remote: RemoteMediaClient): Boolean =
        lastSentUrl != null && remote.mediaStatus?.mediaInfo != null

    /**
     * Se consulta desde el bucle de sincronización Cast: si cambió la canción
     * o apareció su letra, emite la versión completa al Chromecast.
     */
    fun autoCast() {
        var song = songProvider?.invoke() ?: return
        val remote = remoteClient() ?: return
        if (pendingLoadKey == loadKey(song)) return
        // Si la Activity se recreó, adoptar el medio que ya está sonando en el
        // TV en vez de reiniciarlo. Si el medio terminó, el callback puede haber
        // avanzado la cola: volver a leer currentSong antes de emitir.
        if (adoptRemoteMediaIfMatching(remote, song)) return
        song = songProvider?.invoke() ?: return
        if (pendingLoadKey == loadKey(song)) return
        if (lastSentUrl == song.streamUrl &&
            lastSentLyricsUrl == song.lyricsUrl &&
            hasSentMedia(remote)) return
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

        // Karaoke en el TV: pista de subtítulos WebVTT como fallback del
        // receptor estándar. Con el receptor LocalFly, la misma URL se dibuja
        // en una capa karaoke sincronizada con el reloj del reproductor. El
        // stream puede ser silencio (audio en teléfono/BT) o audio real (TV).
        val withLyrics = !song.lyricsUrl.isNullOrBlank()
        if (withLyrics) {
            builder.setMediaTracks(
                listOf(
                    MediaTrack.Builder(LYRICS_TRACK_ID, MediaTrack.TYPE_TEXT)
                        .setContentType("text/vtt")
                        .setContentId(song.lyricsUrl!!)
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setName("Letra")
                        .setLanguage("es")
                        .build()
                )
            )
            // El estilo viaja DENTRO del MediaInfo: aplicarlo despues del load
            // llega tarde y el receptor ya habria pintado las primeras lineas
            // con su tamano por defecto (diminuto en un TV).
            builder.setTextTrackStyle(lyricsStyle())
        }

        // El receptor rechaza la carga si la posición pedida cae fuera del
        // medio (error INVALID_PARAMS y el TV se queda sin nada), así que se
        // recorta contra la duración conocida.
        val startMs = song.startMs.coerceAtLeast(0L).let { start ->
            if (song.durationMs > 0L) start.coerceAtMost((song.durationMs - 1000L).coerceAtLeast(0L))
            else start
        }
        val options = MediaLoadOptions.Builder()
            .setAutoplay(localPlaying)
            .setPlayPosition(startMs)
            .apply { if (withLyrics) setActiveTrackIds(longArrayOf(LYRICS_TRACK_ID)) }
            .build()

        attachRemoteCallback(remote)
        lastErrorReported = null
        lastSentUrl = song.streamUrl
        lastSentLyricsUrl = song.lyricsUrl
        pendingLoadKey = loadKey(song)
        lastFinishedContentId = null
        remoteShouldPlay = localPlaying
        // El resultado de la carga es la única fuente fiable de por qué el
        // televisor se queda en su pantalla de reposo: si el receptor la
        // rechaza se avisa y se libera la URL para poder reintentar.
        runCatching {
            remote.load(builder.build(), options).setResultCallback { result ->
                if (pendingLoadKey == loadKey(song)) {
                    pendingLoadKey = null
                    if (!result.status.isSuccess) {
                        lastSentUrl = null
                        lastSentLyricsUrl = null
                        val code = result.status.statusCode
                        val error = result.mediaError
                        if (error != null) {
                            reportCastError(explainCastError(error), "${mediaErrorDetail(error)} · http=$code")
                        } else {
                            val message = result.status.statusMessage ?: ""
                            reportCastError("El receptor rechazó la carga del audio", "código=$code $message".trim())
                        }
                    } else {
                        if (!CastOptionsProvider.isCustomReceiverEnabled &&
                            !song.lyricsUrl.isNullOrBlank()) {
                            // Fallback para el receptor estándar de Google:
                            // activar explícitamente la pista WebVTT.
                            remote.setActiveMediaTracks(longArrayOf(LYRICS_TRACK_ID))
                            applyLyricsStyle(remote)
                        }
                        // Receptor LocalFly: la pista VTT se dibuja en una capa
                        // de karaoke sincronizada con el reloj del reproductor.
                        sendKaraokeMessage(remote, song)
                    }
                }
            }
        }.onFailure { error ->
            lastSentUrl = null
            lastSentLyricsUrl = null
            pendingLoadKey = null
            reportCastError("No se pudo enviar el audio al televisor", error.message ?: "error local")
        }
        if (withLyrics) applyLyricsStyle(remote)
    }

    private fun sendKaraokeMessage(remote: RemoteMediaClient, song: CastSong) {
        if (!CastOptionsProvider.isCustomReceiverEnabled) return
        val payload = JSONObject().apply {
            put("type", "track")
            put("songId", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("lyricsUrl", song.lyricsUrl ?: JSONObject.NULL)
        }
        runCatching {
            remote.sendMessage(KARAOKE_NAMESPACE, payload.toString())
        }.onFailure {
            reportCastError("No se pudo enviar la letra al receptor karaoke", it.message ?: "error de canal")
        }
    }

    /** Registra (una sola vez por sesión) la callback de estado del receptor. */
    private fun attachRemoteCallback(remote: RemoteMediaClient) {
        if (callbackRemote === remote) return
        callbackRemote?.let { runCatching { it.unregisterCallback(remoteCallback) } }
        runCatching { remote.registerCallback(remoteCallback) }
        callbackRemote = remote
    }

    private fun reportCastError(message: String, detail: String = "") {
        val full = if (detail.isBlank()) message else "$message · $detail"
        if (lastErrorReported == full) return
        lastErrorReported = full
        onCastError?.invoke(full)
    }

    /** Detalle técnico del error del receptor (tipo/razón/código). */
    private fun mediaErrorDetail(error: MediaError?): String = listOfNotNull(
        error?.type?.let { "tipo=$it" },
        error?.reason?.let { "razón=$it" },
        error?.detailedErrorCode?.let { "código=$it" }
    ).joinToString(" · ")

    /** Traduce el error del receptor a algo entendible en pantalla. */
    private fun explainCastError(error: MediaError?): String = when (error?.type) {
        MediaError.ERROR_TYPE_LOAD_FAILED -> "El televisor no pudo cargar el audio"
        MediaError.ERROR_TYPE_INVALID_REQUEST -> "El receptor rechazó la petición de carga"
        MediaError.ERROR_TYPE_INVALID_PLAYER_STATE -> "El receptor estaba ocupado con otro contenido"
        MediaError.ERROR_TYPE_LOAD_CANCELLED -> "La carga se canceló en el receptor"
        else -> "El receptor rechazó la carga del audio"
    }

    /**
     * Estilo de los subtítulos en el TV (letra grande y legible a distancia).
     *
     * El receptor por defecto escribe la letra en letra pequeña, así que se
     * sube el tamaño y se fuerza fondo/texto legibles. Se usa tanto dentro del
     * `MediaInfo` (al cargar) como para volver a fijarlo después (`setTextTrackStyle`).
     *
     * El constructor sin argumentos + setters es la API pública de
     * `TextTrackStyle` en play-services-cast 21.4.0 (no hay Builder).
     */
    private fun lyricsStyle(): TextTrackStyle = TextTrackStyle().apply {
        fontScale = LYRICS_FONT_SCALE
        backgroundColor = LYRICS_BACKGROUND_COLOR
        foregroundColor = LYRICS_FOREGROUND_COLOR
        edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
        edgeColor = LYRICS_EDGE_COLOR
    }

    /**
     * Reaplica el estilo de subtítulos al receptor.
     *
     * Hay que aplicarlo DESPUÉS del load cuando el usuario apagó y encendió el
     * subtítulo en el mando del TV, porque en ese momento el receptor vuelve a
     * su estilo por defecto.
     */
    private fun applyLyricsStyle(remote: RemoteMediaClient) {
        runCatching { remote.setTextTrackStyle(lyricsStyle()) }
    }

    /**
     * Vuelve a aplicar el estilo de letra al receptor.
     *
     * Se llama al volver a la pantalla de reproducción: si el usuario apagó y
     * encendió el subtítulo en el mando del TV, al volver a entrar se quiere
     * recuperar la letra grande.
     */
    fun refreshLyricsStyle() {
        val remote = remoteClient() ?: return
        if (!hasSentMedia(remote)) return
        applyLyricsStyle(remote)
    }

    /**
     * Reemite la cancion actual al receptor aunque la URL no haya cambiado.
     *
     * Necesario al cambiar de "el audio suena en el telefono" a "el audio suena
     * en el televisor" (o al revés): el medio cambia a un MP3 de silencio o al
     * proxy con CORS, asi que hay que recargarlo para que el TV recoja el cambio.
     */
    fun reloadCurrentSong(localPlaying: Boolean) {
        val remote = remoteClient() ?: return
        val song = songProvider?.invoke() ?: return
        sendSong(remote, song, localPlaying)
    }

    /** Fuerza la reemision de la cancion actual (p. ej. al iniciar sesion de cast). */
    fun castCurrentSong(localPlaying: Boolean) {
        var song = songProvider?.invoke() ?: return
        val remote = remoteClient() ?: return
        if (pendingLoadKey == loadKey(song)) return
        if (adoptRemoteMediaIfMatching(remote, song)) return
        song = songProvider?.invoke() ?: return
        if (pendingLoadKey == loadKey(song)) return
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
            // Aun no se ha emitido nada: emitir la cancion actual en reproduccion.
            castCurrentSong(true)
            return
        }
        // Consultar el estado real del receptor: puede haberse pausado desde
        // el mando del televisor o desde otra app de control Cast.
        remoteShouldPlay = !remoteIsPlaying(remote)
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
        lastSentLyricsUrl = null
        pendingLoadKey = null
        runCatching { remote.stop() }
    }

    fun release() {
        callbackRemote?.let { runCatching { it.unregisterCallback(remoteCallback) } }
        callbackRemote = null
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
        lastSentLyricsUrl = null
        pendingLoadKey = null
        lastFinishedContentId = null
        remoteShouldPlay = false
        lastErrorReported = null
        callbackRemote = null
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
