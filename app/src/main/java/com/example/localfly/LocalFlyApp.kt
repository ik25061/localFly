package com.example.localfly

import android.app.Application
import com.example.localfly.network.SongAdminStore
import com.example.localfly.utils.LocalLogger
import com.example.localfly.karaoke.KaraokeLyricsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application de localFly.
 *
 * Inicializa el capturador global de crashes, el contexto de los almacenes
 * locales, el servidor compartido de letras para Cast y la precarga del índice
 * de descargas en segundo plano.
 *
 * Sin esto, cuando PlaybackService (o cualquier otro componente) lanzaba una
 * excepción no controlada, el proceso moría y arrancaba de nuevo EN BUCLE sin
 * dejar ninguna traza: en el log solo se veían ráfagas de
 * "PlaybackService onStartCommand flags=0 startId=N action=null" (reintentos de
 * START_STICKY) y era imposible saber qué fallaba. Con el handler instalado,
 * cada cierre queda registrado como "CRASH FATAL en hilo ..." junto a su
 * stack trace en app_debug_log.txt.
 */
class LocalFlyApp : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var sessionManager: com.example.localfly.network.SessionManager
        private set

    /**
     * Un único servidor para las letras durante Cast. No debe vivir en una
     * Activity: Android puede destruir NowPlayingActivity mientras el receptor
     * sigue reproduciendo, y eso cortaría el WebVTT/audio local.
     */
    val karaokeLyricsServer: KaraokeLyricsServer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KaraokeLyricsServer(8099).apply {
            logger = { message -> LocalLogger.log(this@LocalFlyApp, message) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Lo primero de todo: capturar también fallos de arranque del servicio.
        LocalLogger.initCrashHandler(this)
        // Dar contexto al almacén local para que applyTo()/isDeleted() funcionen
        // incluso si algo (notificación, servicio en segundo plano) se ejecuta
        // antes de que arranque una Activity.
        SongAdminStore.ensureContext(this)

        // Una única instancia de SessionManager permite que Retrofit lea el token
        // vigente en cada petición, también cuando cambia la URL del servidor.
        sessionManager = com.example.localfly.network.SessionManager(this)
        com.example.localfly.network.RetrofitClient.setTokenProvider {
            sessionManager.getToken()
        }

        // Leer el índice JSON de descargas en segundo plano al arrancar. Así,
        // la primera reproducción offline no analiza miles de entradas en main.
        startupScope.launch {
            DownloadManagerHelper.getInstance(this@LocalFlyApp).warmDownloadIndex()
        }
    }
}
