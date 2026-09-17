package com.example.localfly.karaoke

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.example.localfly.utils.LocalLogger

/**
 * Motor de audio del MODO KARAOKE.
 *
 * Captura el micrófono del teléfono en tiempo real ([AudioRecord], fuente
 * VOICE_COMMUNICATION con fallback a MIC) y lo escribe en un [AudioTrack] de
 * baja latencia. El instrumental sigue sonando por ExoPlayer en el mismo
 * dispositivo de salida, así que el mezclador de Android (AudioFlinger) suma
 * ambas señales: instrumental + voz en vivo hacia el altavoz/Bluetooth.
 *
 * - Se activan AcousticEchoCanceler y NoiseSuppressor para evitar
 *   retroalimentación (el mic capta el propio altavoz).
 * - El AudioTrack se enruta con setPreferredDevice() al altavoz Bluetooth
 *   (A2DP) si hay uno conectado, y si no a cable/altavoz interno.
 * - [micVolume] controla el volumen independiente de la voz (0.0 - 2.0).
 *
 * Requiere el permiso RECORD_AUDIO concedido ANTES de llamar a [start].
 */
class KaraokeMicEngine(private val context: Context) {

    companion object {
        private const val CHUNK = 1024 // ~21 ms @ 48 kHz mono PCM16
        private const val SAMPLE_RATE = 48000
    }

    @Volatile
    private var running = false

    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** Volumen de la voz captada (0.0 - 2.0). Ajustable en vivo. */
    @Volatile
    var micVolume: Float = 1.2f

    val isRunning: Boolean get() = running

    /** true si el micrófono quedó capturando y mezclando. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val track = buildOutputTrack()
        if (track == null) {
            LocalLogger.log(context, "Karaoke: no se pudo abrir el AudioTrack de salida")
            return false
        }
        val record = buildInputRecord()
        if (record == null) {
            track.release()
            LocalLogger.log(context, "Karaoke: no se pudo abrir el micrófono (¿permiso RECORD_AUDIO?)")
            return false
        }

        audioTrack = track
        audioRecord = record
        running = true
        captureThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            loop(record, track)
        }.apply {
            name = "LocalFlyKaraokeMic"
            start()
        }
        LocalLogger.log(context, "Karaoke: micrófono activo, mezclando con el instrumental")
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        try { captureThread?.join(1500) } catch (_: Exception) {}
        captureThread = null
        try { aec?.enabled = false; aec?.release() } catch (_: Exception) {}
        try { ns?.enabled = false; ns?.release() } catch (_: Exception) {}
        aec = null
        ns = null
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
        LocalLogger.log(context, "Karaoke: micrófono detenido")
    }

    private fun loop(record: AudioRecord, track: AudioTrack) {
        val buffer = ShortArray(CHUNK)
        try {
            record.startRecording()
            track.play()
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val vol = micVolume
                    if (vol != 1f) {
                        for (i in 0 until read) {
                            val scaled = (buffer[i] * vol).toInt()
                            buffer[i] = scaled.coerceIn(-32768, 32767).toShort()
                        }
                    }
                    track.write(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            LocalLogger.log(context, "Karaoke: bucle de captura interrumpido (${e.message})", e)
        } finally {
            try { record.stop() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
        }
    }

    private fun buildInputRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )) {
            try {
                val rec = AudioRecord(
                    source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    // AEC y NS sobre la sesión del micrófono: evitan que el mic
                    // capte el sonido del propio altavoz (feedback del karaoke).
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            aec = AcousticEchoCanceler.create(rec.audioSessionId)
                            aec?.enabled = true
                        }
                    } catch (e: Exception) {
                        LocalLogger.log(context, "Karaoke: AEC no disponible (${e.message})")
                    }
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            ns = NoiseSuppressor.create(rec.audioSessionId)
                            ns?.enabled = true
                        }
                    } catch (e: Exception) {
                        LocalLogger.log(context, "Karaoke: NS no disponible (${e.message})")
                    }
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                LocalLogger.log(context, "Karaoke: fuente $source no disponible (${e.message})")
            }
        }
        return null
    }

    private fun buildOutputTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                null
            } else {
                applyPreferredOutput(track)
                track
            }
        } catch (e: Exception) {
            LocalLogger.log(context, "Karaoke: error creando AudioTrack (${e.message})", e)
            null
        }
    }

    /** Enruta la voz al altavoz Bluetooth (A2DP) si está conectado; si no,
     *  a auriculares cableados o al altavoz del teléfono. */
    fun applyPreferredOutput(track: AudioTrack) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val preferred = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            preferred?.let {
                track.preferredDevice = it
                LocalLogger.log(context, "Karaoke: voz enrutada a ${deviceName(it.type)}")
            }
        } catch (e: Exception) {
            LocalLogger.log(context, "Karaoke: no se pudo fijar dispositivo de salida (${e.message})")
        }
    }

    private fun deviceName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "altavoz Bluetooth (A2DP)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "auriculares cableados"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "auricular con micro cableado"
        else -> "altavoz del teléfono"
    }
}

