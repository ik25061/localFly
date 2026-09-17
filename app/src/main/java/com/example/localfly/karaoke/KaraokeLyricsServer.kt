package com.example.localfly.karaoke

import com.example.localfly.utils.LocalLogger
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Servidor ligero embebido en la app que emite la letra sincronizada para
 * mostrarla tipo teleprompter en un TV.
 *
 * Protocolo (mismo para Chromecast custom receiver y para la app/página del
 * Fire TV): JSON por HTTP/SSE con el formato
 *     { "type": "line", "title": "...", "artist": "...",
 *       "text": "línea actual", "nextText": "línea siguiente",
 *       "startMs": 1234, "endMs": 3456 }
 *
 * Endpoints:
 *   GET /        → página HTML de teleprompter (se puede abrir en el
 *                  navegador del Fire TV o de cualquier dispositivo).
 *   GET /events  → stream Server-Sent-Events con la línea activa.
 *   GET /state   → JSON con la última línea emitida.
 *
 * El TV (Fire TV) solo necesita abrir en su navegador: http://<ip>:8099
 */
class KaraokeLyricsServer(private val port: Int = 8099) {

    companion object {
        private const val TAG = "KaraokeTV"
        private const val KARAOKE_NAMESPACE = "urn:x-cast:com.example.localfly.karaoke"
    }

    data class KaraokeLinePayload(
        val type: String,
        val title: String = "",
        val artist: String? = null,
        val text: String = "",
        val nextText: String? = null,
        val startMs: Long = 0,
        val endMs: Long = 0
    )

    private val gson = Gson()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    private val sseClients = Collections.synchronizedList(mutableListOf<BufferedWriter>())
    @Volatile private var lastPayload: KaraokeLinePayload? = null

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                ServerSocket(port).let { serverSocket = it }
                android.util.Log.d(TAG, "Karaoke TV: servidor de letras escuchando en puerto $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running) android.util.Log.d(TAG, "Karaoke TV: servidor detenido (${e.message})")
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        synchronized(sseClients) {
            sseClients.forEach { runCatching { it.close() } }
            sseClients.clear()
        }
        android.util.Log.d(TAG, "Karaoke TV: servidor de letras detenido")
    }

    /** URL que hay que abrir en el TV (Fire TV / navegador) o pasar al receptor. */
    fun url(): String? {
        val ip = localIp() ?: return null
        return "http://$ip:$port"
    }

    fun publish(payload: KaraokeLinePayload) { lastPayload = payload }

    private fun handleClient(client: Socket) {
        // Bounded read; a slow client must not hold the listener indefinitely.
        client.use { socket ->
            socket.soTimeout = 1500
            try {
                val reader = socket.getInputStream().bufferedReader()
                val request = StringBuilder()
                while (request.length < 2048) {
                    val c = reader.read()
                    if (c < 0 || c == 10) break
                    request.append(c.toChar())
                }
                val path = request.toString().split(' ').getOrNull(1)
                val state = path == "/state"
                val valid = path == "/" || state
                val body = if (!valid) "Not found" else if (state) gson.toJson(lastPayload) else page
                val bytes = body.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 ${if (valid) "200 OK" else "404 Not Found"}\r\n" +
                    "Content-Type: ${if (state) "application/json" else "text/html"}; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush()
                }
            } catch (_: IOException) { }
        }
    }

    private fun localIp(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private val page = """
        <!doctype html><html lang="es"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>LocalFly Karaoke</title>
        <style>body{background:#101010;color:white;text-align:center;font-family:sans-serif;padding:8vh 5vw}
        h1{color:#1db954;font-size:3vw}#line{font-size:5vw;margin:15vh 0 8vh}#next{font-size:3vw;color:#aaa}</style>
        <h1 id="title">LocalFly Karaoke</h1><div id="line">Esperando letra sincronizada…</div><div id="next"></div>
        <script>
        async function tick(){try{const r=await fetch('/state',{cache:'no-store'});const s=await r.json();
        if(s){document.getElementById('title').textContent=s.title||'LocalFly Karaoke';
        document.getElementById('line').textContent=s.text||'';
        document.getElementById('next').textContent=s.nextText||'';}}
        catch(e){document.getElementById('line').textContent='Sin conexión';}
        finally{setTimeout(tick,250);}}tick();
        </script></html>
    """.trimIndent()
}

