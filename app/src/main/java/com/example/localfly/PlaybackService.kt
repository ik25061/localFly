package com.example.localfly

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.localfly.ai.AIRecommendationManager
import com.example.localfly.ai.AIWeightsStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.ApiService
import com.example.localfly.network.HideRequest
import com.example.localfly.network.LikeRequest
import com.example.localfly.network.MetadataSyncManager
import com.example.localfly.network.PlaylistSyncManager
import com.example.localfly.network.ProgressUpdateRequest
import com.example.localfly.network.RadioManager
import com.example.localfly.network.RadioPublishRequest
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.ServerReachability
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import com.example.localfly.network.SongAdminStore
import com.example.localfly.utils.LocalLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service que mantiene la reproducción de música sonando en segundo plano,
 * gestiona la cola de canciones (siguiente/anterior) y se integra con el
 * sistema mediante MediaSessionService.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_LOCAL_BIND = "com.example.localfly.ACTION_LOCAL_BIND"

        const val FADE_DURATION_MS = 2500L
        const val FADE_STEP_MS = 100L

        const val COMMAND_LIKE = "com.example.localfly.COMMAND_LIKE"
        const val COMMAND_DISLIKE = "com.example.localfly.COMMAND_DISLIKE"

        /** Desfase máximo tolerado por el oyente de una radio antes de corregir. */
        const val RADIO_DRIFT_TOLERANCE_MS = 4000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val serverBaseUrl = RetrofitClient.getBaseUrl()

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

    private val progressTrackerHandler = Handler(Looper.getMainLooper())
    private val progressTrackerRunnable = object : Runnable {
        override fun run() {
            saveEpisodeProgress()
            progressTrackerHandler.postDelayed(this, 5000)
        }
    }

    private fun saveEpisodeProgress() {
        val song = currentSong ?: return
        if (song.isEpisode) {
            val position = player?.currentPosition ?: 0L
            song.lastPositionMs = position
            serviceScope.launch {
                try {
                    RetrofitClient.api.updateEpisodeProgress(
                        ProgressUpdateRequest(song.id, sessionManager.getUserId(), position)
                    )
                } catch (e: Exception) {}
            }
        }
    }

    private var mediaSession: MediaSession? = null

    var currentSong: Song? = null
        private set

    var queue: List<Song> = emptyList()
        private set

    private var queueLocalPaths: List<String?> = emptyList()

    var currentIndex: Int = -1
        private set

    var onStateChanged: (() -> Unit)? = null

    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        LocalLogger.log(this, "PlaybackService iniciado (MediaSessionService)")
        sessionManager = SessionManager(this)
        downloadHelper = DownloadManagerHelper.getInstance(this)

        crossfadeEnabled = sessionManager.isCrossfadeEnabled()

        player = ExoPlayer.Builder(this).build()
        player?.skipSilenceEnabled = crossfadeEnabled
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onStateChanged?.invoke()
                if (isPlaying) {
                    if (crossfadeEnabled) fadeTickerHandler.post(fadeTickerRunnable)
                    progressTrackerHandler.post(progressTrackerRunnable)
                } else {
                    fadeTickerHandler.removeCallbacks(fadeTickerRunnable)
                    progressTrackerHandler.removeCallbacks(progressTrackerRunnable)
                    // Guardar progreso final al pausar
                    saveEpisodeProgress()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    next()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Sincronización mediante mediaId: identificar la canción actual 
                // por su ID único contenido en el MediaItem.
                val mediaId = mediaItem?.mediaId
                if (mediaId != null) {
                    val newIndex = queue.indexOfFirst { it.id == mediaId }
                    if (newIndex != -1) {
                        currentIndex = newIndex
                        currentSong = queue.getOrNull(currentIndex)
                        LocalLogger.log(this@PlaybackService, "MediaItem sync: currentIndex=$currentIndex (ID=$mediaId)")
                    }
                } else {
                    syncIndexById()
                }

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || 
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    handleAutoAdvanceFromTransition()
                }
                // Cada vez que cambiamos de canción, verificar si la cola se está agotando
                checkAndRefillQueue()
            }
        })

        setupMediaSession()

        setupRadio()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )

        // ===== IMPORTANTE PARA REPRODUCCIÓN EN SEGUNDO PLANO =====
        // Nuestras Activities (MainActivity/NowPlayingActivity) se vinculan con el
        // binder local (ACTION_LOCAL_BIND), por lo que el framework NUNCA registra
        // la sesión por sí solo (eso solo ocurre cuando un MediaController real
        // conecta contra androidx.media3.session.MediaSessionService).
        //
        // Sin este registro, Media3 no muestra la notificación de reproducción ni
        // promueve el servicio a foreground. Entonces, al bloquear la pantalla se
        // llama a onStop() -> unbindService(), y como el servicio ni está "started"
        // ni en foreground ni tiene sesión registrada, el sistema lo DESTRUYE y la
        // música se detiene (además de perderse la cola, por eso al desbloquear no
        // aparece nada reproduciéndose).
        //
        // addSession() es idempotente (si la sesión ya estaba registrada no hace
        // nada) y hace que Media3 cree el controlador interno de notificación, que
        // es justo el disparador del auto-foreground: en cuanto el player empiece a
        // sonar (STATE_READY + playWhenReady), la notificación sale y el servicio
        // pasa a foreground, sobreviviendo al bloqueo de pantalla y a los unbind.
        mediaSession?.let { session ->
            addSession(session)
            LocalLogger.log(this, "PlaybackService: sesión registrada con MediaSessionService (addSession)")
        }

        syncOfflineActions()
    }

    private fun setupMediaSession() {
        val callback = object : MediaSession.Callback {
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
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(callback)
            .setSessionActivity(sessionActivityPendingIntent)
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

        // Enviar botones custom. El sistema los ubicará según la versión de Android.
        mediaSession?.setCustomLayout(listOf(likeButton, dislikeButton))
    }

    fun setCrossfadeEnabled(enabled: Boolean) {
        crossfadeEnabled = enabled
        sessionManager.setCrossfadeEnabled(enabled)
        player?.skipSilenceEnabled = enabled
        if (!enabled) {
            fadeJob?.cancel()
            player?.volume = 1f
            fadeTickerHandler.removeCallbacks(fadeTickerRunnable)
        } else if (player?.isPlaying == true) {
            fadeTickerHandler.post(fadeTickerRunnable)
        }
    }

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
            while (isActive) {
                // Punto 3: Optimización de Backend. Solo intentar sincronizar si hay 
                // conexión real confirmada, para no saturar con peticiones fallidas.
                if (!ServerReachability.isOnline) {
                    delay(20000)
                    continue
                }

                val pendingLikes = sessionManager.getPendingLikes()
                val pendingDislikes = sessionManager.getPendingDislikes()
                val pendingPlaylistCreations = sessionManager.getPendingPlaylistCreations()
                val pendingPlaylistAdds = sessionManager.getPendingPlaylistSongAdds()
                val pendingLyrics = sessionManager.getPendingLyricsUploads()

                if (pendingLikes.isEmpty() && pendingDislikes.isEmpty() &&
                    pendingPlaylistCreations.isEmpty() && pendingPlaylistAdds.isEmpty() &&
                    pendingLyrics.isEmpty()) {
                    
                    // Sincronizar metadatos pendientes (si existen)
                    try { MetadataSyncManager.syncPendingEdits(sessionManager) } catch (_: Exception) {}
                    
                    delay(30000)
                    continue
                }

                var anySuccess = false
                
                // 1. Likes
                pendingLikes.forEach { (songId, liked) ->
                    try {
                        val response = RetrofitClient.api.likeSong(songId, LikeRequest(sessionManager.getUserId(), liked))
                        if (response.isSuccessful) {
                            sessionManager.removePendingLike(songId)
                            anySuccess = true
                        }
                    } catch (e: Exception) {}
                }
                
                // 2. Dislikes
                pendingDislikes.forEach { songId ->
                    try {
                        val response = RetrofitClient.api.hideSong(songId, HideRequest(sessionManager.getUserId()))
                        if (response.isSuccessful) {
                            sessionManager.removePendingDislike(songId)
                            anySuccess = true
                        }
                    } catch (e: Exception) {}
                }
                
                // 3. Playlists y ediciones
                if (pendingPlaylistCreations.isNotEmpty() || pendingPlaylistAdds.isNotEmpty()) {
                    try {
                        PlaylistSyncManager.sync(sessionManager)
                        anySuccess = true
                    } catch (e: Exception) {}
                }

                // 4. Letras encontradas offline
                if (pendingLyrics.isNotEmpty()) {
                    for ((songId, content) in pendingLyrics) {
                        try {
                            val response = RetrofitClient.api.saveLyricsFile(songId, ApiService.SaveLyricsFileRequest(content))
                            if (response.isSuccessful) {
                                sessionManager.removePendingLyricsUpload(songId)
                                anySuccess = true
                            }
                        } catch (e: Exception) {}
                    }
                }

                if (anySuccess) withContext(Dispatchers.Main) { onStateChanged?.invoke() }
                delay(15000) 
            }
        }
    }

    private fun checkAutoDelete(song: Song?, index: Int) {
        if (song == null || !sessionManager.isAutoDeleteEnabled()) return
        val localPath = queueLocalPaths.getOrNull(index)
        if (localPath == null && !downloadHelper.isDownloaded(song.id)) return

        serviceScope.launch {
            delay(300)
            try {
                withContext(Dispatchers.IO) { downloadHelper.removeDownload(song.id) }
            } catch (e: Exception) {}
            val mutablePaths = queueLocalPaths.toMutableList()
            if (index in mutablePaths.indices) {
                mutablePaths[index] = null
                queueLocalPaths = mutablePaths
            }
            withContext(Dispatchers.Main) { onStateChanged?.invoke() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        LocalLogger.log(
            this,
            "PlaybackService onStartCommand flags=$flags startId=$startId action=${intent?.action ?: "null"}"
        )
        return START_STICKY
    }

    fun setQueueAndPlay(songs: List<Song>, startIndex: Int, localPaths: List<String?>? = null) {
        if (songs.isEmpty() || startIndex !in songs.indices) return

        val requestedSong = songs.getOrNull(startIndex)

        // MODO DESCARGAS: si la canción elegida está descargada, la cola
        // ("a continuación") debe estar compuesta SOLO por canciones descargadas,
        // reordenadas por artista/género. Se regenera cada vez que se pulsa play
        // sobre una descarga; si luego se cambia a una canción normal, la cola
        // vuelve a armarse con la lógica habitual.
        if (requestedSong != null && downloadHelper.isDownloaded(requestedSong.id)) {
            val mix = buildDownloadMix(requestedSong, excludeIds = setOf(requestedSong.id), limit = 80)
            queue = listOf(requestedSong) + mix
            queueLocalPaths = queue.map { downloadHelper.getLocalFilePath(it.id) }
            currentIndex = 0
            playCurrentIndex()
            return
        }

        if (!ServerReachability.isOnline) {
            // Offline: la cola solo puede contener lo que ya está en el teléfono.
            val indexed = songs.withIndex().filter { (_, s) -> downloadHelper.isDownloaded(s.id) }
            if (indexed.isEmpty()) {
                LocalLogger.log(this, "setQueueAndPlay: sin conexión y nada de esta lista está descargado")
                return
            }
            val filteredSongs = indexed.map { it.value }
            val filteredPaths = indexed.map { (i, s) -> localPaths?.getOrNull(i) ?: downloadHelper.getLocalFilePath(s.id) }
            val newStartIndex = requestedSong?.let { req -> filteredSongs.indexOfFirst { it.id == req.id } }
                ?.takeIf { it >= 0 } ?: 0

            queue = filteredSongs
            queueLocalPaths = filteredPaths
            currentIndex = newStartIndex
            playCurrentIndex()
            return
        }

        queue = songs
        queueLocalPaths = localPaths ?: List(songs.size) { null }
        currentIndex = startIndex
        playCurrentIndex()
    }

    fun playSong(song: Song, localFilePath: String? = null) {
        setQueueAndPlay(listOf(song), 0, listOf(localFilePath))
    }

    private fun pruneQueueToDownloadedIfOffline() {
        // Se poda también cuando la canción actual es una descarga (modo
        // descargas), aunque haya conexión: en esa sesión el "siguiente" debe
        // ser solo de lo descargado.
        val downloadMode = currentSong?.let { downloadHelper.isDownloaded(it.id) } ?: false
        if ((ServerReachability.isOnline && !downloadMode) || queue.isEmpty()) return

        // IMPORTANTE: la canción en currentIndex + 1 puede ya estar cargada como
        // "siguiente item" dentro del ExoPlayer (ver playCurrentIndex/
        // handleAutoAdvance, que la añaden con player.addMediaItem para
        // reproducción sin cortes). Si la quitamos aquí de `queue` pero el
        // player ya la tiene en su lista interna, cuando el player transicione
        // a ella automáticamente, currentIndex++ pasará a apuntar a OTRA
        // canción distinta de la que realmente suena: eso es lo que provocaba
        // que la notificación (que lee metadata directamente del player)
        // mostrara el título correcto mientras el minireproductor y la pantalla
        // completa (que leen `currentSong`) mostraban otro. Por eso protegemos
        // ese slot y solo podamos desde dos canciones por delante en adelante.
        val protectedUpcomingIndex = currentIndex + 1
        val history = queue.take(protectedUpcomingIndex + 1)
        val prunablePortion = queue.drop(protectedUpcomingIndex + 1)
        val prunedPortion = prunablePortion.filter { downloadHelper.isDownloaded(it.id) }
        if (prunedPortion.size == prunablePortion.size) return

        val newQueue = history + prunedPortion
        queue = newQueue
        queueLocalPaths = newQueue.map { downloadHelper.getLocalFilePath(it.id) }
        onStateChanged?.invoke()
    }

    /** Mezcla aleatoria de todo lo descargado en el teléfono, excluyendo
     *  [excludeIds] (normalmente lo que ya sonó en esta sesión). La usa el
     *  botón "DJ Smart" cuando no hay conexión. */
    fun buildOfflineSmartMix(excludeIds: Set<String>, limit: Int = 40): List<Song> {
        return buildDownloadMix(currentSong, excludeIds, limit)
    }

    private fun toSong(d: DownloadedSong): Song = Song(
        id = d.id, title = d.title, artist = d.artist,
        album = null, year = null, duration = d.duration,
        bpm = d.bpm, key = d.key, liked = d.liked,
        hasCover = d.hasCover, hasLyrics = d.hasLyrics,
        isEpisode = d.isEpisode, lastPositionMs = 0L, genre = d.genre
    )

    /** True si la canción actual es una descarga (modo descargas). */
    fun isDownloadedMode(): Boolean =
        currentSong?.let { downloadHelper.isDownloaded(it.id) } ?: false

    /** Lista SOLO de canciones descargadas ordenadas por afinidad con la
     *  canción semilla: primero el mismo artista, luego las que coinciden en
     *  género y por último el resto (con algo de aleatoriedad). */
    fun buildDownloadMix(seed: Song?, excludeIds: Set<String>, limit: Int = 40): List<Song> {
        val seedGenres = seed?.genre.orEmpty().filter { it.isNotBlank() }
        val seedArtist = seed?.artist
        val downloads = downloadHelper.getDownloadedSongs()
            .filter { it.id !in excludeIds }
        if (downloads.isEmpty()) return emptyList()

        val scored = downloads.map { d ->
            val dg = d.genre.orEmpty().filter { it.isNotBlank() }
            var score = 0.0
            if (seedArtist != null && d.artist?.equals(seedArtist, ignoreCase = true) == true) score += 100.0
            if (seedGenres.isNotEmpty() && dg.isNotEmpty()) {
                score += dg.count { g -> seedGenres.any { seed -> seed.equals(g, ignoreCase = true) } } * 50.0
            }
            score += Math.random() * 20.0
            d to score
        }
        return scored
            .sortedByDescending { it.second }
            .take(limit)
            .map { toSong(it.first) }
    }

    fun playNext(song: Song) {
        if (queue.isEmpty()) { playSong(song); return }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        val insertIndex = currentIndex + 1
        mutableQueue.add(insertIndex, song)
        mutablePaths.add(insertIndex, null)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    fun addToQueue(song: Song) {
        if (queue.isEmpty()) { playSong(song); return }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.add(song)
        mutablePaths.add(null)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

    fun addListToQueue(songs: List<Song>) {
        if (queue.isEmpty()) { setQueueAndPlay(songs, 0); return }
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.addAll(songs)
        mutablePaths.addAll(List(songs.size) { null })
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        onStateChanged?.invoke()
    }

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
        currentIndex = when {
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        onStateChanged?.invoke()
    }

    fun removeFromQueue(index: Int) {
        if (index == currentIndex || index !in queue.indices) return
        val mutableQueue = queue.toMutableList()
        val mutablePaths = queueLocalPaths.toMutableList()
        mutableQueue.removeAt(index)
        mutablePaths.removeAt(index)
        queue = mutableQueue
        queueLocalPaths = mutablePaths
        if (index < currentIndex) currentIndex--
        onStateChanged?.invoke()
    }

    fun updateFullQueue(newSongs: List<Song>) {
        queue = newSongs
        queueLocalPaths = newSongs.map { downloadHelper.getLocalFilePath(it.id) }
        syncIndexById()
        onStateChanged?.invoke()
    }

    /**
     * Verifica si quedan menos de 10 canciones por delante en la cola.
     * Si es así, rellena con recomendaciones (online) o con descargas (offline).
     */
    private fun checkAndRefillQueue() {
        pruneQueueToDownloadedIfOffline()
        val pendingCount = queue.size - (currentIndex + 1)
        if (pendingCount >= 10) return

        serviceScope.launch {
            try {
                if (isDownloadedMode()) {
                    // Modo descargas: rellenar SIEMPRE solo con descargas (por
                    // artista/género), haya o no conexión.
                    val existingIds = queue.map { it.id }.toSet()
                    val mix = buildDownloadMix(currentSong, existingIds, limit = 15)
                    if (mix.isNotEmpty()) addListToQueue(mix)
                } else if (ServerReachability.isServerReachable()) {
                    // MODO ONLINE: Usar IA para obtener recomendaciones
                    val aiManager = AIRecommendationManager(
                        sessionManager,
                        AIWeightsStore(this@PlaybackService)
                    )
                    val recommendations = aiManager.getRecommendations(
                        limit = 15,
                        seedSong = currentSong
                    )
                    val existingIds = queue.map { it.id }.toSet()
                    val newSongs = recommendations.filter { it.id !in existingIds }
                    if (newSongs.isNotEmpty()) {
                        addListToQueue(newSongs)
                    }
                } else {
                    // MODO OFFLINE: Usar canciones descargadas
                    val downloads = downloadHelper.getDownloadedSongs()
                    if (downloads.size > 1) {
                        val existingIds = queue.map { it.id }.toSet()
                        val toAdd = downloads
                            .filter { it.id !in existingIds }
                            .shuffled()
                            .take(15)
                            .map { d -> toSong(d) }
                        if (toAdd.isNotEmpty()) {
                            addListToQueue(toAdd)
                        }
                    }
                }
            } catch (e: Exception) {
                LocalLogger.log(this@PlaybackService, "Error rellenando cola: ${e.message}")
            }
        }
    }

    /**
     * Sincroniza el currentIndex basándose en el ID de la canción actual.
     * Esto hace al sistema inmune a reordenamientos externos de la lista.
     */
    private fun syncIndexById() {
        val current = currentSong ?: return
        val newIndex = queue.indexOfFirst { it.id == current.id }
        if (newIndex != -1 && newIndex != currentIndex) {
            currentIndex = newIndex
            LocalLogger.log(this, "Queue sync: currentIndex actualizado a $newIndex por ID ${current.id}")
        }
    }

    private fun handleAutoAdvanceFromTransition() {
        fadeOutStartedForCurrentSong = false
        fadeJob?.cancel()
        if (crossfadeEnabled) {
            player?.volume = 0f
            startFade(from = 0f, to = 1f, durationMs = FADE_DURATION_MS)
        } else {
            player?.volume = 1f
        }
        
        // Cargar el siguiente item en el buffer del player si existe
        if (hasNext()) {
            val nextSong = queue[currentIndex + 1]
            val nextLocalPath = queueLocalPaths.getOrNull(currentIndex + 1)
            val nextMetaBuilder = MediaMetadata.Builder().setTitle(nextSong.title).setArtist(nextSong.artist)
            if (nextLocalPath == null) nextMetaBuilder.setArtworkUri(Uri.parse("$serverBaseUrl/cover/${nextSong.id}"))
            
            val baseAudioUrl = if (nextSong.isEpisode) "$serverBaseUrl/podcast-audio/" else "$serverBaseUrl/audio/"
            val nextMediaItem = MediaItem.Builder()
                .setMediaId(nextSong.id)
                .setUri(if (nextLocalPath != null) Uri.fromFile(File(nextLocalPath)) else Uri.parse("$baseAudioUrl${nextSong.id}"))
                .setMediaMetadata(nextMetaBuilder.build())
                .build()
            
            // Solo añadir si no es duplicado del que ya viene
            if ((player?.mediaItemCount ?: 0) <= 1) {
                player?.addMediaItem(nextMediaItem)
            }
        }
        onStateChanged?.invoke()
        updateMediaSessionCustomLayout()
    }


    fun next() {
        checkAndRefillQueue()
        val songToHandle = currentSong
        val indexToHandle = currentIndex
        if (currentIndex + 1 < queue.size) {
            currentIndex++
            playCurrentIndex()
            checkAutoDelete(songToHandle, indexToHandle)
        } else {
            player?.stop()
            player?.clearMediaItems()
            checkAutoDelete(songToHandle, indexToHandle)
            currentSong = null
            currentIndex = -1
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
    fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }

    private fun playCurrentIndex() {
        val song = queue.getOrNull(currentIndex) ?: return
        val localPath = queueLocalPaths.getOrNull(currentIndex)
        currentSong = song

        // Registrar la canción como "escuchada" (útil para el modo de
        // auto-descarga "solo novedades" / ciclo cuando ya las escuchó todas).
        sessionManager.recordPlayedSong(song.id)

        // Aplicar metadatos locales (ediciones del admin) antes de construir el MediaItem
        val displayedSong = SongAdminStore.applyTo(song)
        
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(displayedSong.title)
            .setArtist(displayedSong.artist)
            .setAlbumTitle(displayedSong.album)
            
        if (localPath == null) metaBuilder.setArtworkUri(Uri.parse("$serverBaseUrl/cover/${displayedSong.id}"))
        
        // Punto 2: Corregir URL de podcast (usar /podcast-audio en lugar de /audio si es episodio)
        val baseAudioUrl = if (displayedSong.isEpisode) "$serverBaseUrl/podcast-audio/" else "$serverBaseUrl/audio/"
        
        val mediaItem = MediaItem.Builder()
            .setMediaId(displayedSong.id)
            .setUri(if (localPath != null) Uri.fromFile(File(localPath)) else Uri.parse("$baseAudioUrl${displayedSong.id}"))
            .setMediaMetadata(metaBuilder.build())
            .build()
        player?.setMediaItem(mediaItem)
        if (hasNext()) {
            val nextSongRaw = queue[currentIndex + 1]
            val nextSong = SongAdminStore.applyTo(nextSongRaw)
            
            val nextLocalPath = queueLocalPaths.getOrNull(currentIndex + 1)
            val nextMetaBuilder = MediaMetadata.Builder().setTitle(nextSong.title).setArtist(nextSong.artist)
            if (nextLocalPath == null) nextMetaBuilder.setArtworkUri(Uri.parse("$serverBaseUrl/cover/${nextSong.id}"))
            
            // También para el siguiente item en el buffer
            val nextBaseUrl = if (nextSong.isEpisode) "$serverBaseUrl/podcast-audio/" else "$serverBaseUrl/audio/"
            val nextMediaItem = MediaItem.Builder()
                .setMediaId(nextSong.id)
                .setUri(if (nextLocalPath != null) Uri.fromFile(File(nextLocalPath)) else Uri.parse("$nextBaseUrl${nextSong.id}"))
                .setMediaMetadata(nextMetaBuilder.build())
                .build()
            player?.addMediaItem(nextMediaItem)
        }
        player?.prepare()
        
        // Continuar episodio donde se dejó
        if (displayedSong.isEpisode && displayedSong.lastPositionMs > 0) {
            player?.seekTo(displayedSong.lastPositionMs)
        }

        fadeOutStartedForCurrentSong = false
        fadeJob?.cancel()
        player?.volume = if (crossfadeEnabled) 0f else 1f
        player?.play()
        if (crossfadeEnabled) startFade(from = 0f, to = 1f, durationMs = FADE_DURATION_MS)
        updateMediaSessionCustomLayout()
        onStateChanged?.invoke()
    }

    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    /** Reanuda la reproduccion local (usado al cerrar una sesion de Chromecast). */
    fun play() { player?.play() }

    /** Pausa la reproduccion local (usado al iniciar una sesion de Chromecast). */
    fun pause() { player?.pause() }

    fun toggleShuffle() { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; onStateChanged?.invoke() } }
    fun toggleRepeat() { player?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; onStateChanged?.invoke() } }

    // ===== MODO RADIO (emisión en vivo / Radio de canción) =====

    /**
     * Inicia una "Radio de canción": limpia la cola próxima y la rellena con
     * recomendaciones basadas en la canción actual.
     */
    fun startSongRadio() {
        val seed = currentSong ?: return
        
        serviceScope.launch {
            try {
                // Mantener historia y canción actual
                val history = queue.take(currentIndex + 1)
                
                // Obtener recomendaciones
                val aiManager = AIRecommendationManager(sessionManager, AIWeightsStore(this@PlaybackService))
                val recommendations = aiManager.getRecommendations(limit = 40, seedSong = seed)
                
                val existingIds = history.map { it.id }.toSet()
                val filtered = recommendations.filter { it.id !in existingIds }
                
                updateFullQueue(history + filtered)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlaybackService, "Iniciando Radio de: ${seed.title}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                LocalLogger.log(this@PlaybackService, "Error iniciando Song Radio", e)
            }
        }
    }

    /**
     * Conecta [RadioManager] con este service:
     *  - HOST: publica qué suena (canción, posición, play/pausa) cada ~5 s.
     *  - OYENTE: carga la canción del host cuando cambia y corrige el desfase.
     */
    private fun setupRadio() {
        RadioManager.init(this)

        // HOST: estado que se publica al servidor
        RadioManager.hostStateProvider = {
            val song = currentSong
            if (song == null) null
            else RadioPublishRequest(
                hostId = sessionManager.getUserId(),
                hostName = sessionManager.getUsername(),
                songId = song.id,
                title = song.title,
                artist = song.artist,
                positionMs = player?.currentPosition ?: 0L,
                isPlaying = player?.isPlaying == true
            )
        }

        // OYENTE: el host cambió de canción → cargarla aquí y situarnos
        RadioManager.onListenerSongChange = { songId, _title, _artist, startMs, play ->
            loadRadioSong(songId, startMs, play)
        }

        // OYENTE: misma canción → corregir desfase y respetar play/pausa
        RadioManager.onListenerSync = { expectedPos, play ->
            syncWithRadioHost(expectedPos, play)
        }
    }

    /** true si este dispositivo está emitiendo su propia radio. */
    fun isRadioHosting(): Boolean = RadioManager.isHost

    /** true si este dispositivo está escuchando la radio de otro usuario. */
    fun isRadioListening(): Boolean = RadioManager.isListener

    /** HOST: empezar a emitir. Devuelve false si no hay canción en reproducción. */
    fun startRadioBroadcast(): Boolean {
        if (currentSong == null) return false
        RadioManager.startHosting()
        return true
    }

    /** HOST: dejar de emitir. */
    fun stopRadioBroadcast() {
        RadioManager.stopHosting()
    }

    /** OYENTE: dejar de escuchar (la música local sigue en pausa donde estaba). */
    fun leaveRadio() {
        RadioManager.stopListening()
    }

    /** Carga en el reproductor la canción que suena en la radio del host. */
    private fun loadRadioSong(songId: String, startMs: Long, play: Boolean) {
        serviceScope.launch {
            try {
                val response = RetrofitClient.api.getSongsByIds(songId, sessionManager.getUserId())
                val song = response.body()?.songs?.firstOrNull() ?: return@launch
                withContext(Dispatchers.Main) {
                    // La cola del oyente ES la canción que suena en la radio:
                    // cada cambio del host reemplaza la cola completa.
                    queue = listOf(song)
                    queueLocalPaths = listOf(null)
                    currentIndex = 0
                    playCurrentIndex()
                    if (startMs > 0) player?.seekTo(startMs)
                    if (!play) player?.pause()
                }
            } catch (e: Exception) {
                LocalLogger.log(this@PlaybackService, "Radio: no se pudo cargar la canción del host (${e.message})")
            }
        }
    }

    /** Corrige la deriva respecto al host y sincroniza play/pausa. */
    private fun syncWithRadioHost(expectedPos: Long, play: Boolean) {
        val p = player ?: return
        if (play) {
            if (!p.isPlaying && p.playbackState == Player.STATE_READY) p.play()
            val drift = kotlin.math.abs(p.currentPosition - expectedPos)
            if (drift > RADIO_DRIFT_TOLERANCE_MS) p.seekTo(expectedPos.coerceAtLeast(0L))
        } else {
            if (p.isPlaying) p.pause()
        }
    }

    private fun updateSongState(songId: String, updater: (Song) -> Song) {
        val queueIndex = queue.indexOfFirst { it.id == songId }
        if (queueIndex >= 0) {
            val updatedQueue = queue.toMutableList()
            updatedQueue[queueIndex] = updater(updatedQueue[queueIndex])
            queue = updatedQueue
        }
        val current = currentSong ?: return
        if (current.id == songId) {
            currentSong = updater(current)
        }
    }

    private fun syncLikeStateForSong(songId: String, liked: Boolean) {
        updateSongState(songId) { it.copy(liked = liked) }
    }

    fun toggleLike() {
        try {
            val song = currentSong ?: return
            val newLiked = !song.liked
            syncLikeStateForSong(song.id, newLiked)
            AIWeightsStore(this).reinforce(song.id, if (newLiked) 1f else -0.5f)
            downloadHelper.updateLiked(song.id, newLiked)
            updateMediaSessionCustomLayout()
            onStateChanged?.invoke()
            serviceScope.launch {
                try {
                    val response = RetrofitClient.api.likeSong(song.id, LikeRequest(sessionManager.getUserId(), newLiked))
                    if (!response.isSuccessful) sessionManager.addPendingLike(song.id, newLiked)
                } catch (e: Exception) { sessionManager.addPendingLike(song.id, newLiked) }
            }
        } catch (e: Exception) {
            LocalLogger.log(this, "toggleLike: fallo inesperado (${e.message})")
        }
    }

    fun dislikeCurrentSong() {
        try {
            val songToHide = currentSong ?: return
            AIWeightsStore(this).reinforce(songToHide.id, -1f)
            // Registrar localmente para que el admin pueda revisarla y, si quiere,
            // borrarla por completo del disco.
            SongAdminStore.recordDislikedSong(songToHide)
            serviceScope.launch {
                try {
                    val response = RetrofitClient.api.hideSong(songToHide.id, HideRequest(sessionManager.getUserId()))
                    if (!response.isSuccessful) sessionManager.addPendingDislike(songToHide.id)
                } catch (e: Exception) { sessionManager.addPendingDislike(songToHide.id) }
            }
            next()
        } catch (e: Exception) {
            LocalLogger.log(this, "dislikeCurrentSong: fallo inesperado (${e.message})")
        }
    }

    fun flushPendingLyricsUploads() {
        val pending = sessionManager.getPendingLyricsUploads()
        if (pending.isEmpty()) return
        serviceScope.launch {
            for ((songId, content) in pending) {
                try {
                    val response = RetrofitClient.api.saveLyricsFile(songId, ApiService.SaveLyricsFileRequest(content))
                    if (response.isSuccessful) sessionManager.removePendingLyricsUpload(songId)
                } catch (e: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        fadeTickerHandler.removeCallbacks(fadeTickerRunnable)
        fadeJob?.cancel()
        // Cerrar cualquier modo radio activo para no dejar la sesión colgada
        if (RadioManager.isHost) RadioManager.stopHosting()
        if (RadioManager.isListener) RadioManager.stopListening()
        serviceScope.cancel()
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}