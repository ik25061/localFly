package com.example.localfly.karaoke

import com.example.localfly.utils.LocalLogger
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
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
 *   GET /          → página HTML de teleprompter (se puede abrir en el
 *                    navegador del Fire TV o de cualquier dispositivo).
 *   GET /events    → stream Server-Sent-Events con la línea activa.
 *   GET /state     → JSON con la última línea emitida.
 *   GET /track.vtt → WebVTT con la letra sincronizada (pista de subtítulos).
 *   GET /silence   → MP3 de silencio (karaoke: la música se queda en el
 *                    teléfono y el TV solo muestra la letra).
 *   GET /audio     → proxy del audio real con cabeceras CORS (modo "el audio
 *                    suena en el televisor").
 *
 * CORS: Google Cast exige cabeceras CORS en las pistas de texto Y en el medio
 * cuando se usan MediaTrack; sin ellas el receptor descarta los subtítulos
 * silenciosamente. Por eso TODAS las respuestas de este servidor las incluyen.
 *
 * El TV (Fire TV) solo necesita abrir en su navegador: http://<ip>:8099
 */
class KaraokeLyricsServer(private val port: Int = 8099) {

    companion object {
        private const val TAG = "KaraokeTV"
        private const val KARAOKE_NAMESPACE = "urn:x-cast:com.example.localfly.karaoke"

        /**
         * Cabeceras permitidas cuando el receptor no pide ninguna en concreto.
         *
         * Los headers permitidos NO son libres: la documentacion de Google
         * exige como minimo Content-Type, Accept-Encoding y Range. Si falta
         * Accept-Encoding, el preflight del receptor falla y los subtitulos
         * nunca llegan a mostrarse.
         */
        private const val DEFAULT_ALLOW_HEADERS =
            "Range, Content-Type, Accept-Encoding, Origin, Authorization, Accept"

        /** Duración asumida (4 min) cuando la canción aún no tiene duración conocida. */
        private const val DEFAULT_DURATION_MS = 240_000L

        /**
         * Cabeceras CORS que exige el receptor de Google Cast. Sin ellas la
         * carga se descarta y el TV se queda sin letra (y sin audio cuando la
         * pista lleva MediaTrack) volviendo a su pantalla de reposo.
         *
         * Detalles que importan y que la version anterior no cubria:
         *  - Se DEVUELVE el mismo `Origin` que envio el receptor (y no el
         *    comodin `*`): cuando el fetch lleva credenciales el navegador
         *    rechaza `*`, y en ese caso debe coincidir con `Allow-Credentials`.
         *  - `Access-Control-Allow-Private-Network`: Chrome/CAF exige esta
         *    cabecera cuando un contexto seguro (el receptor) pide un recurso
         *    de una IP privada (192.168.x.x). Sin ella el navegador descarta la
         *    respuesta y el receptor se queda en reposo aunque el servidor haya
         *    contestado.
         *  - Los headers pedidos en el preflight se repiten tal cual, asi
         *    cualquier cabecera extra del receptor queda permitida.
         */
        internal fun corsHeaders(origin: String?, requestedHeaders: String? = null): String {
            val allowOrigin = origin?.takeIf { it.isNotBlank() } ?: "*"
            val allowHeaders = requestedHeaders?.takeIf { it.isNotBlank() } ?: DEFAULT_ALLOW_HEADERS
            return "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                "Access-Control-Allow-Credentials: true\r\n" +
                "Vary: Origin\r\n" +
                "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: $allowHeaders\r\n" +
                "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type, Date\r\n" +
                "Access-Control-Allow-Private-Network: true\r\n" +
                "Timing-Allow-Origin: *\r\n"
        }
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

    // ===== Pista actual (letra sincronizada) para el Chromecast =====
    @Volatile private var trackSongId: String? = null
    @Volatile private var trackTitle: String = ""
    @Volatile private var trackArtist: String? = null
    @Volatile private var trackLines: List<Pair<Long, String>> = emptyList()
    /** Duración de la pista actual: reparte la letra en el tiempo si no lo tiene. */
    @Volatile private var trackDurationMs: Long = 0L

    // Caché del silencio generado (solo se rebuild cuando cambia la duración).
    @Volatile private var silenceCache: Pair<Long, ByteArray>? = null

    /** Última petición recibida del receptor (diagnóstico de "no sale la letra"). */
    @Volatile private var lastRequest: String = ""

    /**
     * Resultado del último proxy de audio (/audio): p. ej. `206 6321450 bytes` o
     * `502 upstream HTTP 404`. Es lo único que permite ver desde la app por qué
     * el receptor se queda en su pantalla de reposo cuando el audio va al TV.
     */
    @Volatile private var lastProxyResult: String = ""

    /** Log opcional: la actividad lo conecta a LocalLogger para dejar rastro. */
    var logger: ((String) -> Unit)? = null

    private fun log(message: String) {
        runCatching { logger?.invoke(message) }
        android.util.Log.d(TAG, message)
    }

    /** Resumen para depurar: IP, puerto, nº de líneas, petición y proxy del TV. */
    fun diagnostic(): String {
        val ip = localIp() ?: "sin red"
        val proxy = lastProxyResult.takeIf { it.isNotBlank() }?.let { " · audio=$it" }.orEmpty()
        return "http://$ip:$port · líneas=${trackLines.size} · canción=${trackSongId ?: "-"} · " +
            "última petición=${lastRequest.ifBlank { "ninguna" }}" + proxy
    }

    fun start() {
        if (running) return
        // El socket se abre AQUI, no dentro del hilo: el receptor pide el audio
        // (y la letra) milisegundos despues del LOAD, y si el puerto todavia no
        // esta escuchando el Chromecast recibe "conexion rechazada", falla el
        // medio y se queda en su pantalla de reposo mostrando solo el icono.
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
        } catch (e: Exception) {
            log("Karaoke TV: no se pudo abrir el puerto $port (${e.message})")
            return
        }
        serverSocket = socket
        running = true
        acceptThread = Thread {
            try {
                log("Karaoke TV: servidor de letras escuchando en puerto $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    // Un cliente por hilo: la espera de la letra (/track.vtt) no
                    // debe bloquear el resto de peticiones del receptor.
                    Thread({
                        try { handleClient(client) } catch (_: Exception) {}
                    }, "KaraokeClient").apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (running) log("Karaoke TV: servidor detenido (${e.message})")
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
        log("Karaoke TV: servidor de letras detenido")
    }

    /** URL que hay que abrir en el TV (Fire TV / navegador) o pasar al receptor. */
    fun url(): String? {
        val ip = localIp() ?: return null
        return "http://$ip:$port"
    }

    fun publish(payload: KaraokeLinePayload) { lastPayload = payload }

    /**
     * Registra la letra de la canción actual para servirla a Chromecast
     * (/track.vtt) como pista de subtítulos.
     *
     * @param lines pares (posición en ms, texto) ordenados por posición.
     * @param durationMs duración de la canción; si la letra viene sin tiempos
     *   ([lines] con todas las posiciones a 0) se reparte a lo largo de la
     *   canción para que el TV muestre algo en lugar de un VTT vacío.
     */
    fun setTrack(
        songId: String,
        title: String?,
        artist: String?,
        lines: List<Pair<Long, String>>,
        durationMs: Long = 0L
    ) {
        trackSongId = songId
        trackTitle = title ?: ""
        trackArtist = artist
        trackDurationMs = durationMs.coerceAtLeast(0L)
        trackLines = withTimings(lines, durationMs)
    }

    /**
     * Si la letra no tiene tiempos (letra guardada a mano, sin sincronizar) la
     * reparte por igual a lo largo de la canción: sin esto el WebVTT salía
     * vacío porque todas las líneas empiezan en 0 y no hay ningún "fin" válido.
     */
    internal fun withTimings(lines: List<Pair<Long, String>>, durationMs: Long): List<Pair<Long, String>> {
        val clean = lines.filter { it.second.isNotBlank() }
        if (clean.isEmpty()) return emptyList()
        // Si la duración aún no se conoce (el reproductor no la tiene), se
        // asume una canción de 4 minutos para poder repartir los tiempos.
        val total = durationMs.takeIf { it > 0L } ?: DEFAULT_DURATION_MS
        val sync = clean.count { it.first > 0L } >= (clean.size / 2).coerceAtLeast(1)
        if (sync) return clean.sortedBy { it.first }
        // Primer tercio reservado a la introducción; el resto se reparte.
        val usable = (total * 2 / 3).coerceAtLeast(1000L)
        val slot = usable / clean.size
        return clean.mapIndexed { i, line -> (i * slot).toLong() to line.second }
    }

    /** URL de la letra (WebVTT) para incluirla en la pista de Chromecast. */
    fun trackUrl(songId: String): String? {
        val ip = localIp() ?: return null
        return "http://$ip:$port/track.vtt?song=$songId"
    }

    /** URL del audio "silencio" que recibe el Chromecast en lugar de la música. */
    fun silenceUrl(songId: String, durationMs: Long): String? {
        val ip = localIp() ?: return null
        return "http://$ip:$port/silence?ms=$durationMs&song=$songId"
    }

    /**
     * URL del proxy local del audio real (modo "el audio suena en el TV").
     *
     * El servidor de LocalFly no manda cabeceras CORS y Google Cast las exige
     * cuando la pista lleva MediaTrack: por eso el audio se sirve desde aquí,
     * con CORS y reenviando al upstream las peticiones Range del receptor.
     */
    fun mediaProxyUrl(songId: String, sourceUrl: String): String? {
        val ip = localIp() ?: return null
        return "http://$ip:$port/audio?song=$songId&u=" + java.net.URLEncoder.encode(sourceUrl, "UTF-8")
    }

    private fun handleClient(client: Socket) {
        // Bounded read; a slow client must not hold the listener indefinitely.
        client.use { socket ->
            // /track.vtt puede esperar la letra (ver waitForTrack)
            socket.soTimeout = 20000
            try {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = readLine(reader) ?: return
                // Se rellena con true para HEAD: se envían cabeceras sin cuerpo.
                var headOnly = false
                // Cabeceras: el receptor manda "Range" para poder hacer seek.
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(reader) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase().orEmpty()
                val rawPath = parts.getOrNull(1) ?: ""
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))

                // Origin del receptor: se devuelve tal cual en la respuesta.
                val origin = headers["origin"]

                // Traza de peticiones: es la unica forma de saber si el receptor
                // llego a pedir la letra cuando el TV se queda en negro.
                lastRequest = "$method $rawPath" + if (headers["range"] != null) " [${headers["range"]}]" else ""
                log("Karaoke TV <- $lastRequest")
                if (method == "HEAD") headOnly = true

                if (method == "OPTIONS") {
                    writePreFlight(socket, origin, headers["access-control-request-headers"])
                    return
                }

                when (path) {
                    "/" -> writeResponse(socket, 200, "text/html; charset=utf-8", page, origin)
                    "/state" -> writeResponse(socket, 200, "application/json", gson.toJson(lastPayload), origin)
                    "/track.vtt" -> {
                        val songId = query["song"]?.ifBlank { null }
                        if (songId != null) waitForTrack(songId)
                        writeBytes(
                            socket,
                            "text/vtt; charset=utf-8",
                            buildVtt().toByteArray(Charsets.UTF_8),
                            headers["range"],
                            headOnly,
                            origin
                        )
                    }
                    "/silence" -> {
                        val ms = query["ms"]?.toLongOrNull() ?: 0L
                        writeBytes(socket, "audio/mpeg", silentMp3(ms), headers["range"], headOnly, origin)
                    }
                    "/audio" -> {
                        // Flujo largo: sin timeout de socket para no cortar la canción.
                        socket.soTimeout = 0
                        proxyAudio(socket, query["u"], headers["range"], headOnly, origin)
                    }
                    else -> writeResponse(socket, 404, "text/plain", "Not found", origin)
                }
            } catch (_: IOException) { }
        }
    }

    /** Lee una línea HTTP (sin el CR/LF) o null si el cliente cerró la conexión. */
    private fun readLine(reader: java.io.BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == 10) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 4096) return sb.toString()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            out[URLDecoder.decode(name, "UTF-8")] = runCatching { URLDecoder.decode(value, "UTF-8") }
                .getOrDefault(value)
        }
        return out
    }

    /** Preflight CORS (el receptor lo lanza al pedir la pista de texto). */
    private fun writePreFlight(socket: Socket, origin: String?, requestedHeaders: String?) {
        val header = "HTTP/1.1 204 No Content\r\n" + corsHeaders(origin, requestedHeaders) +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
    }

    /**
     * Proxy del audio real con CORS (modo "el audio suena en el televisor").
     *
     * Google Cast exige cabeceras CORS en el medio cuando la carga lleva
     * MediaTrack. Se reenvía la cabecera Range para que el TV pueda hacer seek
     * dentro de la canción.
     *
     * IMPORTANTE: el receptor interpreta "conexión cerrada sin respuesta" como
     * error de medio y vuelve a su pantalla de reposo (el TV solo muestra el
     * icono de Cast). Por eso CUALQUIER fallo del upstream se contesta aquí con
     * un 502 explícito y se apunta en [lastProxyResult] para el diagnóstico.
     */
    private fun proxyAudio(
        socket: Socket,
        target: String?,
        range: String?,
        headOnly: Boolean = false,
        origin: String? = null
    ) {
        if (target.isNullOrBlank()) {
            lastProxyResult = "sin url de origen"
            writeResponse(socket, 404, "text/plain", "Missing audio url", origin)
            return
        }
        val conn = runCatching {
            (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "LocalFly-Cast/1.0")
                // Sin compresión: si el upstream mandase gzip el receptor
                // recibiría bytes que no puede decodificar como MP3.
                setRequestProperty("Accept-Encoding", "identity")
                if (!range.isNullOrBlank()) setRequestProperty("Range", range)
            }
        }.getOrNull()
        if (conn == null) {
            lastProxyResult = "url de origen inválida"
            log("Karaoke TV: proxy /audio con URL inválida")
            writeResponse(socket, 502, "text/plain", "Upstream unreachable", origin)
            return
        }
        // Marca de "ya se enviaron cabeceras": a partir de ahí un corte del
        // receptor es normal (seek/stop) y no hay que contestar nada más.
        var responded = false
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = "upstream HTTP $code ${conn.responseMessage ?: ""}".trim()
                lastProxyResult = detail
                log("Karaoke TV: /audio -> $detail")
                writeResponse(socket, 502, "text/plain", "Upstream error $code", origin)
                responded = true
                return
            }
            val body = conn.inputStream
            val length = conn.contentLengthLong.coerceAtLeast(0L)
            val status = if (code == 206) "206 Partial Content" else "200 OK"
            lastProxyResult = if (length > 0) "$code $length bytes" else "$code sin longitud"
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status\r\n")
            sb.append("Content-Type: ").append(conn.contentType ?: "audio/mpeg").append("\r\n")
            if (length > 0) sb.append("Content-Length: $length\r\n")
            sb.append("Accept-Ranges: ").append(conn.getHeaderField("Accept-Ranges") ?: "bytes").append("\r\n")
            conn.getHeaderField("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
            sb.append(corsHeaders(origin))
            sb.append("Cache-Control: no-store\r\n")
            sb.append("Connection: close\r\n\r\n")

            val out = socket.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.flush()
            responded = true
            // Un HEAD solo pide las cabeceras: no se reenvía el cuerpo entero.
            if (headOnly) return
            val buffer = ByteArray(16 * 1024)
            while (running) {
                val n = body.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                out.flush()
            }
        } catch (e: Exception) {
            // Sin respuesta el receptor descarta el medio (y con él la letra):
            // se contesta un 502 explícito si aún no se había escrito nada.
            if (!responded) {
                lastProxyResult = "error ${e.javaClass.simpleName}: ${e.message}"
                log("Karaoke TV: /audio sin poder contestar (${e.message})")
                runCatching { writeResponse(socket, 502, "text/plain", "Upstream failed", origin) }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Espera (máx. 15 s) a que la app publique la letra de [songId].
     *
     * El receptor pide el VTT justo al cargar la pista, mientras la app aún
     * puede estar descargando la letra: con esta espera el Chromecast siempre
     * recibe la letra correcta en la primera petición.
     */
    private fun waitForTrack(songId: String) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (running && trackSongId != songId && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * WebVTT con los tiempos de la canción actual (subtítulos en el TV).
     *
     * A propósito NO se incluye bloque STYLE: el tamaño y el color los fija el
     * sender con `TextTrackStyle`, que es la vía soportada. Un bloque STYLE con
     * propiedades fuera de la lista permitida (p.ej. `line-height`, `font-size`
     * en `vh`) puede hacer que el parser del receptor descarte el archivo
     * completo, dejando el TV sin ninguna línea: el riesgo no compensa.
     */
    internal fun buildVtt(): String {
        val lines = trackLines
        val sb = StringBuilder("WEBVTT\n\n")
        if (lines.isEmpty()) return sb.toString()
        for (i in lines.indices) {
            val (startMs, text) = lines[i]
            val rawEnd = lines.getOrNull(i + 1)?.first
                ?: trackDurationMs.takeIf { it > startMs }?.minus(300)
                ?: (startMs + 6000L)
            // Un cue con duración cero lo descarta el receptor: si la línea
            // siguiente empieza en el mismo instante (o la última cae al final
            // de la canción) se le da una duración mínima en lugar de perderla.
            val endMs = if (rawEnd > startMs) rawEnd else startMs + 1000L
            if (text.isBlank()) continue
            sb.append('\n')
                .append(vttTime(startMs)).append(" --> ").append(vttTime(endMs)).append('\n')
                .append(text.trim().replace("\n", " ")).append('\n')
        }
        return sb.toString()
    }

    private fun vttTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val h = total / 3600000
        val m = (total % 3600000) / 60000
        val s = (total % 60000) / 1000
        val t = total % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", h, m, s, t)
    }

    /**
     * Respuesta binaria con soporte de HEAD y de peticiones Range.
     *
     * El receptor de Cast pregunta por rangos antes de reproducir: si a un
     * `Range: bytes=...` se le contesta 200 con el archivo entero en lugar de un
     * 206 con `Content-Range`, la carga puede quedarse colgada o fallar con un
     * error de medio (y con ella desaparecen los subtítulos).
     */
    private fun writeBytes(
        socket: Socket,
        contentType: String,
        bytes: ByteArray,
        rangeHeader: String?,
        headOnly: Boolean,
        origin: String? = null
    ) {
        val total = bytes.size
        val range = parseRange(rangeHeader, total)
        val start = range?.first ?: 0
        val end = range?.second ?: (total - 1)
        val length = (end - start + 1).coerceAtLeast(0)
        val sb = StringBuilder()
        sb.append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (range != null) sb.append("Content-Range: bytes ").append(start).append('-')
            .append(end).append('/').append(total).append("\r\n")
        sb.append(corsHeaders(origin))
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")

        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (!headOnly && length > 0) out.write(bytes, start, length)
        out.flush()
    }

    /**
     * Interpreta `Range: bytes=a-b`, `bytes=a-` o `bytes=-n`.
     * Devuelve null si no hay rango utilizable (se responde el archivo entero).
     */
    internal fun parseRange(header: String?, total: Int): Pair<Int, Int>? {
        if (total <= 0 || header.isNullOrBlank()) return null
        val spec = header.substringAfter("bytes=", "").trim()
        // Rangos múltiples: se ignora y se manda todo (el receptor no los usa).
        if (spec.isEmpty() || spec.contains(',')) return null
        val startRaw = spec.substringBefore('-').trim()
        val endRaw = spec.substringAfter('-', "").trim()
        val start = startRaw.toIntOrNull()
        if (start != null) {
            val s = start.coerceIn(0, total - 1)
            val e = endRaw.toIntOrNull()?.coerceIn(s, total - 1) ?: (total - 1)
            return s to e
        }
        // Sufijo: "bytes=-500" = los últimos 500 bytes.
        val suffix = endRaw.toIntOrNull() ?: return null
        val len = suffix.coerceIn(1, total)
        return (total - len) to (total - 1)
    }

    /**
     * Audio SILENCIOSO (MP3) para el Chromecast, con la duración de la canción.
     *
     * Es el modo "el audio se queda en el teléfono": el receptor recibe una
     * pista MP3 válida pero muda, de modo que muestra portada + título + letra
     * (subtítulos) mientras la música real sigue sonando en el teléfono.
     */
    private fun silentMp3(durationMs: Long): ByteArray {
        val wanted = durationMs.coerceIn(1000L, 60L * 60L * 1000L)
        silenceCache?.let { (cachedMs, bytes) -> if (cachedMs == wanted) return bytes }

        // MPEG-1 Layer III, 128 kbps, 44.1 kHz, mono: 417 bytes por frame y
        // 1152 muestras de silencio (side info a cero = sin datos = silencio).
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0xC4.toByte())
        val frameSize = 417
        val samplesPerFrame = 1152
        val totalSamples = (44100.0 * wanted / 1000.0).toLong()
        val frames = ((totalSamples + samplesPerFrame - 1) / samplesPerFrame).toInt().coerceAtLeast(1)

        val out = ByteArray(frames * frameSize)
        var offset = 0
        val frame = ByteArray(frameSize)
        for (i in 0 until 4) frame[i] = header[i]
        while (offset < out.size) {
            System.arraycopy(frame, 0, out, offset, frameSize)
            offset += frameSize
        }
        silenceCache = wanted to out
        return out
    }

    private fun writeResponse(
        socket: Socket,
        code: Int,
        contentType: String,
        body: String,
        origin: String? = null
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "404 Not Found"
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            corsHeaders(origin) +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    /**
     * IP local que el receptor debe usar para llegar al teléfono.
     *
     * Se prioriza la interfaz Wi-Fi (wlan*): el Chromecast vive en la misma red
     * Wi-Fi, y otras interfaces activas (VPN tun*, tethering, datos móviles)
     * devuelven una IP que el TV no puede alcanzar. Si la IP es inalcanzable el
     * receptor no carga ni el medio ni la letra, y la pantalla se queda vacía.
     */
    private fun localIp(): String? = runCatching {
        val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
        fun ipv4Of(predicate: (NetworkInterface) -> Boolean): String? = ifaces
            .filter(predicate)
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress

        ipv4Of { it.name.startsWith("wlan", ignoreCase = true) }
            ?: ipv4Of { it.name.startsWith("eth", ignoreCase = true) }
            ?: ipv4Of { true }
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

