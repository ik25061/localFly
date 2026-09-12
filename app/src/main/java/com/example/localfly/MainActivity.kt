package com.example.localfly

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.example.localfly.fragments.*
import com.example.localfly.network.RescanManager
import com.example.localfly.network.SessionManager
import com.example.localfly.network.SongAdminStore
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import kotlin.math.max

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var sessionManager: SessionManager
    private lateinit var rescanProgressLayout: View
    private lateinit var rescanProgressBar: ProgressBar
    private lateinit var rescanMessage: TextView

    var playbackService: PlaybackService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sessionManager = SessionManager(this)
        SongAdminStore.ensureContext(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNavigation)
        rescanProgressLayout = findViewById(R.id.layoutRescanProgress)
        rescanProgressBar = findViewById(R.id.pbRescan)
        rescanMessage = findViewById(R.id.tvRescanMessage)

        setupNavigation()
        setupMiniPlayer()
        setupRescanObserver()
        setupSystemBars()
        
        applyBackgroundAppearance()

        if (savedInstanceState == null) {
            replaceFragment(HomeFragment())
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = supportFragmentManager.findFragmentById(R.id.container)
                if (current !is HomeFragment) {
                    bottomNav.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupSystemBars() {
        val root = findViewById<View>(R.id.rootMain)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomNav.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
            }
            insets
        }
    }

    private fun setupNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> replaceFragment(HomeFragment())
                R.id.nav_search -> replaceFragment(SearchFragment())
                R.id.nav_downloads -> replaceFragment(DownloadsFragment())
                R.id.nav_playlists -> replaceFragment(PlaylistsFragment())
                R.id.nav_ai -> replaceFragment(AIFragment())
                else -> false
            }
            true
        }
    }

    private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean = false): Boolean {
        if (isFinishing || isDestroyed) return false
        val transaction = supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
        if (addToBackStack) transaction.addToBackStack(null)
        transaction.commit()
        return true
    }

    private fun setupMiniPlayer() {
        val miniPlayer = findViewById<View>(R.id.miniPlayer)
        miniPlayer.setOnClickListener {
            val intent = Intent(this, NowPlayingActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupRescanObserver() {
        lifecycleScope.launch {
            RescanManager.progress.collect { state ->
                if (state.phase == "idle" || state.phase == "done") {
                    rescanProgressLayout.visibility = View.GONE
                } else {
                    rescanProgressLayout.visibility = View.VISIBLE
                    rescanProgressBar.isIndeterminate = (state.pct <= 0)
                    if (state.pct > 0) rescanProgressBar.progress = state.pct
                    rescanMessage.text = state.message
                }
            }
        }
    }

    // ===== Fondo personalizado =====
    
    private var backgroundJob: Job? = null
    private var cachedOriginalBitmap: Bitmap? = null
    private var cachedUriString: String? = null
    private var cachedBlurredBitmap: Bitmap? = null
    private var cachedBlurValue: Int = -1
    private var cachedRotationValue: Int = -1

    fun applyBackgroundAppearance() {
        val root = findViewById<View>(R.id.rootMain) ?: return
        backgroundJob?.cancel()

        val mode = sessionManager.getBackgroundMode().lowercase()
        val fallbackSolid = parseColorSafely(sessionManager.getBackgroundSolidColor(), "#121212")
        val alphaPct = sessionManager.getBackgroundAlphaPct()
        val alpha = (alphaPct * 255) / 100

        when (mode) {
            "solid" -> {
                val base = ColorDrawable(fallbackSolid).apply { this.alpha = alpha }
                applyFinalBackground(root, base)
            }
            "gradient" -> {
                val start = parseColorSafely(sessionManager.getBackgroundGradientStart(), "#1DB954")
                val end = parseColorSafely(sessionManager.getBackgroundGradientEnd(), "#121212")
                val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
                    this.alpha = alpha
                }
                applyFinalBackground(root, base)
            }
            "image" -> {
                backgroundJob = lifecycleScope.launch {
                    delay(80) 
                    val bitmap = withContext(Dispatchers.IO) { buildImageBackgroundBitmap() }
                    val base: Drawable = if (bitmap != null) {
                        BitmapDrawable(resources, bitmap).apply { this.alpha = alpha }
                    } else {
                        ColorDrawable(fallbackSolid).apply { this.alpha = alpha }
                    }
                    applyFinalBackground(root, base)
                }
            }
            else -> {
                applyFinalBackground(root, ColorDrawable(fallbackSolid).apply { this.alpha = 255 })
            }
        }
    }

    private fun applyFinalBackground(root: View, base: Drawable) {
        val finalDrawable: Drawable =
            if (sessionManager.getBackgroundMode().lowercase() == "image") {
                val behindColor = parseColorSafely(sessionManager.getBackgroundOverlayColor(), "#66000000")
                val behindAlpha = (sessionManager.getBackgroundOverlayAlphaPct() * 255) / 100
                val behind = ColorDrawable(behindColor).apply { this.alpha = behindAlpha }
                LayerDrawable(arrayOf(behind, base))
            } else {
                base
            }
            
        // IMPORTANTE: Para evitar el efecto de "fantasma" o "Windows XP" (ghosting) al desplazar
        // listas sobre fondos transparentes, el fondo debe aplicarse a la VENTANA (Window).
        // Esto permite que el sistema use el fondo como buffer de limpieza optimizado.
        window.setBackgroundDrawable(finalDrawable)
        
        // Quitar el fondo del root para no dibujar dos veces (overdraw)
        root.background = null
    }

    private fun buildImageBackgroundBitmap(): Bitmap? {
        val uriString = sessionManager.getBackgroundImageUri()?.trim() ?: return null
        if (uriString.isBlank()) return null
        val uri = try { Uri.parse(uriString) } catch (_: Exception) { null }
        if (uri == null || (uri.scheme.isNullOrBlank() && !uriString.startsWith("/"))) return null

        val rotation = sessionManager.getBackgroundImageRotation()
        val blur = sessionManager.getBackgroundBlur()

        if (uriString == cachedUriString && rotation == cachedRotationValue && blur == cachedBlurValue && cachedBlurredBitmap != null) {
            return cachedBlurredBitmap!!.copy(cachedBlurredBitmap!!.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val original = if (uriString == cachedUriString && cachedOriginalBitmap != null) {
            cachedOriginalBitmap!!
        } else {
            val decoded = decodeBackgroundBitmap(uri) ?: return null
            cachedOriginalBitmap?.recycle()
            cachedOriginalBitmap = decoded
            cachedUriString = uriString
            decoded
        }

        var result: Bitmap = original
        try {
            val w = original.width
            val h = original.height
            if (rotation != 0) {
                val m = Matrix().apply { setRotate(rotation.toFloat(), w / 2f, h / 2f) }
                val rad = Math.toRadians(rotation.toDouble())
                val cos = Math.abs(Math.cos(rad))
                val sin = Math.abs(Math.sin(rad))
                val scale = max( (w * cos + h * sin) / w, (w * sin + h * cos) / h )
                m.postScale(scale.toFloat(), scale.toFloat(), w / 2f, h / 2f)
                
                val rotated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(rotated)
                canvas.drawBitmap(result, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
                if (result !== original) result.recycle()
                result = rotated
            }
            if (blur > 0) {
                val blurred = blurBitmap(result, blur)
                if (blurred !== result) {
                    if (result !== original) result.recycle()
                    result = blurred
                }
            }
            
            cachedBlurredBitmap?.recycle()
            cachedBlurredBitmap = result.copy(result.config ?: Bitmap.Config.ARGB_8888, true)
            cachedBlurValue = blur
            cachedRotationValue = rotation
            
            return if (result === original) {
                result.copy(result.config ?: Bitmap.Config.ARGB_8888, true)
            } else {
                result
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error procesando fondo", e)
            if (result !== original) result.recycle()
            return null
        }
    }

    private fun decodeBackgroundBitmap(uri: Uri?): Bitmap? {
        if (uri == null) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val maxDimension = 1800
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    private fun blurBitmap(bitmap: Bitmap, blur: Int): Bitmap {
        val factor = (blur / 7) + 1
        if (factor <= 1) return bitmap
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, bitmap.width / factor, bitmap.height / factor, true)
            val result = Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
            if (small !== bitmap) small.recycle()
            result
        } catch (e: Exception) { bitmap }
    }

    private fun parseColorSafely(hex: String?, fallback: String): Int {
        return try {
            Color.parseColor(hex ?: fallback)
        } catch (_: Exception) {
            Color.parseColor(fallback)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundJob?.cancel()
        cachedOriginalBitmap?.recycle()
        cachedBlurredBitmap?.recycle()
    }
}
