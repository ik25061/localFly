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
import com.example.localfly.ai.AIRecommendationManager
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class NowPlayingActivity : AppCompatActivity() {

    private val serverBaseUrl = ApiConfig.BASE_URL

    private lateinit var ivCircularImage: ImageView
    private lateinit var ivBlurredBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnLike: ImageButton
    private lateinit var btnDislike: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnDeleteSong: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnLyrics: ImageButton
    private lateinit var btnShowQueue: ImageButton
    private lateinit var rvUpcoming: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvUpcomingHeader: TextView
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        ivCircularImage = findViewById(R.id.ivCircularImage)
        ivBlurredBackground = findViewById(R.id.ivBlurredBackground)
        tvTitle = findViewById(R.id.tvFullTitle)
        tvArtist = findViewById(R.id.tvFullArtist)
        seekBar = findViewById(R.id.seekBarProgress)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnPlayPause = findViewById(R.id.btnFullPlayPause)
        btnLike = findViewById(R.id.btnFullLike)
        btnDislike = findViewById(R.id.btnFullDislike)
        btnPrev = findViewById(R.id.btnFullPrev)
        btnNext = findViewById(R.id.btnFullNext)
        btnDeleteSong = findViewById(R.id.btnDeleteSong)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnLyrics = findViewById(R.id.btnLyrics)
        btnShowQueue = findViewById(R.id.btnShowQueue)
        rvUpcoming = findViewById(R.id.rvUpcomingSongs)
        tvUpcomingHeader = findViewById(R.id.tvUpcomingHeader)
        queueOverlay = findViewById(R.id.queueOverlay)
        ivQueueMiniThumb = findViewById(R.id.ivQueueMiniThumb)
        tvQueueMiniInfo = findViewById(R.id.tvQueueMiniInfo)
        btnCloseQueueOverlay = findViewById(R.id.btnCloseQueueOverlay)

        queueOverlay.visibility = android.view.View.GONE
        btnCloseQueueOverlay.setOnClickListener { toggleQueue() }

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

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvCurrentTime.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userIsSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsSeeking = false
                playbackService?.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })
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
                    val sessionManager = SessionManager(this@NowPlayingActivity)
                    val aiManager = AIRecommendationManager(sessionManager)
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
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseLyrics)
        btnClose.setOnClickListener { 
            lyricsUpdateJob?.cancel()
            dialog.dismiss() 
        }

        lifecycleScope.launch {
            try {
                val lines = fetchLyrics(song)
                if (lines != null && lines.isNotEmpty()) {
                    lyricsAdapter = LyricsAdapter(lines)
                    rvLyrics.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@NowPlayingActivity)
                    rvLyrics.adapter = lyricsAdapter
                    startLyricsUpdateLoop(rvLyrics)
                } else {
                    Toast.makeText(this@NowPlayingActivity, "No hay letra disponible", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NowPlayingActivity, "Error al cargar la letra", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
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

    private suspend fun fetchLyrics(song: Song): List<LyricLine>? = withContext(Dispatchers.IO) {
        var rawLyrics: String? = null

        // 0. Intentar cargar archivo local si la canción está descargada
        val localLrc = File(filesDir, "downloads/${song.id}.lrc")
        if (localLrc.exists()) {
            try {
                rawLyrics = localLrc.readText()
            } catch (e: Exception) { }
        }

        if (rawLyrics == null || isHtml(rawLyrics)) {
            // 1. Intentar por el endpoint oficial de la API
            try {
                val response = RetrofitClient.api.getLyrics(song.id)
                if (response.isSuccessful && response.body()?.lyrics != null) {
                    rawLyrics = response.body()!!.lyrics!!
                }
            } catch (e: Exception) { }
        }

        if (rawLyrics == null || isHtml(rawLyrics)) {
            // 2. Fallback: Buscar archivo .lrc directo en /resources/
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
                                rawLyrics = bodyString
                                break
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        val lyricsToParse = rawLyrics
        if (lyricsToParse != null && !isHtml(lyricsToParse)) {
            return@withContext parseLrcToList(lyricsToParse)
        }
        null
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

    private fun refreshUi() {
        val song = playbackService?.currentSong ?: run { finish(); return }
        tvTitle.text = toTitleCase(song.title)
        tvArtist.text = toTitleCase(song.artist) ?: "Artista desconocido"
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
            seekBar.max = duration.toInt()
            tvTotalTime.text = formatTime(duration)
        }
        if (!userIsSeeking) {
            seekBar.progress = progress.toInt()
            tvCurrentTime.text = formatTime(progress)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun toTitleCase(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return text.lowercase(Locale.getDefault()).split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, PlaybackService::class.java).also { intent ->
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