package com.example.localfly.network

/**
 * Configuración central del servidor de mirepo.
 *
 * El servidor se levanta con `npm run dev` o `npm run server` en el PC:
 *   - Backend Express (API, /audio, /cover): http://localhost:5002
 *   - Frontend Vite con proxy hacia Express:  http://localhost:5172
 *
 * IMPORTANTE: la app Android debe apuntar DIRECTO al backend Express (5002),
 * nunca al puerto del frontend Vite (5172), porque el proxy de Vite solo
 * funciona dentro del navegador del PC.
 *
 *  - Emulador de Android Studio: "http://10.0.2.2:5002" (10.0.2.2 = localhost del PC).
 *  - Teléfono FÍSICO en la misma WiFi que el PC: usa la IP local del PC,
 *    p.ej. "http://192.168.1.152:5002" (la que muestra `npm run dev`).
 */
object ApiConfig {
    /** Puerto del backend Express. No cambiar salvo que cambie el .env del servidor. */
    const val SERVER_PORT = 5002

    /** IP por defecto del PC en tu red (según tu .env: VITE_SERVER_HOST). */
    const val DEFAULT_LAN_IP = "192.168.1.152"

    /** URL para el emulador de Android Studio. */
    const val EMULATOR_BASE_URL = "http://10.0.2.2:5002"

    /** URL para teléfono físico en tu red actual. */
    const val LAN_BASE_URL = "http://192.168.1.152:5002"

    /**
     * URL base por defecto. Se deja la LAN porque es lo que usa un teléfono
     * físico; el emulador también puede resolver la IP LAN del PC en la
     * mayoría de setups, y además la pantalla de Ajustes permite cambiarla
     * en tiempo de ejecución (ver [RetrofitClient.setBaseUrl]).
     */
    const val BASE_URL = LAN_BASE_URL

    /** Construye una URL base válida a partir de lo que escriba el usuario. */
    fun buildBaseUrl(rawHost: String): String {
        val input = rawHost.trim()
        if (input.isEmpty()) return BASE_URL
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input.trimEnd('/')
        }
        // Acepta "192.168.1.152", "192.168.1.152:5002" o "10.0.2.2:5002".
        val withPort = if (input.contains(":")) input else "$input:$SERVER_PORT"
        return "http://$withPort".trimEnd('/')
    }
}