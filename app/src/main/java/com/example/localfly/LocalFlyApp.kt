package com.example.localfly

import android.app.Application
import com.example.localfly.network.SongAdminStore
import com.example.localfly.utils.LocalLogger

/**
 * Application de localFly.
 *
 * Su única misión es instalar LO ANTES POSIBLE el capturador global de crashes
 * ([LocalLogger.initCrashHandler]) y dar contexto al almacén local.
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
    lateinit var sessionManager: com.example.localfly.network.SessionManager
        private set

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
    }
}
