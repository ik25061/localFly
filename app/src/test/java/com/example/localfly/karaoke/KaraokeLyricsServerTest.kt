package com.example.localfly.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La letra del TV se sirve como WebVTT: si este archivo sale mal el receptor
 * descarta los subtítulos en silencio (sin error visible), así que aquí se
 * fija el contrato que debe cumplir.
 */
class KaraokeLyricsServerTest {

    private val server = KaraokeLyricsServer()

    private fun cues(vtt: String): List<String> =
        vtt.substringAfter("WEBVTT\n\n", vtt).trim().split("\n\n").filter { it.isNotBlank() }

    @Test
    fun `webvtt empieza con la cabecera obligatoria`() {
        val vtt = server.buildVtt()
        assertTrue("Debe empezar por WEBVTT: '$vtt'", vtt.startsWith("WEBVTT\n\n"))
    }

    @Test
    fun `letra sincronizada genera un cue por linea con sus tiempos`() {
        server.setTrack(
            "s1", "Título", "Artista",
            listOf(0L to "Uno", 5000L to "Dos", 12000L to "Tres"),
            durationMs = 20000L
        )
        val vtt = server.buildVtt()
        assertTrue(vtt.contains("00:00:00.000 --> 00:00:05.000"))
        assertTrue(vtt.contains("00:00:05.000 --> 00:00:12.000"))
        assertTrue(vtt.contains("00:00:12.000 --> 00:00:19.700"))
        assertTrue(vtt.contains("Uno") && vtt.contains("Dos") && vtt.contains("Tres"))
        assertEquals(3, cues(vtt).size)
    }

    @Test
    fun `no se incluye bloque STYLE porque el estilo va por TextTrackStyle`() {
        server.setTrack("s1", "T", null, listOf(0L to "Uno"), durationMs = 10000L)
        assertFalse(server.buildVtt().contains("STYLE"))
    }

    /**
     * Regresión: la letra sin tiempos (guardada a mano) dejaba todas las líneas
     * en 0 ms y el VTT salía sin ningún cue, por eso el TV no mostraba nada.
     */
    @Test
    fun `letra sin tiempos se reparte a lo largo de la cancion`() {
        server.setTrack(
            "s2", "T", null,
            listOf(0L to "Uno", 0L to "Dos", 0L to "Tres"),
            durationMs = 180000L
        )
        // En un cue, lines()[0] es el rango de tiempos y lines()[1] el texto.
        val starts = cues(server.buildVtt()).map { it.lines()[0].substringBefore(" --> ") }
        assertEquals(3, starts.size)
        assertEquals(starts.sorted(), starts)
        assertEquals("00:00:00.000", starts.first())
        assertEquals(3, starts.toSet().size)
    }

    @Test
    fun `letra sin tiempos ni duracion sigue generando cues`() {
        server.setTrack("s3", "T", null, listOf(0L to "Uno", 0L to "Dos"), durationMs = 0L)
        assertEquals(2, cues(server.buildVtt()).size)
    }

    @Test
    fun `la ultima linea al final de la cancion no se pierde`() {
        // Caso límite: el fin calculado (duración - 300) cae antes del inicio.
        server.setTrack("s4", "T", null, listOf(0L to "Uno", 9970L to "Dos"), durationMs = 10000L)
        val vtt = server.buildVtt()
        assertTrue("La última línea debe sobrevivir: $vtt", vtt.contains("Dos"))
        assertEquals(2, cues(vtt).size)
    }

    @Test
    fun `tiempos duplicados no descartan la linea`() {
        server.setTrack("s5", "T", null, listOf(0L to "Uno", 0L to "Dos"), durationMs = 60000L)
        val vtt = server.buildVtt()
        assertTrue(vtt.contains("Uno") && vtt.contains("Dos"))
        assertEquals(2, cues(vtt).size)
    }

    @Test
    fun `lineas vacias y espacios se descartan`() {
        server.setTrack(
            "s6", "T", null,
            listOf(0L to "Uno", 1000L to "   ", 2000L to "", 3000L to "Cuatro"),
            durationMs = 10000L
        )
        val vtt = server.buildVtt()
        assertTrue(vtt.contains("Uno") && vtt.contains("Cuatro"))
        assertEquals(2, cues(vtt).size)
    }

    @Test
    fun `los saltos de linea internos no rompen el cue`() {
        server.setTrack("s7", "T", null, listOf(0L to "Uno\ndos"), durationMs = 10000L)
        val cue = cues(server.buildVtt()).first()
        assertEquals(2, cue.lines().size) // línea de tiempos + texto
        assertTrue(cue.contains("Uno dos"))
    }

