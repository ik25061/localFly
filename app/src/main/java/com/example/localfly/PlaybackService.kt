package com.example.localfly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.bumptech.glide.Glide
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.ApiService
import com.example.localfly.network.HideRequest
import com.example.localfly.network.LikeRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service que mantiene la reproducción de música sonando en segundo plano,
 * gestiona la cola de canciones (siguiente/anterior) y muestra una
 * notificación con controles (me gusta, reproducir/pausar, no me gusta).
 */
@UnstableApi
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "localfly_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.example.localfly.ACTION_PLAY_PAUSE"
        const val ACTION_LIKE = "com.example.localfly.ACTION_LIKE"
        const val ACTION_DISLIKE = "com.example.localfly.ACTION_DISLIKE"
        const val ACTION_NEXT = "com.example.localfly.ACTION_NEXT"
        const val ACTION_PREV = "com.example.localfly.ACTION_PREV"
        const val ACTION_STOP = "com.example.localfly.ACTION_STOP"

        const val FADE_DURATION_MS = 2500L
        const val FADE_STEP_MS = 100L

        const val COMMAND_LIKE = "com.example.localfly.COMMAND_LIKE"
        const val COMMAND_DISLIKE = "com.example.localfly.COMMAND_DISLIKE"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Base usada solo para transmitir canciones que NO están descargadas
    private val serverBaseUrl = ApiConfig.BASE_URL

    var player: ExoPlayer? = null
        private set

    private var crossfadeEnabled = false
    private var fadeOutStartedForCurrentSong = false
    private var fadeJob: kotlinx.coroutines.Job? = null

    private val fadeTickerHandler = Handler(Looper.getMainLooper())
    private val fadeTickerRunnable = object : Runnable {
        override fun run() {
            checkFadeOutTrigger()
            fadeTickerHandler.postDelayed(this, 250)
        }
    }

    private var mediaSession: MediaSession? = null

    var currentSong: Song? = null
        private set

    /** Cola de reproducción actual (biblioteca completa, descargas, etc.) */
    var queue: List<Song> = emptyList()
        private set

    private var queueLocalPaths: List<String?> = emptyList()

    var currentIndex: Int = -1
        private set

    /** La actividad conectada puede suscribirse aquí para refrescar su UI */
    var onStateChanged: (() -> Unit)? = null

    private lateinit var downloadHelper: DownloadManagerHelper

    private lateinit var sessionManager: SessionManager

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(this)
        downloadHelper = DownloadManagerHelper.getInstance(this)
        createNotificationChannel()

        crossfadeEnabled = sessionManager.isCrossfadeEnabled()

        player = ExoPlayer.Builder(this).build()
        player?.skipSilenceEnabled = crossfadeEnabled
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                onStateChanged?.invoke()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    next()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // ExoPlayer trae precargada la siguiente canción como un
                // segundo item de su propia playlist interna (para que la
                // notificación de Android 13+ tenga botón "Siguiente"
                // nativo). Cuando la canción actual termina SOLA, ExoPlayer
                // avanza a ese segundo item por su cuenta, sin pasar por
                // next(). Hay que detectarlo aquí y sincronizar currentIndex
                // / currentSong / fundido / auto-borrado manualmente, o la
                // app se queda "ciega" a ese cambio (bug de canción
                // silenciosa que parece la misma canción).
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    handleAutoAdvance()
                }
            }
        })

        setupMediaSession()

        fadeTickerHandler.post(fadeTickerRunnable)

        // Sincronizar acciones offline al iniciar
        syncOfflineActions()
    }

    private fun setupMediaSession() {
        val callback = object : MediaSession.Callback {
            @UnstableApi
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(COMMAND_LIKE, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_DISLIKE, Bundle.EMPTY))
                    .build()
                
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
                
                updateMediaSessionCustomLayout()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    COMMAND_LIKE -> toggleLike()
                    COMMAND_DISLIKE -> dislikeCurrentSong()
                }
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                when (playerCommand) {
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                        next()
                        return SessionResult.RESULT_SUCCESS
                    }
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                        prev()
                        return SessionResult.RESULT_SUCCESS
                    }
                }
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
        }

        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(callback)
            .build()
            
        updateMediaSessionCustomLayout()
    }

    private fun updateMediaSessionCustomLayout() {
        val song = currentSong
        val likeIcon = if (song?.liked == true) R.drawable.ic_like_on else R.drawable.ic_like_off
        
        val likeButton = CommandButton.Builder(CommandButton.ICON_HEART_FILLED)
            .setSessionCommand(SessionCommand(COMMAND_LIKE, Bundle.EMPTY))
            .setIconResId(likeIcon)
            .setDisplayName("Me gusta")
            .build()

        val dislikeButton = CommandButton.Builder(CommandButton.ICON_BLOCK)
            .setSessionCommand(SessionCommand(COMMAND_DISLIKE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_dislike_off)
            .setDisplayName("No me gusta")
            .build()

        val customLayout = listOf(likeButton, dislikeButton)
        mediaSession?.setCustomLayout(customLayout)
    }

    /** Activa/desactiva en caliente el fundido + recorte de silencio desde la UI. */
    @UnstableApi
    fun setCrossfadeEnabled(enabled: Boolean) {
        crossfadeEnabled = enabled
        sessionManager.setCrossfadeEnabled(enabled)
        player?.skipSilenceEnabled = enabled
        if (!enabled) {
            fadeJob?.cancel()
            player?.volume = 1f
        }
    }

    /**
     * Revisa en cada tick si quedan <= FADE_DURATION_MS para que termine la
     * canción actual, y si es así arranca el fundido de salida. Solo
     * aplica a la transición NATURAL (canción llega a su fin sola); un
     * salto manual (Siguiente/Anterior) corta directo, sin esperar a este
     * temporizador.
     */
    private fun checkFadeOutTrigger() {
        if (!crossfadeEnabled) return
        val p = player ?: return
        if (!p.isPlaying) return
        val duration = p.duration
        if (duration <= 0) return
        val remaining = duration - p.currentPosition
        if (!fadeOutStartedForCurrentSong && remaining in 0..FADE_DURATION_MS) {
            fadeOutStartedForCurrentSong = true
            startFade(from = p.volume, to = 0f, durationMs = remaining.coerceAtMost(FADE_DURATION_MS))
        }
    }

    private fun startFade(from: Float, to: Float, durationMs: Long) {
        fadeJob?.cancel()
        if (durationMs <= 0) {
            player?.volume = to
            return
        }
        fadeJob = serviceScope.launch {
            val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val progress = i / steps.toFloat()
                player?.volume = from + (to - from) * progress
                delay(FADE_STEP_MS)
            }
            player?.volume = to
        }
    }

    private fun syncOfflineActions() {
        serviceScope.launch(Dispatchers.IO) {
            // Intentar sincronizar periódicamente si hay acciones pendientes.
            while (true) {
                val pendingLikes = sessionManager.getPendingLikes()
                val pendingDislikes = sessionManager.getPendingDislikes()

                if (pendingLikes.isEmpty() && pendingDislikes.isEmpty()) {
                    delay(30000) // Revisar cada 30 segundos si aparecen nuevas
                    continue
                }

                var anySuccess = false

                // Sincronizar Likes
                pendingLikes.forEach { (songId, liked) ->
                    try {
                        val response = RetrofitClient.api.likeSong(
                            songId,
                            LikeRequest(sessionManager.getUserId(), liked)
                        )
                        if (response.isSuccessful) {
                            sessionManager.removePendingLike(songId)
                            anySuccess = true
                        }
                    } catch (e: Exception) {
                        // Sin conexión o error de red: se reintentará en el siguiente ciclo.
                    }
                }

                // Sincronizar Dislikes
                pendingDislikes.forEach { songId ->
                    try {
                        val response = RetrofitClient.api.hideSong(
                            songId,
                            HideRequest(sessionManager.getUserId())
                        )
                        if (response.isSuccessful) {
                            sessionManager.removePendingDislike(songId)
                            anySuccess = true
                        }
                    } catch (e: Exception) {
                        // Error de red: reintentar luego.
                    }
                }

                if (anySuccess) {
                    withContext(Dispatchers.Main) {
                        onStateChanged?.invoke()
                    }
                }

                // Si falló (no hubo éxitos pero había pendientes), esperar un poco antes de reintentar.
                // Si tuvo éxito, podemos seguir de inmediato con los que falten o esperar un ciclo corto.
                delay(15000) 
            }
        }
    }

    private fun checkAutoDelete(song: Song?, index: Int) {
        if (song == null) return
        if (!sessionManager.isAutoDeleteEnabled()) return

        // No confiar solo en queueLocalPaths (depende de que la pantalla de
        // origen haya construido la cola pasando localPaths correctamente).
        // Confirmar también contra el registro real de descargas para que
        // el auto-borrado nunca dependa silenciosamente de eso.
        val localPath = queueLocalPaths.getOrNull(index)
        val isDownloaded = localPath != null || downloadHelper.isDownloaded(song.id)
        if (!isDownloaded) return

        // Pequeño retardo para asegurar que ExoPlayer ha liberado el archivo
        serviceScope.launch {
            delay(300)
            try {
                withContext(Dispatchers.IO) {
                    downloadHelper.removeDownload(song.id)
                }
            } catch (e: Exception) {
                // No dejar que un fallo aquí tumbe el resto del flujo de reproducción
            }

            // Limpiar la ruta en la cola para que no intente reproducirla de nuevo localmente
            val mutablePaths = queueLocalPaths.toMutableList()
            if (index in mutablePaths.indices) {
                mutablePaths[index] = null
                queueLocalPaths = mutablePaths
            }

            withContext(Dispatchers.Main) {
                onStateChanged?.invoke()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_LIKE -> toggleLike()
            ACTION_DISLIKE -> dislikeCurrentSong()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> {
                player?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Establece una nueva cola de reproducción y empieza a reproducir
     * desde [startIndex]. [localPaths], si se pasa, debe tener el mismo
     * tamaño que [songs]; cada posición null significa "transmitir desde
     * el servidor", y cada ruta no-null significa "reproducir ese archivo
     * ya descargado".
     */
    fun setQueueAndPlay(songs: List<Song>, startIndex: Int, localPaths: List<String?>? = null) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        queue = songs
        queueLocalPaths = localPaths ?: List(songs.size) { null }
        currentIndex = startIndex
        playCurrentIndex()
    }

    /** Atajo para reproducir una sola canción (la convierte en cola de tamaño 1) */
    fun playSong(song: Song, localFilePath: String? = null) {
        setQueueAndPlay(listOf(song), 0, listOf(localFilePath))
    }

    /** Inserta una canción justo después de la actual en la cola */
    fun playNext(song: Song) {
        if (queue.isEmpty()) {
            playSong(song)
            return
        }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        val insertIndex = currentIndex + 1
        mutableQueue.add(insertIndex, song)
        mutablePaths.add(insertIndex, null)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    /** Añade una canción al final de la cola actual */
    fun addToQueue(song: Song) {
        if (queue.isEmpty()) {
            playSong(song)
            return
        }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.add(song)
        mutablePaths.add(null)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    /** Añade una lista de canciones al final de la cola actual */
    fun addListToQueue(songs: List<Song>) {
        if (queue.isEmpty()) {
            setQueueAndPlay(songs, 0)
            return
        }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.addAll(songs)
        mutablePaths.addAll(List(songs.size) { null })
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    /**
     * Mueve una canción de la cola de [fromIndex] a [toIndex] (índices
     * absolutos sobre `queue`, no relativos a la vista "próximas"). No
     * permite mover la canción que está sonando actualmente.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == currentIndex || toIndex == currentIndex) return
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return

        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        val song = mutableQueue.removeAt(fromIndex)
        val path = mutablePaths.removeAt(fromIndex)
        mutableQueue.add(toIndex, song)
        mutablePaths.add(toIndex, path)
        queue = mutableQueue
        queueLocalPaths = mutablePaths

        // Si el movimiento cruza por encima/debajo de la canción actual,
        // el índice de la canción en reproducción cambia de posición.
        currentIndex = when {
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        onStateChanged?.invoke()
    }

    /**
     * Elimina una canción de la cola por índice absoluto. No se puede
     * eliminar así la canción que está sonando actualmente (para eso está
     * "Siguiente"/"Anterior").
     */
    fun removeFromQueue(index: Int) {
        if (index == currentIndex) return
        if (index !in queue.indices) return

        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.removeAt(index)
        mutablePaths.removeAt(index)
        queue = mutableQueue
        queueLocalPaths = mutablePaths

        if (index < currentIndex) currentIndex--
        onStateChanged?.invoke()
    }

    /**
     * Reemplaza la cola completa (p.ej. tras un Smart Reorder).
     * Mantiene la canción actual intacta si se incluye en la nueva lista.
     */
    fun updateFullQueue(newSongs: List<Song>) {
        val currentSongId = currentSong?.id
        val mutablePaths = newSongs.map { downloadHelper.getLocalFilePath(it.id) }
        
        queue = newSongs
        queueLocalPaths = mutablePaths
        
        if (currentSongId != null) {
            val newIdx = newSongs.indexOfFirst { it.id == currentSongId }
            if (newIdx != -1) {
                currentIndex = newIdx
            }
        }
        onStateChanged?.invoke()
    }

    /**
     * Se llama cuando ExoPlayer avanzó SOLO a la canción precargada tras
     * terminar la actual (ver comentario en onMediaItemTransition). Replica
     * lo que hace next() para mantener currentIndex/currentSong, el
     * fundido, el auto-borrado y la notificación sincronizados con lo que
     * realmente está sonando.
     */
    private fun handleAutoAdvance() {
        if (!hasNext()) return // por seguridad; si no hay siguiente, no debería haber pasado esto

        val songToHandle = currentSong
        val indexToHandle = currentIndex

        currentIndex++
        currentSong = queue.getOrNull(currentIndex)

        // Reiniciar el fundido para la canción que ExoPlayer ya empezó a
        // reproducir de verdad (arrancó en volumen 0 tras el fade-out de
        // la anterior; aquí lo subimos de vuelta).
        fadeOutStartedForCurrentSong = false
        fadeJob?.cancel()
        if (crossfadeEnabled) {
            player?.volume = 0f
            startFade(from = 0f, to = 1f, durationMs = FADE_DURATION_MS)
        } else {
            player?.volume = 1f
        }

        // Volver a precargar UNA canción más por delante, para que el
        // botón "Siguiente" nativo del sistema siga funcionando en la
        // próxima transición también (si no, solo funcionaría una vez).
        if (hasNext()) {
            val nextSong = queue[currentIndex + 1]
            val nextLocalPath = queueLocalPaths.getOrNull(currentIndex + 1)
            val nextMediaItem = MediaItem.Builder()
                .setUri(
                    if (nextLocalPath != null) Uri.fromFile(File(nextLocalPath))
                    else Uri.parse("$serverBaseUrl/audio/${nextSong.id}")
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(nextSong.title)
                        .setArtist(nextSong.artist)
                        .build()
                )
                .build()
            player?.addMediaItem(nextMediaItem)
        }

        startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        onStateChanged?.invoke()

        // Borrar la descarga de la canción que acaba de terminar, igual
        // que en un "Siguiente" manual.
        checkAutoDelete(songToHandle, indexToHandle)
    }

    fun next() {
        val songToHandle = currentSong
        val indexToHandle = currentIndex

        if (currentIndex + 1 < queue.size) {
            currentIndex++
            playCurrentIndex()
            
            // Borrar la descarga de la canción anterior DESPUÉS de cambiar a la nueva
            // para asegurar que el archivo ya no está bloqueado por el player.
            checkAutoDelete(songToHandle, indexToHandle)
        } else {
            // No hay más canciones en la cola. 
            // Al pulsar "Siguiente" en la última canción, detenemos y limpiamos.
            player?.stop()
            player?.clearMediaItems()
            
            checkAutoDelete(songToHandle, indexToHandle)
            
            currentSong = null
            currentIndex = -1
            stopForeground(STOP_FOREGROUND_REMOVE)
            onStateChanged?.invoke()
        }
    }

    fun prev() {
        if (currentIndex > 0) {
            currentIndex--
            playCurrentIndex()
        }
    }

    fun hasNext(): Boolean = currentIndex + 1 < queue.size
    fun hasPrev(): Boolean = currentIndex > 0

    fun getProgressMs(): Long = player?.currentPosition ?: 0L
    fun getDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    @UnstableApi
    private fun playCurrentIndex() {
        val song = queue.getOrNull(currentIndex) ?: return
        val localPath = queueLocalPaths.getOrNull(currentIndex)
        currentSong = song

        // Metadatos de la canción para que la tarjeta de medios del sistema,
        // el panel rápido y la pantalla de bloqueo muestren la información.
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
        if (localPath == null) {
            metaBuilder.setArtworkUri(Uri.parse("$serverBaseUrl/cover/${song.id}"))
        }

        val mediaItem = MediaItem.Builder()
            .setUri(
                if (localPath != null) Uri.fromFile(File(localPath))
                else Uri.parse("$serverBaseUrl/audio/${song.id}")
            )
            .setMediaMetadata(metaBuilder.build())
            .build()

        player?.setMediaItem(mediaItem)
        
        // Añadir también la siguiente canción si existe, para que el sistema
        // muestre el botón "Siguiente" nativo en Android 13+.
        if (hasNext()) {
            val nextSong = queue[currentIndex + 1]
            val nextLocalPath = queueLocalPaths.getOrNull(currentIndex + 1)
            val nextMediaItem = MediaItem.Builder()
                .setUri(
                    if (nextLocalPath != null) Uri.fromFile(File(nextLocalPath))
                    else Uri.parse("$serverBaseUrl/audio/${nextSong.id}")
                )
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(nextSong.title)
                    .setArtist(nextSong.artist)
                    .build()
                )
                .build()
            player?.addMediaItem(nextMediaItem)
        }

        player?.prepare()
        
        fadeOutStartedForCurrentSong = false
        fadeJob?.cancel()
        if (crossfadeEnabled) {
            player?.volume = 0f
        } else {
            player?.volume = 1f
        }
        player?.play()
        
        if (crossfadeEnabled) {
            startFade(from = 0f, to = 1f, durationMs = FADE_DURATION_MS)
        }

        updateMediaSessionCustomLayout()
        startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        onStateChanged?.invoke()
    }

    fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun toggleShuffle() {
        player?.let {
            it.shuffleModeEnabled = !it.shuffleModeEnabled
            onStateChanged?.invoke()
        }
    }

    fun toggleRepeat() {
        player?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            onStateChanged?.invoke()
        }
    }

    /** Marca/desmarca "me gusta" en la canción actual y avisa al servidor */
    fun toggleLike() {
        val song = currentSong ?: return
        val newLiked = !song.liked
        currentSong = song.copy(liked = newLiked)

        // Aprendizaje en línea: si esta canción vino de una sugerencia de
        // la IA, "me gusta" refuerza positivamente las reglas que la
        // sugirieron; quitar el "me gusta" las castiga un poco.
        com.example.localfly.ai.AIWeightsStore(this).reinforce(song.id, if (newLiked) 1f else -0.5f)
        // Mantener el estado guardado de la descarga (si la canción está descargada)
        downloadHelper.updateLiked(song.id, newLiked)
        updateMediaSessionCustomLayout()
        updateNotification()
        onStateChanged?.invoke()

        serviceScope.launch {
            try {
                val response = RetrofitClient.api.likeSong(
                    song.id,
                    LikeRequest(sessionManager.getUserId(), newLiked)
                )
                if (!response.isSuccessful) {
                    sessionManager.addPendingLike(song.id, newLiked)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar para después
                sessionManager.addPendingLike(song.id, newLiked)
            }
        }
    }

    /** "No me gusta": oculta la canción en el servidor y avanza a la siguiente */
    fun dislikeCurrentSong() {
        val songToHide = currentSong ?: return

        // Aprendizaje en línea: señal negativa fuerte si la IA la sugirió.
        com.example.localfly.ai.AIWeightsStore(this).reinforce(songToHide.id, -1f)

        serviceScope.launch {
            try {
                val response = RetrofitClient.api.hideSong(songToHide.id, HideRequest(sessionManager.getUserId()))
                if (!response.isSuccessful) {
                    sessionManager.addPendingDislike(songToHide.id)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar para después
                sessionManager.addPendingDislike(songToHide.id)
            }
        }

        // Avanzar a la siguiente (next ya maneja el auto-borrado de la actual)
        next()
    }

    /**
     * Sube al servidor las letras que se encontraron vía LRCLIB directo
     * mientras el servidor no era alcanzable, para que queden guardadas
     * como archivo .lrc físico (ver endpoint del documento de mirepo).
     * Se llama automáticamente desde MainActivity en cuanto detecta que el
     * servidor volvió a estar disponible.
     */
    fun flushPendingLyricsUploads() {
        val pending = sessionManager.getPendingLyricsUploads()
        if (pending.isEmpty()) return

        serviceScope.launch {
            for ((songId, content) in pending) {
                try {
                    val response = RetrofitClient.api.saveLyricsFile(
                        songId,
                        ApiService.SaveLyricsFileRequest(content)
                    )
                    if (response.isSuccessful) {
                        sessionManager.removePendingLyricsUpload(songId)
                    }
                } catch (e: Exception) {
                    // Se reintentará en el próximo "servidor disponible"
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reproducción de música",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentFor(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @UnstableApi
    private fun buildNotification(largeIcon: Bitmap? = null): Notification {
        val song = currentSong
        val isPlaying = player?.isPlaying == true

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkles)
            .setContentTitle(song?.title ?: "localFly")
            .setContentText(song?.artist ?: "Música para tus oídos")
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)

        // IMPORTANTE: La imagen del álbum debe ir aquí
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        // Action 0: Like
        builder.addAction(
            if (song?.liked == true) R.drawable.ic_like_on else R.drawable.ic_like_off,
            "Me gusta",
            pendingIntentFor(ACTION_LIKE)
        )

        // Action 1: Anterior
        builder.addAction(
            android.R.drawable.ic_media_previous,
            "Anterior",
            pendingIntentFor(ACTION_PREV)
        )

        // Action 2: Play/Pausa
        builder.addAction(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pausar" else "Reproducir",
            pendingIntentFor(ACTION_PLAY_PAUSE)
        )

        // Action 3: Siguiente
        builder.addAction(
            android.R.drawable.ic_media_next,
            "Siguiente",
            pendingIntentFor(ACTION_NEXT)
        )

        // Action 4: No me gusta
        builder.addAction(
            R.drawable.ic_dislike_off,
            "No me gusta",
            pendingIntentFor(ACTION_DISLIKE)
        )

        // Configuración MediaStyle para Media3
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(1, 2, 3) // Anterior, Play/Pausa, Siguiente
            )
        }

        return builder.build()
    }

    @UnstableApi
    private fun updateNotification() {
        val hasPermission = ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            // Publicar versión inicial (sin imagen o con la anterior)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            // Cargar nueva imagen
            loadLargeIcon()
        }
    }

    @UnstableApi
    private fun loadLargeIcon() {
        val song = currentSong ?: return
        serviceScope.launch {
            val coverUrl = if (!song.album.isNullOrBlank()) {
                val encodedAlbum = java.net.URLEncoder.encode(song.album, "UTF-8").replace("+", "%20")
                "$serverBaseUrl/resources/album - $encodedAlbum.jpg"
            } else {
                "$serverBaseUrl/cover/${song.id}"
            }

            try {
                val bitmap = withContext(Dispatchers.IO) {
                    Glide.with(this@PlaybackService)
                        .asBitmap()
                        .load(coverUrl)
                        .placeholder(R.drawable.ic_music_placeholder)
                        .error(
                            Glide.with(this@PlaybackService)
                                .asBitmap()
                                .load("$serverBaseUrl/cover/${song.id}")
                                .submit(512, 512)
                                .get()
                        )
                        .submit(512, 512)
                        .get()
                }

                showNotificationWithBitmap(bitmap)
            } catch (e: Exception) {
                // Fallback silencioso
            }
        }
    }

    @UnstableApi
    private fun showNotificationWithBitmap(bitmap: Bitmap) {
        val hasPermission = ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            NotificationManagerCompat.from(this@PlaybackService).notify(
                NOTIFICATION_ID, 
                buildNotification(bitmap)
            )
        }
    }

    override fun onDestroy() {
        fadeTickerHandler.removeCallbacks(fadeTickerRunnable)
        fadeJob?.cancel()
        // Detiene el bucle de sincronización offline para no acumular
        // corrutinas ni hacer llamadas redundantes al servidor.
        serviceScope.cancel()
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}