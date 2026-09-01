package com.example.localfly

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.localfly.adapters.LyricLine
import com.example.localfly.adapters.LyricsAdapter
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.Song
import com.example.localfly.network.DeleteSongRequest
import com.example.localfly.network.SessionManager
import com.example.localfly.network.LrclibClient
import com.example.localfly.network.ServerReachability
import com.example.localfly.lyrics.LyricsTranslator
import com.example.localfly.ai.AIRecommendationManager
import com.example.localfly.dialogs.AddToPlaylistDialog
import com.example.localfly.utils.LocalLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NowPlayingActivity : AppCompatActivity() {

    private val serverBaseUrl = ApiConfig.BASE_URL

    private lateinit var ivCircularImage: ImageView
    private lateinit var ivBlurredBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var waveformSeekBar: com.masoudss.lib.WaveformSeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnLike: ImageButton
    private lateinit var btnDislike: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnDeleteSong: ImageButton
    private lateinit var btnAddToPlaylist: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnLyrics: ImageButton
    private lateinit var btnShowQueue: ImageButton
    private lateinit var rvUpcoming: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvUpcomingHeader: TextView
    private lateinit var btnSmartReorder: com.google.android.material.button.MaterialButton
    private lateinit var queueOverlay: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var ivQueueMiniThumb: ImageView
    private lateinit var tvQueueMiniInfo: TextView
    private lateinit var btnCloseQueueOverlay: ImageButton
    private var queueAdapter: QueueAdapter? = null
    private var queueItemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

    private var deleteConfirmArmed = false
    private val deleteConfirmResetRunnable = Runnable { resetDeleteConfirmState() }

    private var playbackService: PlaybackService? = null
    private var isBound = false
    private var userIsSeeking = false
    private var queueIsVisible = false

    private var currentQueueSongs: MutableList<Song> = mutableListOf()
    private var aiRecommendationsLoaded = false

    private var lyricsDialog: android.app.Dialog? = null
    private var lyricsAdapter: LyricsAdapter? = null
    private var lyricsUpdateJob: Job? = null

    // Vistas del mini-reproductor en el diálogo de letras
    private var ivLyricsMiniCover: ImageView? = null
    private var tvLyricsMiniTitle: TextView? = null
    private var tvLyricsMiniArtist: TextView? = null
    private var btnLyricsMiniPlayPause: ImageButton? = null
    private var sbLyricsMiniProgress: SeekBar? = null

    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var sessionManager: SessionManager
    private lateinit var amplituda: linc.com.amplituda.Amplituda

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            playbackService?.onStateChanged = { refreshUi() }
            if (queueIsVisible) updateQueueUI()
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        sessionManager = SessionManager(this)
        applyAppSettings()
        LocalLogger.log(this, "NowPlayingActivity iniciada")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        downloadHelper = DownloadManagerHelper.getInstance(this)
        amplituda = linc.com.amplituda.Amplituda(this)

        ivCircularImage = findViewById(R.id.ivCircularImage)
        ivBlurredBackground = findViewById(R.id.ivBlurredBackground)
        tvTitle = findViewById(R.id.tvFullTitle)
        tvArtist = findViewById(R.id.tvFullArtist)
        waveformSeekBar = findViewById(R.id.waveformSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnPlayPause = findViewById(R.id.btnFullPlayPause)
        btnLike = findViewById(R.id.btnFullLike)
        btnDislike = findViewById(R.id.btnFullDislike)
        btnPrev = findViewById(R.id.btnFullPrev)
        btnNext = findViewById(R.id.btnFullNext)
        btnDeleteSong = findViewById(R.id.btnDeleteSong)
        btnAddToPlaylist = findViewById(R.id.btnAddToPlaylist)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnLyrics = findViewById(R.id.btnLyrics)
        btnShowQueue = findViewById(R.id.btnShowQueue)
        rvUpcoming = findViewById(R.id.rvUpcomingSongs)
        tvUpcomingHeader = findViewById(R.id.tvUpcomingHeader)
        btnSmartReorder = findViewById(R.id.btnSmartReorder)
        queueOverlay = findViewById(R.id.queueOverlay)
        ivQueueMiniThumb = findViewById(R.id.ivQueueMiniThumb)
        tvQueueMiniInfo = findViewById(R.id.tvQueueMiniInfo)
        btnCloseQueueOverlay = findViewById(R.id.btnCloseQueueOverlay)

        queueOverlay.visibility = android.view.View.GONE
        btnCloseQueueOverlay.setOnClickListener { toggleQueue() }

        btnSmartReorder.setOnClickListener {
            applySmartReorder()
        }

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        btnPlayPause.setOnClickListener { playbackService?.togglePlayPause() }
        btnLike.setOnClickListener { playbackService?.toggleLike() }
        btnDislike.setOnClickListener {
            playbackService?.dislikeCurrentSong()
            if (playbackService?.currentSong == null) finish()
        }
        btnPrev.setOnClickListener { playbackService?.prev() }
        btnNext.setOnClickListener { playbackService?.next() }
        btnLyrics.setOnClickListener { showLyrics() }
        btnAddToPlaylist.setOnClickListener {
            val song = playbackService?.currentSong ?: return@setOnClickListener
            AddToPlaylistDialog.show(this, lifecycleScope, song, sessionManager)
        }
        btnDeleteSong.setOnClickListener { onDeleteSongClicked() }
        btnRepeat.setOnClickListener { playbackService?.toggleRepeat() }
        btnShowQueue.setOnClickListener { toggleQueue() }

        tvArtist.setOnClickListener {
            val artistName = playbackService?.currentSong?.artist
            if (!artistName.isNullOrBlank()) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = "com.example.localfly.ACTION_OPEN_ARTIST"
                    putExtra("artist_name", artistName)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        waveformSeekBar.onProgressChanged = object : com.masoudss.lib.SeekBarOnProgressChanged {
            override fun onProgressChanged(waveformSeekBar: com.masoudss.lib.WaveformSeekBar, progress: Float, fromUser: Boolean) {
                if (fromUser) tvCurrentTime.text = formatTime(progress.toLong())
            }
        }
        
        // Listener para el inicio y fin del scroll (seek)
        waveformSeekBar.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    userIsSeeking = true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    userIsSeeking = false
                    playbackService?.seekTo(waveformSeekBar.progress.toLong())
                }
            }
            view.performClick()
            false
        }

        setupQueue()

        if (playbackService != null) {
            refreshUi()
        }
    }

    private fun applyAppSettings() {
        // 1. Aplicar Tema de color
        when (sessionManager.getAppColor()) {
            "Azul" -> setTheme(R.style.Theme_Localfly_Blue)
            "Rojo" -> setTheme(R.style.Theme_Localfly_Red)
            "Púrpura" -> setTheme(R.style.Theme_Localfly_Purple)
            else -> setTheme(R.style.Theme_Localfly) // Verde por defecto
        }

        // 2. Aplicar Tamaño de fuente
        val scale = when (sessionManager.getTextSize()) {
            "Pequeño" -> 0.85f
            "Grande" -> 1.15f
            else -> 1.0f
        }
        
        val configuration = resources.configuration
        configuration.fontScale = scale
        val metrics = resources.displayMetrics
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        @Suppress("DEPRECATION")
        metrics.scaledDensity = configuration.fontScale * metrics.density
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, metrics)
    }

    private fun toggleQueue() {
        queueIsVisible = !queueIsVisible
        if (queueIsVisible) {
            openQueueOverlay()
        } else {
            closeQueueOverlay()
        }
    }

    private fun openQueueOverlay() {
        val song = playbackService?.currentSong
        if (song != null) {
            tvQueueMiniInfo.text = "Reproduciendo ahora: ${song.title}"
            Glide.with(this)
                .load("$serverBaseUrl/cover/${song.id}")
                .placeholder(R.drawable.ic_music_placeholder)
                .centerCrop()
                .into(ivQueueMiniThumb)
        }

        queueOverlay.visibility = android.view.View.VISIBLE
        queueOverlay.translationY = queueOverlay.height.takeIf { it > 0 }
            ?.toFloat() ?: resources.displayMetrics.heightPixels.toFloat()
        queueOverlay.animate()
            .translationY(0f)
            .setDuration(280)
            .start()

        // Encoger y subir la carátula, como en Spotify, mientras sube la cola
        ivCircularImage.animate()
            .scaleX(0.35f)
            .scaleY(0.35f)
            .translationY(-260f)
            .alpha(0.6f)
            .setDuration(280)
            .start()

        btnShowQueue.rotation = 0f
        setupQueue()
    }

    private fun closeQueueOverlay() {
        queueOverlay.animate()
            .translationY(queueOverlay.height.toFloat())
            .setDuration(220)
            .withEndAction { queueOverlay.visibility = android.view.View.GONE }
            .start()

        ivCircularImage.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .start()

        btnShowQueue.rotation = 180f
    }

    private fun setupQueue() {
        val service = playbackService ?: return

        lifecycleScope.launch {
            // Si hay menos de 10 canciones pendientes, rellenar con IA y
            // AÑADIRLAS de verdad a la cola real (no solo mostrarlas).
            val pendingCount = service.queue.size - (service.currentIndex + 1)
            if (pendingCount < 10) {
                try {
                    val aiManager = AIRecommendationManager(sessionManager, com.example.localfly.ai.AIWeightsStore(this@NowPlayingActivity))
                    val recommendations = aiManager.getRecommendations(
                        limit = 10 - pendingCount,
                        seedSong = service.currentSong
                    )
                    val existingIds = service.queue.map { it.id }.toSet()
                    val filteredRecs = recommendations.filter { it.id !in existingIds }
                    if (filteredRecs.isNotEmpty()) {
                        service.addListToQueue(filteredRecs)
                    }
                } catch (e: Exception) {
                    // Fallback silencioso si falla la IA
                }
            }

            val upcomingSongs = service.queue.drop(service.currentIndex + 1).toMutableList()

            val existingAdapter = queueAdapter
            if (existingAdapter != null && rvUpcoming.adapter === existingAdapter) {
                existingAdapter.updateSongs(upcomingSongs)
                return@launch
            }

            val adapter = QueueAdapter(
                songs = upcomingSongs,
                onDragHandleTouch = { holder -> queueItemTouchHelper?.startDrag(holder) },
                onMove = { from, to ->
                    // Los índices de la vista son relativos a "próximas
                    // canciones"; sumamos currentIndex + 1 para obtener el
                    // índice absoluto real dentro de service.queue.
                    val offset = service.currentIndex + 1
                    service.moveQueueItem(from + offset, to + offset)
                },
                onRemove = { position ->
                    val offset = service.currentIndex + 1
                    service.removeFromQueue(position + offset)
                }
            )
            queueAdapter = adapter
            rvUpcoming.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@NowPlayingActivity)
            rvUpcoming.adapter = adapter

            val callback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            ) {
                override fun onMove(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean {
                    adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun clearView(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    // onItemMove ya reordenó la lista visual; aquí confirmamos
                    // el cambio real usando la posición final del holder.
                    val finalPosition = viewHolder.bindingAdapterPosition
                    if (finalPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        adapter.confirmMove(lastDragFrom, finalPosition)
                    }
                }

                private var lastDragFrom = -1

                override fun onSelectedChanged(
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                    actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        lastDragFrom = viewHolder.bindingAdapterPosition
                    }
                }

                override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                    adapter.onItemDismiss(viewHolder.bindingAdapterPosition)
                }
            }

            queueItemTouchHelper?.attachToRecyclerView(null)
            queueItemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(callback)
            queueItemTouchHelper?.attachToRecyclerView(rvUpcoming)
        }
    }

    private fun updateQueueUI() {
        setupQueue()
    }

    private fun animateSkeleton(view: android.view.View) {
        view.alpha = 0.4f
        view.animate()
            .alpha(0.8f)
            .setDuration(1000)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                if (view.visibility == android.view.View.VISIBLE) {
                    animateSkeleton(view)
                }
            }
            .start()
    }

    private fun applySmartReorder() {
        val service = playbackService ?: return
        if (service.queue.isEmpty()) return

        lifecycleScope.launch {
            Toast.makeText(this@NowPlayingActivity, "🤖 IA mezclando tu sesión...", Toast.LENGTH_SHORT).show()
            
            // Reordenar solo las canciones PRÓXIMAS (no la que suena ni las pasadas)
            val currentIdx = service.currentIndex
            val history = service.queue.take(currentIdx + 1)
            val upcoming = service.queue.drop(currentIdx + 1)
            
            val reorderedUpcoming = com.example.localfly.utils.SmartReorderUtils.reorder(upcoming)
            
            service.updateFullQueue(history + reorderedUpcoming)
            Toast.makeText(this@NowPlayingActivity, "Mezcla Smart DJ aplicada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleteSongClicked() {
        if (deleteConfirmArmed) {
            progressHandler.removeCallbacks(deleteConfirmResetRunnable)
            resetDeleteConfirmState()
            deleteCurrentSong()
        } else {
            deleteConfirmArmed = true
            btnDeleteSong.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FFA500")
            )
            Toast.makeText(this, "Pulsa de nuevo para eliminar esta canción permanentemente", Toast.LENGTH_SHORT).show()
            progressHandler.postDelayed(deleteConfirmResetRunnable, 3000)
        }
    }

    private fun resetDeleteConfirmState() {
        deleteConfirmArmed = false
        btnDeleteSong.imageTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#FF4444")
        )
    }

    private fun deleteCurrentSong() {
        val service = playbackService ?: return
        val song = service.currentSong ?: return
        val sessionManager = SessionManager(this)

        btnDeleteSong.isEnabled = false

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.deleteSong(
                    DeleteSongRequest(id = song.id, userId = sessionManager.getUserId())
                )
                if (response.isSuccessful) {
                    val downloadHelper = DownloadManagerHelper.getInstance(this@NowPlayingActivity)
                    if (downloadHelper.isDownloaded(song.id)) {
                        withContext(Dispatchers.IO) { downloadHelper.removeDownload(song.id) }
                    }
                    Toast.makeText(this@NowPlayingActivity, "Canción eliminada", Toast.LENGTH_SHORT).show()
                    if (service.hasNext()) {
                        service.next()
                    } else {
                        finish()
                    }
                } else {
                    Toast.makeText(this@NowPlayingActivity, "No se pudo eliminar la canción", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NowPlayingActivity, "Sin conexión: no se pudo eliminar la canción", Toast.LENGTH_SHORT).show()
            } finally {
                btnDeleteSong.isEnabled = true
            }
        }
    }

    private fun showLyrics() {
        val song = playbackService?.currentSong ?: return
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_lyrics)
        lyricsDialog = dialog

        val rvLyrics = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvLyrics)
        val progressBar = dialog.findViewById<android.widget.ProgressBar>(R.id.progressLyrics)
        val skeleton = dialog.findViewById<android.view.View>(R.id.llLyricsSkeleton)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseLyrics)
        
        // Enlazar mini-reproductor del diálogo
        ivLyricsMiniCover = dialog.findViewById(R.id.ivLyricsMiniCover)
        tvLyricsMiniTitle = dialog.findViewById(R.id.tvLyricsMiniTitle)
        tvLyricsMiniArtist = dialog.findViewById(R.id.tvLyricsMiniArtist)
        btnLyricsMiniPlayPause = dialog.findViewById(R.id.btnLyricsMiniPlayPause)
        sbLyricsMiniProgress = dialog.findViewById(R.id.sbLyricsMiniProgress)
        val btnPrev = dialog.findViewById<ImageButton>(R.id.btnLyricsMiniPrev)
        val btnNext = dialog.findViewById<ImageButton>(R.id.btnLyricsMiniNext)

        btnLyricsMiniPlayPause?.setOnClickListener { playbackService?.togglePlayPause() }
        btnPrev?.setOnClickListener { playbackService?.prev() }
        btnNext?.setOnClickListener { playbackService?.next() }
        
        // Actualizar datos iniciales del mini-reproductor
        refreshLyricsMiniPlayer()

        btnClose.setOnClickListener { 
            lyricsUpdateJob?.cancel()
            lyricsDialog = null
            resetLyricsMiniPlayerViews()
            dialog.dismiss() 
        }

        lifecycleScope.launch {
            try {
                skeleton.visibility = android.view.View.VISIBLE
                progressBar.visibility = android.view.View.VISIBLE
                
                // Animación de pulso para el skeleton
                animateSkeleton(skeleton)

                var lines = fetchLyrics(song)
                
                // Si no hay líneas pero estamos online, intentar forzar descarga desde LRCLIB y guardar localmente
                if ((lines == null || lines.isEmpty()) && ServerReachability.isServerReachable()) {
                    lines = fetchLyrics(song)
                }

                progressBar.visibility = android.view.View.GONE
                skeleton.visibility = android.view.View.GONE

                if (lines != null && lines.isNotEmpty()) {
                    // Limpiar timestamps residuales de las líneas si por algún motivo se colaron
                    val timestampRegex = Regex("\\[\\d{1,2}:\\d{2}([.:]\\d{2,3})?\\]")
                    val cleanedLines = lines.map { it.copy(content = it.content.replace(timestampRegex, "").trim()) }
                    
                    // Si el archivo local no existe pero tenemos la letra (la acabamos de bajar de LRCLIB),
                    // la guardamos para la próxima vez offline.
                    if (downloadHelper.isDownloaded(song.id)) {
                        saveLyricsLocallyIfMissing(song, cleanedLines)
                    }

                    val finalLines = try {
                        LyricsTranslator.translateIfEnglish(cleanedLines)
                    } catch (e: Exception) {
                        cleanedLines
                    }
                    
                    lyricsAdapter = LyricsAdapter(finalLines) { clickedLine ->
                        if (clickedLine.timeMs > 0) {
                            playbackService?.seekTo(clickedLine.timeMs)
                        }
                    }
                    rvLyrics.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@NowPlayingActivity)
                    rvLyrics.adapter = lyricsAdapter
                    startLyricsUpdateLoop(rvLyrics)
                } else {
                    Toast.makeText(this@NowPlayingActivity, "No se encontró la letra (revisando fuentes...)", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NowPlayingActivity, "Error al cargar la letra", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun saveLyricsLocallyIfMissing(song: Song, lines: List<LyricLine>) {
        val lrcFile = File(filesDir, "downloads/${song.id}.lrc")
        if (lrcFile.exists()) return

        // Reconstruir texto LRC simple o texto plano
        val content = lines.joinToString("\n") { 
            val totalSeconds = it.timeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val ms = (it.timeMs % 1000) / 10
            String.format(Locale.getDefault(), "[%02d:%02d.%02d]%s", minutes, seconds, ms, it.content)
        }
        try {
            lrcFile.writeText(content)
            Toast.makeText(this, "Letra guardada para uso offline", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { }
    }

    private fun startLyricsUpdateLoop(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        lyricsUpdateJob?.cancel()
        lyricsUpdateJob = lifecycleScope.launch {
            while (true) {
                val currentTime = playbackService?.getProgressMs() ?: 0L
                val activePos = lyricsAdapter?.updateActiveLine(currentTime) ?: -1
                if (activePos != -1) {
                    recyclerView.smoothScrollToPosition(activePos)
                }
                delay(300)
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private suspend fun fetchLyrics(song: Song): List<LyricLine>? = withContext(Dispatchers.IO) {
        // 0. Archivo local (canción descargada). Puede ser LRC real
        //    (con timestamps) o texto plano guardado previamente.
        val localLrc = File(filesDir, "downloads/${song.id}.lrc")
        if (localLrc.exists()) {
            try {
                val content = localLrc.readText()
                if (content.isNotBlank() && !isHtml(content)) {
                    val parsed = parseLrcToList(content)
                    if (parsed.isNotEmpty()) return@withContext parsed
                    val plain = plainTextToLines(content)
                    if (plain.isNotEmpty()) return@withContext plain
                }
            } catch (e: Exception) { }
        }

        // 1. Servidor: preferir syncedLines (ya vienen parseadas, en
        //    segundos) y el texto plano solo como respaldo.
        try {
            val response = RetrofitClient.api.getLyrics(song.id)
            if (response.isSuccessful) {
                val body = response.body()
                val synced = body?.syncedLines
                if (!synced.isNullOrEmpty()) {
                    val lines = synced
                        .sortedBy { it.time }
                        .map { LyricLine((it.time * 1000).toLong(), it.text.trim()) }
                        .filter { it.content.isNotEmpty() }
                    if (lines.isNotEmpty()) return@withContext lines
                }
                val plain = body?.lyrics
                if (!plain.isNullOrBlank() && !isHtml(plain)) {
                    val plainLines = plainTextToLines(plain)
                    if (plainLines.isNotEmpty()) return@withContext plainLines
                }
            }
        } catch (e: Exception) { }

        // 2. Servidor no alcanzable (o sin resultado): buscar directo en
        //    LRCLIB desde el móvil, si hay internet.
        try {
            val trackName = song.title
            val artistName = song.artist
            val lrclibResponse = LrclibClient.api.getLyrics(trackName, artistName)
            if (lrclibResponse.isSuccessful) {
                val result = lrclibResponse.body()
                val synced = result?.syncedLyrics
                if (!synced.isNullOrBlank()) {
                    val lines = parseLrcToList(synced)
                    if (lines.isNotEmpty()) {
                        runOnUiThread { Toast.makeText(this@NowPlayingActivity, "Letra encontrada en internet", Toast.LENGTH_SHORT).show() }
                        queueLyricsUploadIfServerHasNone(song.id, synced)
                        return@withContext lines
                    }
                }
                val plain = result?.plainLyrics
                if (!plain.isNullOrBlank()) {
                    val plainLines = plainTextToLines(plain)
                    if (plainLines.isNotEmpty()) {
                        runOnUiThread { Toast.makeText(this@NowPlayingActivity, "Letra encontrada en internet", Toast.LENGTH_SHORT).show() }
                        queueLyricsUploadIfServerHasNone(song.id, plain)
                        return@withContext plainLines
                    }
                }
            }
        } catch (e: Exception) { }

        // 3. Último recurso, heredado de versiones antiguas de la
        //    biblioteca (rara vez encuentra algo).
        val variants = mutableListOf<String>()
        variants.add(song.title)
        song.artist?.let { variants.add("$it - ${song.title}") }
        variants.add(song.title.lowercase(Locale.getDefault()))
        song.artist?.let { variants.add("${it.lowercase(Locale.getDefault())} - ${song.title.lowercase(Locale.getDefault())}") }

        val client = OkHttpClient()
        for (variant in variants.distinct()) {
            try {
                val encoded = java.net.URLEncoder.encode(variant, "UTF-8").replace("+", "%20")
                val url = "${ApiConfig.BASE_URL}/resources/$encoded.lrc"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string()
                        if (!bodyString.isNullOrBlank() && !isHtml(bodyString)) {
                            val parsed = parseLrcToList(bodyString)
                            if (parsed.isNotEmpty()) return@withContext parsed
                            val plain = plainTextToLines(bodyString)
                            if (plain.isNotEmpty()) return@withContext plain
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        null
    }

    private fun plainTextToLines(text: String): List<LyricLine> {
        val timestampRegex = Regex("\\[\\d{1,2}:\\d{2}([.:]\\d{2,3})?\\]")
        return text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(0L, it.replace(timestampRegex, "").trim()) }
    }

    /**
     * Guarda como pendiente de subir SOLO si el servidor no tenía ya esta
     * letra (evita subir de más si el servidor sí la tenía pero, por lo
     * que sea, esa petición en concreto falló). Comprobación best-effort:
     * si no se puede saber, se guarda igual — subir de más no hace daño,
     * el servidor simplemente sobrescribe el mismo archivo .lrc.
     */
    private suspend fun queueLyricsUploadIfServerHasNone(songId: String, content: String) {
        try {
            val sessionManager = SessionManager(this@NowPlayingActivity)
            sessionManager.addPendingLyricsUpload(songId, content)
            // Si el servidor está disponible ahora mismo, intentar subir ya
            // en vez de esperar al próximo evento de reconexión.
            if (ServerReachability.isServerReachable()) {
                playbackService?.flushPendingLyricsUploads()
            }
        } catch (e: Exception) { }
    }

    private fun parseLrcToList(lrc: String): List<LyricLine> {
        val lines = lrc.split("\n")
        val lyricLines = mutableListOf<LyricLine>()
        // Regex para marcas de tiempo: [00:00.00], [00:00], [00:00:00], etc.
        val timestampRegex = Regex("\\[(\\d{1,2}):(\\d{2})([.:](\\d{2,3}))?\\]")
        // Regex para etiquetas de metadatos: [ar: artist], [al: album], [ti: title], etc.
        val metadataRegex = Regex("\\[[a-z]{2,}:.*\\]")
        
        for (line in lines) {
            val match = timestampRegex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msPart = match.groupValues[4].let { 
                    if (it.isEmpty()) 0L 
                    else if (it.length == 2) it.toLong() * 10 
                    else it.toLong()
                }
                val timeMs = (min * 60 * 1000) + (sec * 1000) + msPart
                
                var content = line.replace(timestampRegex, "").trim()
                content = content.replace(metadataRegex, "").trim()
                
                if (content.isNotEmpty()) {
                    lyricLines.add(LyricLine(timeMs, content))
                }
            }
        }
        return lyricLines.sortedBy { it.timeMs }
    }

    private fun isHtml(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("<!DOCTYPE", ignoreCase = true) ||
               t.startsWith("<html", ignoreCase = true) ||
               t.startsWith("<head", ignoreCase = true) ||
               t.startsWith("<body", ignoreCase = true) ||
               t.startsWith("<div", ignoreCase = true)
    }

    private fun resetLyricsMiniPlayerViews() {
        ivLyricsMiniCover = null
        tvLyricsMiniTitle = null
        tvLyricsMiniArtist = null
        btnLyricsMiniPlayPause = null
        sbLyricsMiniProgress = null
    }

    private fun refreshLyricsMiniPlayer() {
        val song = playbackService?.currentSong ?: return
        
        tvLyricsMiniTitle?.text = song.title
        tvLyricsMiniArtist?.text = song.artist ?: "Artista desconocido"
        
        val isPlaying = playbackService?.player?.isPlaying == true
        btnLyricsMiniPlayPause?.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        
        ivLyricsMiniCover?.let { iv ->
            Glide.with(this)
                .load("$serverBaseUrl/cover/${song.id}")
                .placeholder(R.drawable.ic_music_placeholder)
                .centerCrop()
                .into(iv)
        }
        
        val duration = playbackService?.getDurationMs() ?: 0L
        if (duration > 0) {
            sbLyricsMiniProgress?.max = duration.toInt()
        }
    }

    private fun refreshUi() {
        val song = playbackService?.currentSong ?: run { finish(); return }
        tvTitle.text = toTitleCase(song.title)
        tvArtist.text = toTitleCase(song.artist) ?: "Artista desconocido"
        
        // Actualizar mini-reproductor de letras si el diálogo está abierto
        refreshLyricsMiniPlayer()

        // Cargar waveform
        loadWaveform(song)

        val artistEncoded = java.net.URLEncoder.encode(song.artist ?: "", "UTF-8").replace("+", "%20")
        val artistImageUrl = "$serverBaseUrl/artist-cover/$artistEncoded"
        val albumImageUrl = "$serverBaseUrl/cover/${song.id}"
        Glide.with(this).load(albumImageUrl).placeholder(R.drawable.ic_music_placeholder).centerCrop().override(100, 100).into(ivBlurredBackground)
        Glide.with(this).load(artistImageUrl).placeholder(R.drawable.ic_music_placeholder).error(Glide.with(this).load(albumImageUrl).centerCrop()).centerCrop().into(ivCircularImage)
        btnLike.setImageResource(if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off)
        val isPlaying = playbackService?.player?.isPlaying == true
        btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        btnRepeat.imageTintList = android.content.res.ColorStateList.valueOf(if (playbackService?.player?.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) android.graphics.Color.parseColor("#1DB954") else android.graphics.Color.WHITE)
        btnPrev.isEnabled = playbackService?.hasPrev() == true
        btnPrev.alpha = if (playbackService?.hasPrev() == true) 1f else 0.4f
        btnNext.isEnabled = playbackService?.hasNext() == true
        btnNext.alpha = if (playbackService?.hasNext() == true) 1f else 0.4f
        if (queueIsVisible) updateQueueUI()
    }

    private fun updateProgress() {
        val service = playbackService ?: return
        val duration = service.getDurationMs()
        val progress = service.getProgressMs()
        if (duration > 0) {
            waveformSeekBar.maxProgress = duration.toFloat()
            tvTotalTime.text = formatTime(duration)
        }
        if (!userIsSeeking) {
            waveformSeekBar.progress = progress.toFloat()
            tvCurrentTime.text = formatTime(progress)
            sbLyricsMiniProgress?.progress = progress.toInt()
        }
    }

    private fun toTitleCase(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return text.lowercase(Locale.getDefault()).split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }
    }

    private fun loadWaveform(song: Song) {
        val localPath = downloadHelper.getLocalFilePath(song.id)
        if (localPath != null) {
            amplituda.processAudio(localPath).get(
                { result ->
                    runOnUiThread {
                        waveformSeekBar.setSampleFrom(result.amplitudesAsList().toIntArray())
                    }
                },
                { error ->
                    runOnUiThread {
                        val randomAmplitudes = IntArray(100) { (10..100).random() }
                        waveformSeekBar.setSampleFrom(randomAmplitudes)
                    }
                }
            )
        } else {
            val randomAmplitudes = IntArray(100) { (10..100).random() }
            waveformSeekBar.setSampleFrom(randomAmplitudes)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, PlaybackService::class.java).apply { action = PlaybackService.ACTION_LOCAL_BIND }.also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
    override fun onResume() {
        super.onResume()
        progressHandler.post(progressRunnable)
    }
    override fun onPause() {
        super.onPause()
        progressHandler.removeCallbacks(progressRunnable)
    }
    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
