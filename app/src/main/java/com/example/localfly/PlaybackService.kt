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

    private lateinit var sessionManager: SessionManager

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(this)
        createNotificationChannel()

        player = ExoPlayer.Builder(this).build()
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                onStateChanged?.invoke()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    checkAutoDelete()
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
            // Intentar sincronizar cada 30 segundos si hay pendientes
            while (true) {
                val pendingLikes = sessionManager.getPendingLikes()
                val pendingDislikes = sessionManager.getPendingDislikes()

                if (pendingLikes.isEmpty() && pendingDislikes.isEmpty()) {
                    delay(60000) // Esperar un minuto si no hay nada
                    continue
                }

                // Sincronizar Likes
                pendingLikes.forEach { (songId, liked) ->
                    try {
                        val response = RetrofitClient.api.likeSong(
                            songId,
                            LikeRequest(sessionManager.getUserId(), liked)
                        )
                        if (response.isSuccessful) {
                            sessionManager.removePendingLike(songId)
                        }
                    } catch (e: Exception) {
                        // Seguir intentando más tarde
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
                        }
                    } catch (e: Exception) {
                        // Seguir intentando más tarde
                    }
                }

                delay(30000)
            }
        }
    }

    private fun checkAutoDelete() {
        if (sessionManager.isAutoDeleteEnabled()) {
            val songId = currentSong?.id ?: return
            val localPath = queueLocalPaths.getOrNull(currentIndex)
            if (localPath != null) {
                // Es una descarga. La eliminamos.
                DownloadManagerHelper(this).removeDownload(songId)
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
        if (currentIndex + 1 < queue.size) {
            currentIndex++
            playCurrentIndex()
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

    /** Marca/desmarca "me gusta" en la canción actual y avisa al servidor */
    fun toggleLike() {
        val song = currentSong ?: return
        val newLiked = !song.liked
        currentSong = song.copy(liked = newLiked)
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
        val song = currentSong ?: return

        serviceScope.launch {
            try {
                val response = RetrofitClient.api.hideSong(song.id, HideRequest(sessionManager.getUserId()))
                if (!response.isSuccessful) {
                    sessionManager.addPendingDislike(song.id)
                }
            } catch (e: Exception) {
                // Sin conexión: guardar para después
                sessionManager.addPendingDislike(song.id)
            }
        }

        if (hasNext()) {
            next()
        } else {
            player?.stop()
            currentSong = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            onStateChanged?.invoke()
        }
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
            R.drawable.ic_dislike,
            "No me gusta",
            pendingIntentFor(ACTION_DISLIKE)
        )

        builder.setStyle(
            MediaNotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3) // Anterior, Play, Siguiente (Standard compact)
                .setMediaSession(mediaSession?.sessionCompatToken)
        )

        // Vista personalizada: carátula del álbum como fondo + botones
        // like, anterior, reproducir/pausar, siguiente y no me gusta.
        builder.setCustomContentView(buildNotificationSmallView(song, isPlaying, largeIcon))
        builder.setCustomBigContentView(buildNotificationBigView(song, isPlaying, largeIcon))

        return builder.build()
    }

    /** Construye la vista compacta (plegada) de la notificación. */
    private fun buildNotificationSmallView(song: Song?, isPlaying: Boolean, art: Bitmap?): RemoteViews {
        val rv = RemoteViews(packageName, R.layout.notification_small)
        rv.setTextViewText(R.id.tv_title, song?.title ?: "localFly")
        rv.setTextViewText(R.id.tv_artist, song?.artist ?: "Música para tus oídos")
        applyAlbumArt(rv, art)

        bindMediaButton(rv, R.id.btn_like, if (song?.liked == true) R.drawable.ic_like_on else R.drawable.ic_like_off, "Me gusta", ACTION_LIKE)
        bindMediaButton(rv, R.id.btn_prev, R.drawable.ic_prev, "Anterior", ACTION_PREV, enabled = hasPrev())
        bindMediaButton(rv, R.id.btn_play, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (isPlaying) "Pausar" else "Reproducir", ACTION_PLAY_PAUSE)
        bindMediaButton(rv, R.id.btn_next, R.drawable.ic_next, "Siguiente", ACTION_NEXT, enabled = hasNext())
        bindMediaButton(rv, R.id.btn_dislike, R.drawable.ic_dislike, "No me gusta", ACTION_DISLIKE)
        return rv
    }

    /** Construye la vista expandida de la notificación. */
    private fun buildNotificationBigView(song: Song?, isPlaying: Boolean, art: Bitmap?): RemoteViews {
        val rv = RemoteViews(packageName, R.layout.notification_big)
        rv.setTextViewText(R.id.tv_title, song?.title ?: "localFly")
        rv.setTextViewText(R.id.tv_artist, song?.artist ?: "Música para tus oídos")

        val album = song?.album
        if (album.isNullOrBlank()) {
            rv.setViewVisibility(R.id.tv_album, View.GONE)
        } else {
            rv.setTextViewText(R.id.tv_album, album)
            rv.setViewVisibility(R.id.tv_album, View.VISIBLE)
        }
        applyAlbumArt(rv, art)

        bindMediaButton(rv, R.id.btn_like, if (song?.liked == true) R.drawable.ic_like_on else R.drawable.ic_like_off, "Me gusta", ACTION_LIKE)
        bindMediaButton(rv, R.id.btn_prev, R.drawable.ic_prev, "Anterior", ACTION_PREV, enabled = hasPrev())
        bindMediaButton(rv, R.id.btn_play, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (isPlaying) "Pausar" else "Reproducir", ACTION_PLAY_PAUSE)
        bindMediaButton(rv, R.id.btn_next, R.drawable.ic_next, "Siguiente", ACTION_NEXT, enabled = hasNext())
        bindMediaButton(rv, R.id.btn_dislike, R.drawable.ic_dislike, "No me gusta", ACTION_DISLIKE)
        return rv
    }

    /** Pone la carátula como fondo o un color oscuro si aún no se ha cargado. */
    private fun applyAlbumArt(rv: RemoteViews, art: Bitmap?) {
        if (art != null) {
            rv.setImageViewBitmap(R.id.album_background, art)
        } else {
            rv.setInt(R.id.album_background, "setBackgroundColor", 0xFF1B1B1E.toInt())
        }
    }

    /** Conecta un botón de la notificación con su acción del servicio. */
    private fun bindMediaButton(
        rv: RemoteViews,
        viewId: Int,
        iconRes: Int,
        label: String,
        action: String,
        enabled: Boolean = true
    ) {
        rv.setImageViewResource(viewId, iconRes)
        rv.setContentDescription(viewId, label)
        rv.setOnClickPendingIntent(viewId, pendingIntentFor(action))
        rv.setBoolean(viewId, "setEnabled", enabled)
        rv.setFloat(viewId, "setAlpha", if (enabled) 1f else 0.35f)
    }

    @UnstableApi
    private fun updateNotification() {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            // First show without image
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            // Then load image and update
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
                                .submit(256, 256)
                                .get()
                        )
                        .submit(256, 256)
                        .get()
                }

                val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ActivityCompat.checkSelfPermission(
                            this@PlaybackService,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    NotificationManagerCompat.from(this@PlaybackService).notify(
                        NOTIFICATION_ID, 
                        buildNotification(bitmap)
                    )
                }
            } catch (e: Exception) {
                // Fallback a la imagen por ID si falla el formato por nombre
                try {
                    val fallbackBitmap = withContext(Dispatchers.IO) {
                        Glide.with(this@PlaybackService)
                            .asBitmap()
                            .load("$serverBaseUrl/cover/${song.id}")
                            .submit(256, 256)
                            .get()
                    }
                    NotificationManagerCompat.from(this@PlaybackService).notify(
                        NOTIFICATION_ID, 
                        buildNotification(fallbackBitmap)
                    )
                } catch (e2: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}