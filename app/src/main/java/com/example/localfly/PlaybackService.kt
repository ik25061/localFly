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
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.bumptech.glide.Glide
import com.example.localfly.network.ApiConfig
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

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(this)
        downloadHelper = DownloadManagerHelper.getInstance(this)
        createNotificationChannel()

        player = ExoPlayer.Builder(this).build()
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
        })

        mediaSession = MediaSession.Builder(this, player!!).build()

        // Sincronizar acciones offline al iniciar
        syncOfflineActions()
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
        if (sessionManager.isAutoDeleteEnabled()) {
            val localPath = queueLocalPaths.getOrNull(index)
            if (localPath != null) {
                // Pequeño retardo para asegurar que ExoPlayer ha liberado el archivo
                serviceScope.launch {
                    delay(300)
                    withContext(Dispatchers.IO) {
                        downloadHelper.removeDownload(song.id)
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
        player?.prepare()
        player?.play()

        startForeground(NOTIFICATION_ID, buildNotification())
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
        // Mantener el estado guardado de la descarga (si la canción está descargada)
        downloadHelper.updateLiked(song.id, newLiked)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción de música",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
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

        // Action 1: Play/Pausa
        builder.addAction(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pausar" else "Reproducir",
            pendingIntentFor(ACTION_PLAY_PAUSE)
        )

        // Action 2: No me gusta
        builder.addAction(
            R.drawable.ic_dislike_off,
            "No me gusta",
            pendingIntentFor(ACTION_DISLIKE)
        )

        // Configuración MediaStyle (Standard para Android 12+)
        builder.setStyle(
            MediaNotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2) // Me gusta, Play/Pausa, No me gusta
                .setMediaSession(mediaSession?.sessionCompatToken)
        )

        return builder.build()
    }

    @UnstableApi
    private fun updateNotification() {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(
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
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(
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