    @Test
    fun `sin letra solo se envia la cabecera`() {
        server.setTrack("s8", "T", null, emptyList(), durationMs = 10000L)
        assertEquals("WEBVTT\n\n", server.buildVtt())
    }

    // ===== Peticiones Range del receptor (Shaka pide rangos al cargar) =====

    @Test
    fun `rango con inicio y fin devuelve el tramo pedido`() {
        assertEquals(0 to 99, server.parseRange("bytes=0-99", 1000))
        assertEquals(500 to 999, server.parseRange("bytes=500-", 1000))
    }

    @Test
    fun `rango sufijo devuelve los ultimos bytes`() {
        assertEquals(900 to 999, server.parseRange("bytes=-100", 1000))
    }

    @Test
    fun `rango fuera de limite se recorta y no desborda`() {
        assertEquals(999 to 999, server.parseRange("bytes=5000-", 1000))
        assertEquals(0 to 999, server.parseRange("bytes=0-99999", 1000))
    }

    @Test
    fun `cabeceras invalidas o ausentes devuelven null`() {
        assertNull(server.parseRange(null, 1000))
        assertNull(server.parseRange("", 1000))
        assertNull(server.parseRange("bytes=abc", 1000))
        assertNull(server.parseRange("items=0-10", 1000))
        assertNull(server.parseRange("bytes=0-10", 0))
        // Varios rangos: el receptor no los usa, se responde el archivo entero.
        assertNull(server.parseRange("bytes=0-10,20-30", 1000))
    }

    // ===== Servidor HTTP real: lo que ve el receptor de Cast =====

    @Test
    fun `las cabeceras CORS devuelven el mismo origin y permiten red privada`() {
        val headers = KaraokeLyricsServer.corsHeaders("https://abc.ccast.me")
        assertTrue(headers.contains("Access-Control-Allow-Origin: https://abc.ccast.me"))
        assertTrue(headers.contains("Access-Control-Allow-Credentials: true"))
        assertTrue(headers.contains("Access-Control-Allow-Private-Network: true"))
        assertTrue(headers.contains("Access-Control-Allow-Headers: Range, Content-Type, Accept-Encoding"))
        // Sin Origin (petición directa del navegador) se permite cualquiera.
        assertTrue(KaraokeLyricsServer.corsHeaders(null).contains("Access-Control-Allow-Origin: *"))
    }

    @Test
    fun `el preflight responde al receptor con las cabeceras que pide`() {
        val port = freePort()
        withServer(port) {
            val (head, _) = requestRaw(
                port, "/track.vtt?song=s1",
                listOf(
                    "Origin: https://abc.ccast.me",
                    "Access-Control-Request-Method: GET",
                    "Access-Control-Request-Headers: range,accept-encoding"
                ),
                method = "OPTIONS"
            )
            assertTrue("Debe ser 204: $head", head.startsWith("HTTP/1.1 204"))
            assertTrue(head.contains("Access-Control-Allow-Origin: https://abc.ccast.me"))
            assertTrue(head.contains("Access-Control-Allow-Headers: range,accept-encoding"))
            assertTrue(head.contains("Access-Control-Allow-Private-Network: true"))
            assertTrue(head.contains("Access-Control-Max-Age"))
        }
    }

    // ===== Reparto de tiempos =====

    @Test
    fun `una letra con la mayoria de tiempos se considera sincronizada`() {
        val lines = listOf(0L to "A", 1000L to "B", 2000L to "C")
        assertEquals(lines, server.withTimings(lines, 60000L))
    }

    @Test
    fun `una letra sin tiempos recibe posiciones crecientes`() {
        val timed = server.withTimings(listOf(0L to "A", 0L to "B", 0L to "C"), 90000L)
        assertEquals(3, timed.size)
        assertTrue(timed[0].first < timed[1].first && timed[1].first < timed[2].first)
        assertTrue(timed.all { it.first >= 0L })
    }

    // ===== Servidor HTTP: el proxy de audio del receptor =====

    /**
     * Regresión del fallo real: si el upstream del audio no responde, antes se
     * cerraba la conexión SIN contestar nada; el receptor interpreta eso como
     * error de medio y el televisor vuelve a su pantalla de reposo (solo el
     * icono de Cast) sin ningún aviso en el teléfono.
     */
    @Test
    fun `si el upstream del audio falla el proxy contesta 502 y no se queda mudo`() {
        val port = freePort()
        val deadUrl = "http://127.0.0.1:${freePort()}/audio/s1"
        withServer(port) {
            val (head, body) = requestRaw(
                port,
                "/audio?song=s1&u=" + java.net.URLEncoder.encode(deadUrl, "UTF-8"),
                listOf("Range: bytes=0-")
            )
            assertTrue("Debe contestar 502 y no quedarse en silencio: '$head'", head.startsWith("HTTP/1.1 502"))
            assertTrue(head.contains("Access-Control-Allow-Origin"))
            assertTrue(body.isNotEmpty())
        }
    }

