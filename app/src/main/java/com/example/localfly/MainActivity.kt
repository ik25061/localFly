package com.example.localfly

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.example.localfly.fragments.*
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.PlaylistSyncManager
import com.example.localfly.network.RescanManager
import com.example.localfly.network.ServerReachability
import com.example.localfly.network.SessionManager
import com.example.localfly.network.SongAdminStore
import com.example.localfly.utils.CoverPlaceholder
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

    // Mini reproductor
    private lateinit var miniPlayer: View
    private lateinit var miniPlayerInfo: View
    private lateinit var ivMiniCover: ImageView
    private lateinit var tvNowPlayingTitle: TextView
    private lateinit var tvNowPlayingArtist: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnMiniLike: ImageButton
    private lateinit var btnMiniDislike: ImageButton
    private lateinit var btnNext: ImageButton

    private val serverBaseUrl get() = ApiConfig.BASE_URL

    var playbackService: PlaybackService? = null
        private set
    private var isBound = false

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isServerOnline = false
    private var connectivityPollJob: Job? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: la notif. de MediaSession no depende de esto */ }

    // ===== SERVICE CONNECTION =====
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            playbackService?.onStateChanged = { refreshMiniPlayer() }
            refreshMiniPlayer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        sessionManager = SessionManager(this)
        applyFontScale()

        super.onCreate(savedInstanceState)

        SongAdminStore.ensureContext(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Punto 1: sin esto, el sistema pinta su propia franja gris encima de
        // nuestro fondo personalizado en la barra de estado/navegación.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNavigation)
        rescanProgressLayout = findViewById(R.id.layoutRescanProgress)
        rescanProgressBar = findViewById(R.id.pbRescan)
        rescanMessage = findViewById(R.id.tvRescanMessage)

        setupNavigation()
        setupMiniPlayer()
        setupRescanObserver()
        setupSystemBars()
        setupServerConnectivityMonitoring()
        requestNotificationPermissionIfNeeded()

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

    // Punto 5: aplica de verdad el tamaño de letra elegido en Ajustes.
    // Antes se llamaba desde applyAppSettings(); esa función se perdió y con
    // ella la única línea que realmente escalaba la tipografía del sistema.
    private fun applyFontScale() {
        val scale = when (sessionManager.getTextSize()) {
            "Extra pequeño" -> 0.75f
            "Normal" -> 1.0f
            "Grande" -> 1.25f
            "Extra grande" -> 1.45f
            else -> 1.0f
        }
        val configuration = resources.configuration
        configuration.fontScale = scale
        val metrics = resources.displayMetrics
        @Suppress("DEPRECATION")
        metrics.scaledDensity = configuration.fontScale * metrics.density
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, metrics)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Punto 4: además del margen inferior para la barra de navegación, se
    // restaura el padding superior para la barra de estado/notch — sin esto
    // el contenido de cada pantalla queda pegado arriba del todo.
    private fun setupSystemBars() {
        val root = findViewById<View>(R.id.rootMain)
        val container = findViewById<View>(R.id.container)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            container?.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
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

    // ===== MINI REPRODUCTOR (punto 6) =====

    private fun setupMiniPlayer() {
        miniPlayer = findViewById(R.id.miniPlayer)
        miniPlayerInfo = findViewById(R.id.miniPlayerInfo)
        ivMiniCover = findViewById(R.id.ivMiniCover)
        tvNowPlayingTitle = findViewById(R.id.tvNowPlayingTitle)
        tvNowPlayingArtist = findViewById(R.id.tvNowPlayingArtist)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnMiniLike = findViewById(R.id.btnMiniLike)
        btnMiniDislike = findViewById(R.id.btnMiniDislike)
        btnNext = findViewById(R.id.btnNext)

        miniPlayerInfo.setOnClickListener {
            if (playbackService?.currentSong != null) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }
        btnPlayPause.setOnClickListener { playbackService?.togglePlayPause() }
        btnMiniLike.setOnClickListener { playbackService?.toggleLike() }
        btnMiniDislike.setOnClickListener { playbackService?.dislikeCurrentSong() }
        btnNext.setOnClickListener { playbackService?.next() }
    }

    private fun refreshMiniPlayer() {
        val song = playbackService?.currentSong
        if (song == null) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        tvNowPlayingTitle.text = song.title
        tvNowPlayingArtist.text = if (song.artist != null) "Artista: ${song.artist}" else "Artista desconocido"

        val coverUrl = "$serverBaseUrl/cover/${song.id}"
        Glide.with(this)
            .load(coverUrl)
            .placeholder(CoverPlaceholder.drawable(song.id))
            .error(CoverPlaceholder.drawable(song.id))
            .centerCrop()
            .into(ivMiniCover)

        btnMiniLike.setImageResource(if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off)
        btnMiniDislike.setImageResource(R.drawable.ic_dislike_off)

        val isPlaying = playbackService?.player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        btnNext.alpha = if (playbackService?.hasNext() == true) 1f else 0.4f
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

    // ===== CONECTIVIDAD CON EL SERVIDOR (punto 3) =====
    // No es "hay internet o no": es un ping real y corto a /api/config/ip.
    // ConnectivityManager solo actúa como disparador para revisar antes; además
    // se hace polling cada 15s por si el servidor cae sin que cambie la red
    // del móvil (p. ej. se apaga el PC).
    private fun setupServerConnectivityMonitoring() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        lifecycleScope.launch { checkServerReachabilityNow() }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch { checkServerReachabilityNow() }
            }
            override fun onLost(network: Network) {
                isServerOnline = false
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback!!)

        connectivityPollJob?.cancel()
        connectivityPollJob = lifecycleScope.launch {
            while (true) {
                delay(15000)
                checkServerReachabilityNow()
            }
        }
    }

    private suspend fun checkServerReachabilityNow() {
        val reachable = ServerReachability.isServerReachable()
        if (isServerOnline == reachable) return
        isServerOnline = reachable
        if (!reachable) return

        // El servidor volvió: sincronizar playlists pendientes...
        lifecycleScope.launch {
            PlaylistSyncManager.sync(sessionManager)
            (supportFragmentManager.findFragmentById(R.id.container) as? PlaylistsFragment)?.reloadAfterSync()
        }
        // ...y disparar la auto-descarga inteligente (hasta 500 temas offline).
        lifecycleScope.launch {
            val helper = DownloadManagerHelper.getInstance(this@MainActivity)
            helper.autoDownloadSmart(sessionManager)
        }
    }

    // ===== Fondo personalizado =====

    private var backgroundJob: Job? = null
    private var cachedOriginalBitmap: Bitmap? = null
    private var cachedUriString: String? = null
    private var cachedBlurredBitmap: Bitmap? = null
    private var cachedBlurValue: Int = -1
    private var cachedRotationValue: Int = -1
    private var cachedFitMode: String? = null
    private var compositedBackgroundBitmap: Bitmap? = null

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

    @Suppress("DEPRECATION")
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

        // ANTI-GHOSTING: se pre-compone siempre sobre negro opaco para evitar
        // el efecto "texto fantasma" al hacer scroll con transparencia < 100%.
        val opaqueDrawable: Drawable =
            if (finalDrawable.getOpacity() == PixelFormat.OPAQUE) {
                finalDrawable
            } else {
                compositeOverOpaqueBlack(finalDrawable)
            }

        window.setBackgroundDrawable(opaqueDrawable)
        root.background = null
    }

    private fun compositeOverOpaqueBlack(drawable: Drawable): Drawable {
        val w = max(resources.displayMetrics.widthPixels, 1)
        val h = max(resources.displayMetrics.heightPixels, 1)
        var bitmap = compositedBackgroundBitmap
        if (bitmap == null || bitmap.isRecycled || bitmap.width != w || bitmap.height != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            compositedBackgroundBitmap = bitmap
        }
        bitmap.eraseColor(Color.BLACK)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        return BitmapDrawable(resources, bitmap)
    }

    // Punto 7: ahora entrega SIEMPRE un bitmap del tamaño exacto de la
    // pantalla, ya recortado/ajustado según el modo elegido — antes se
    // estiraba sin más al pintarse como fondo de la ventana.
    private fun buildImageBackgroundBitmap(): Bitmap? {
        val uriString = sessionManager.getBackgroundImageUri()?.trim() ?: return null
        if (uriString.isBlank()) return null
        val uri = try { Uri.parse(uriString) } catch (_: Exception) { null }
        if (uri == null || (uri.scheme.isNullOrBlank() && !uriString.startsWith("/"))) return null

        val rotation = sessionManager.getBackgroundImageRotation()
        val blur = sessionManager.getBackgroundBlur()
        val fitMode = sessionManager.getBackgroundImageFitMode()

        if (uriString == cachedUriString && rotation == cachedRotationValue &&
            blur == cachedBlurValue && fitMode == cachedFitMode && cachedBlurredBitmap != null) {
            return cachedBlurredBitmap!!.copy(cachedBlurredBitmap!!.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val original = if (uriString == cachedUriString && cachedOriginalBitmap != null) {
            cachedOriginalBitmap!!
        } else {
            // decodeBackgroundBitmap ya corrige la orientación EXIF de la foto.
            val decoded = decodeBackgroundBitmap(uri) ?: return null
            cachedOriginalBitmap?.recycle()
            cachedOriginalBitmap = decoded
            cachedUriString = uriString
            decoded
        }

        return try {
            val screenW = max(resources.displayMetrics.widthPixels, 1)
            val screenH = max(resources.displayMetrics.heightPixels, 1)

            var result = original
            if (rotation != 0) {
                val m = Matrix().apply { setRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
                result = rotated
            }

            // Encajar (recortar o dejar bandas, según el modo) al tamaño exacto de pantalla.
            val fitted = fitBitmapToScreen(result, screenW, screenH, fitMode)
            if (fitted !== result && result !== original) result.recycle()
            result = fitted

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
            cachedFitMode = fitMode

            if (result === original) result.copy(result.config ?: Bitmap.Config.ARGB_8888, true) else result
        } catch (e: Exception) {
            Log.e("MainActivity", "Error procesando fondo", e)
            null
        }
    }

    /**
     * Escala [src] al tamaño exacto [targetW]x[targetH]:
     *  - "cover" (por defecto): recorta el sobrante, sin bandas ni estiramiento.
     *  - "fit_width": ajusta al ancho completo, puede dejar banda arriba/abajo.
     *  - "fit_height": ajusta al alto completo, puede dejar banda a los lados.
     */
    private fun fitBitmapToScreen(src: Bitmap, targetW: Int, targetH: Int, fitMode: String): Bitmap {
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0) return src

        val scale = when (fitMode) {
            "fit_width" -> targetW.toFloat() / srcW
            "fit_height" -> targetH.toFloat() / srcH
            else -> max(targetW.toFloat() / srcW, targetH.toFloat() / srcH) // cover
        }

        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val left = (targetW - scaledW) / 2f
        val top = (targetH - scaledH) / 2f
        canvas.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== src) scaled.recycle()
        return out
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
            val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            // Corrige la orientación EXIF (fotos de cámara/galería que vienen
            // "giradas" solo por metadato) para no depender del botón manual.
            val exifDegrees = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    when (ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (_: Exception) { 0 }

            if (exifDegrees == 0) return decoded
            val m = Matrix().apply { setRotate(exifDegrees.toFloat()) }
            val corrected = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (corrected !== decoded) decoded.recycle()
            corrected
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

    // ===== CICLO DE VIDA (bind del servicio de reproducción) =====

    override fun onStart() {
        super.onStart()
        if (isBound) return
        Intent(this, PlaybackService::class.java).apply { action = PlaybackService.ACTION_LOCAL_BIND }.also { intent ->
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    bindService(intent, connection, BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error bindeando PlaybackService", e)
                }
            }, 100)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundJob?.cancel()
        connectivityPollJob?.cancel()
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) { }
        }
        cachedOriginalBitmap?.recycle()
        cachedBlurredBitmap?.recycle()
        // compositedBackgroundBitmap NO se recicla aquí: el drawable de la ventana
        // todavía puede estar referenciándolo durante el último frame en vuelo.
    }
}
