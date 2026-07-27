package com.example.localfly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.localfly.network.HideRequest
import com.example.localfly.network.LikeRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val serverBaseUrl = "http://127.0.0.1:5002"

    var player: ExoPlayer? = null
        private set

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
                    next()
                }
            }
        })
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

        val mediaItem = if (localPath != null) {
            MediaItem.fromUri(Uri.fromFile(File(localPath)))
        } else {
            MediaItem.fromUri("$serverBaseUrl/audio/${song.id}")
        }

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
                RetrofitClient.api.likeSong(
                    song.id,
                    LikeRequest(sessionManager.getUserId(), newLiked)
                )
            } catch (e: Exception) {
                // Sin conexión: el like queda solo reflejado localmente por ahora
            }
        }
    }

    /** "No me gusta": oculta la canción en el servidor y avanza a la siguiente */
    fun dislikeCurrentSong() {
        val song = currentSong ?: return

        serviceScope.launch {
            try {
                RetrofitClient.api.hideSong(song.id, HideRequest(sessionManager.getUserId()))
            } catch (e: Exception) {
                // Sin conexión: se podrá reintentar más adelante
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

    private fun buildNotification(): Notification {
        val song = currentSong
        val isPlaying = player?.isPlaying == true

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song?.title ?: "Mirepo")
            .setContentText(song?.artist ?: "")
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(
                if (song?.liked == true) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off,
                "Me gusta",
                pendingIntentFor(ACTION_LIKE)
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                "Reproducir/Pausar",
                pendingIntentFor(ACTION_PLAY_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "No me gusta",
                pendingIntentFor(ACTION_DISLIKE)
            )
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}