    @Test
    fun `el proxy sirve el audio del upstream con CORS y soporte de rangos`() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val upstream = fakeUpstream(payload)
        val port = freePort()
        val url = "http://127.0.0.1:${upstream.address.port}/audio/s1"
        try {
            withServer(port) {
                val (head, body) = requestRaw(
                    port,
                    "/audio?song=s1&u=" + java.net.URLEncoder.encode(url, "UTF-8"),
                    listOf("Range: bytes=0-")
                )
                assertTrue("Debe ser 206: $head", head.startsWith("HTTP/1.1 206"))
                assertTrue(head.contains("Content-Type: audio/mpeg"))
                assertTrue(head.contains("Content-Length: ${payload.size}"))
                assertTrue(head.contains("Content-Range: bytes 0-${payload.size - 1}/${payload.size}"))
                assertTrue(head.contains("Accept-Ranges: bytes"))
                assertTrue(head.contains("Access-Control-Allow-Origin: *"))
                assertEquals(payload.size, body.size)
                assertTrue(body.contentEquals(payload))
            }
        } finally {
            upstream.stop(0)
        }
    }

    @Test
    fun `el mp3 de silencio dura lo pedido y tiene frames validos`() {
        val port = freePort()
        withServer(port) {
            val (head, body) = requestRaw(port, "/silence?ms=5000&song=s1")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(head.contains("Content-Type: audio/mpeg"))
            assertTrue(head.contains("Content-Length: ${body.size}"))
            // MPEG-1 Layer III mono a 128 kbps/44.1 kHz = 417 bytes por frame.
            assertEquals(0, body.size % 417)
            val frames = body.size / 417
            val durationMs = frames * 1152L * 1000L / 44100L
            assertTrue("Duración fuera de rango: $durationMs ms", durationMs in 4900..5100)
            assertEquals(0xFF.toByte(), body[0])
            assertEquals(0xFB.toByte(), body[1])
        }
    }

    @Test
    fun `la letra publicada se sirve como webvtt al receptor`() {
        val port = freePort()
        withServer(port) {
            it.setTrack("s1", "T", "A", listOf(0L to "Uno", 5000L to "Dos"), durationMs = 20000L)
            val (head, body) = requestRaw(port, "/track.vtt?song=s1")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(head.contains("Content-Type: text/vtt"))
            assertTrue(head.contains("Accept-Ranges: bytes"))
            val vtt = body.toString(Charsets.UTF_8)
            assertTrue(vtt.startsWith("WEBVTT"))
            assertTrue(vtt.contains("Uno") && vtt.contains("Dos"))
        }
    }

    // ===== Utilidades de las pruebas HTTP =====

    private fun withServer(port: Int, block: (KaraokeLyricsServer) -> Unit) {
        val instance = KaraokeLyricsServer(port)
        instance.start()
        try {
            block(instance)
        } finally {
            instance.stop()
        }
    }

    /** Puerto libre (se cierra enseguida para que lo use el servidor). */
    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /**
     * Servidor de audio falso que imita al backend de LocalFly: contesta 206 con
     * Content-Range/Content-Length cuando el cliente pide un rango.
     */
    private fun fakeUpstream(payload: ByteArray): com.sun.net.httpserver.HttpServer {
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/audio") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes 0-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, payload.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
            }
            exchange.responseBody.use { it.write(payload) }
        }
        http.start()
        return http
    }

    /** Petición HTTP cruda, como la que envía el receptor: cabeceras + cuerpo. */
    private fun requestRaw(
        port: Int,
        target: String,
        headers: List<String> = emptyList(),
        method: String = "GET"
    ): Pair<String, ByteArray> {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val head = StringBuilder("$method $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
            headers.forEach { head.append(it).append("\r\n") }
            head.append("Connection: close\r\n\r\n")
            socket.getOutputStream().apply {
                write(head.toString().toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val split = raw.indexOfHeaderEnd()
            if (split < 0) return raw.toString(Charsets.ISO_8859_1) to ByteArray(0)
            return raw.copyOfRange(0, split).toString(Charsets.ISO_8859_1) to
                raw.copyOfRange(split + 4, raw.size)
        }
    }

    /** Posición del CRLFCRLF que separa cabeceras y cuerpo, o -1. */
    private fun ByteArray.indexOfHeaderEnd(): Int {
        for (i in 0 until size - 3) {
            if (this[i] == 13.toByte() && this[i + 1] == 10.toByte() &&
                this[i + 2] == 13.toByte() && this[i + 3] == 10.toByte()
            ) return i
        }
        return -1
    }
}
