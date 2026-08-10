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
import com.bumptech.glide.Glide
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.Song
import java.util.Locale

class NowPlayingActivity : AppCompatActivity() {

    // Debe coincidir con la URL base de RetrofitClient/ApiConfig (sin la barra final)
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
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton

    private var playbackService: PlaybackService? = null
    private var isBound = false
    private var userIsSeeking = false

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
        btnPrev = findViewById(R.id.btnFullPrev)
        btnNext = findViewById(R.id.btnFullNext)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        btnPlayPause.setOnClickListener { playbackService?.togglePlayPause() }
        btnLike.setOnClickListener { playbackService?.toggleLike() }
        btnPrev.setOnClickListener { playbackService?.prev() }
        btnNext.setOnClickListener { playbackService?.next() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvCurrentTime.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userIsSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsSeeking = false
                playbackService?.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })
    }

    private fun refreshUi() {
        val song = playbackService?.currentSong ?: run { finish(); return }

        tvTitle.text = toTitleCase(song.title)
        tvArtist.text = toTitleCase(song.artist) ?: "Artista desconocido"

        // Rutas reales del servidor: /artist-cover/{nombre} y /cover/{id}
        val artistEncoded = java.net.URLEncoder.encode(song.artist ?: "", "UTF-8").replace("+", "%20")
        val artistImageUrl = "$serverBaseUrl/artist-cover/$artistEncoded"
        val albumImageUrl = "$serverBaseUrl/cover/${song.id}"

        // Fondo difuminado (siempre el álbum)
        Glide.with(this)
            .load(albumImageUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .centerCrop()
            .override(100, 100) // Para mejorar rendimiento del blur si fuera local, pero Glide lo maneja
            .into(ivBlurredBackground)

        // Imagen circular (Artista si tiene, sino Álbum)
        Glide.with(this)
            .load(artistImageUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .error(
                Glide.with(this)
                    .load(albumImageUrl)
                    .centerCrop()
            )
            .centerCrop()
            .into(ivCircularImage)

        btnLike.setImageResource(
            if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off
        )

        val isPlaying = playbackService?.player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        btnPrev.isEnabled = playbackService?.hasPrev() == true
        btnPrev.alpha = if (playbackService?.hasPrev() == true) 1f else 0.4f
        btnNext.isEnabled = playbackService?.hasNext() == true
        btnNext.alpha = if (playbackService?.hasNext() == true) 1f else 0.4f
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

        val isPlaying = service.player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun toTitleCase(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return text.lowercase(Locale.getDefault())
            .split(" ")
            .joinToString(" ") { word ->
                if (word.isEmpty()) word
                else word.replaceFirstChar { it.uppercase(Locale.getDefault()) }
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