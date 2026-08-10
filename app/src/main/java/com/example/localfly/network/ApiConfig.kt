package com.example.localfly.network

/**
 * Configuración central del servidor de mirepo.
 *
 * El servidor se levanta con `npm run dev` en el PC:
 *   - Backend Express (API, /audio, /cover): http://localhost:5002
 *   - Frontend Vite con proxy hacia Express:  http://localhost:5172
 *
 * [BASE_URL] debe ser alcanzable desde el dispositivo Android:
 *   - Teléfono FÍSICO en la misma red WiFi que el PC: usa la IP local
 *     mostrada por `npm run dev`, p.ej. "http://192.168.1.152:5172".
 *   - Emulador de Android Studio (backend directo): "http://10.0.2.2:5002".
 *   - Backend directo desde el PC: "http://127.0.0.1:5002".
 */
object ApiConfig {
    const val BASE_URL = "http://192.168.1.152:5172"
}