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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnLyrics: ImageButton
    private lateinit var btnShowQueue: ImageButton
    private lateinit var rvUpcoming: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvUpcomingHeader: TextView

    private var playbackService: PlaybackService? = null
    private var isBound = false
    private var userIsSeeking = false
    private var queueIsVisible = false

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
            if (queueIsVisible) setupQueue()
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
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnLyrics = findViewById(R.id.btnLyrics)
        btnShowQueue = findViewById(R.id.btnShowQueue)
        rvUpcoming = findViewById(R.id.rvUpcomingSongs)
        tvUpcomingHeader = findViewById(R.id.tvUpcomingHeader)

        rvUpcoming.visibility = android.view.View.GONE
        tvUpcomingHeader.visibility = android.view.View.GONE

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
        btnShuffle.setOnClickListener { playbackService?.toggleShuffle() }
        btnRepeat.setOnClickListener { playbackService?.toggleRepeat() }
        btnShowQueue.setOnClickListener { toggleQueue() }

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
        rvUpcoming.visibility = if (queueIsVisible) android.view.View.VISIBLE else android.view.View.GONE
        tvUpcomingHeader.visibility = if (queueIsVisible) android.view.View.VISIBLE else android.view.View.GONE
        btnShowQueue.rotation = if (queueIsVisible) 180f else 0f
        if (queueIsVisible) setupQueue()
    }

    private fun setupQueue() {
        val service = playbackService ?: return
        val currentIdx = service.currentIndex
        val upcomingSongs = service.queue.drop(currentIdx + 1)
        
        rvUpcoming.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvUpcoming.adapter = QueueAdapter(upcomingSongs) { song, action ->
            when (action) {
                QueueAction.PLAY_NEXT -> service.playNext(song)
                QueueAction.ADD_TO_END -> service.addToQueue(song)
            }
            setupQueue()
        }
    }

    private fun showLyrics() {
        val song = playbackService?.currentSong ?: return
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_lyrics)
        val tvContent = dialog.findViewById<TextView>(R.id.tvLyricsContent)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseLyrics)
        btnClose.setOnClickListener { dialog.dismiss() }
        lifecycleScope.launch {
            try {
                val lyrics = fetchLyrics(song)
                tvContent.text = lyrics ?: "No hay letra disponible para esta canción"
            } catch (e: Exception) {
                tvContent.text = "Error al cargar la letra"
            }
        }
        dialog.show()
    }

    private suspend fun fetchLyrics(song: Song): String? = withContext(Dispatchers.IO) {
        // Primero intentamos por el endpoint de la API (más fiable si existe)
        try {
            val response = RetrofitClient.api.getLyrics(song.id)
            if (response.isSuccessful && response.body()?.lyrics != null) {
                return@withContext parseLrc(response.body()!!.lyrics!!)
            }
        } catch (e: Exception) {
            // Silencioso, pasamos al fallback
        }

        // Fallback: Intentar cargar directamente el archivo .lrc por el título (como pide el usuario)
        try {
            val encodedTitle = java.net.URLEncoder.encode(song.title, "UTF-8").replace("+", "%20")
            val lyricsUrl = "${ApiConfig.BASE_URL}/resources/$encodedTitle.lrc"
            
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(lyricsUrl).build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val rawLyrics = response.body?.string()
                    if (!rawLyrics.isNullOrBlank()) {
                        return@withContext parseLrc(rawLyrics)
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback fallido
        }
        null
    }

    private fun parseLrc(lrc: String): String {
        val lines = lrc.split("\n")
        val parsed = StringBuilder()
        // Regex para marcas de tiempo: [00:00.00], [00:00], [00:00:00], etc.
        val timestampRegex = Regex("\\[\\d{1,2}:\\d{2}([.:]\\d{2,3})?\\]")
        // Regex para etiquetas de metadatos: [ar: artist], [al: album], [ti: title], etc.
        val metadataRegex = Regex("\\[[a-z]{2,}:.*\\]")
        
        for (line in lines) {
            var cleanLine = line.replace(timestampRegex, "")
            cleanLine = cleanLine.replace(metadataRegex, "").trim()
            
            if (cleanLine.isNotEmpty()) {
                parsed.append(cleanLine).append("\n\n")
            }
        }
        return parsed.toString().trim()
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
        btnShuffle.imageTintList = android.content.res.ColorStateList.valueOf(if (playbackService?.player?.shuffleModeEnabled == true) android.graphics.Color.parseColor("#1DB954") else android.graphics.Color.WHITE)
        btnRepeat.imageTintList = android.content.res.ColorStateList.valueOf(if (playbackService?.player?.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) android.graphics.Color.parseColor("#1DB954") else android.graphics.Color.WHITE)
        btnPrev.isEnabled = playbackService?.hasPrev() == true
        btnPrev.alpha = if (playbackService?.hasPrev() == true) 1f else 0.4f
        btnNext.isEnabled = playbackService?.hasNext() == true
        btnNext.alpha = if (playbackService?.hasNext() == true) 1f else 0.4f
        if (queueIsVisible) setupQueue()
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