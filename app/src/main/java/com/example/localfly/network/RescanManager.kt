package com.example.localfly.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Gestiona el monitoreo del reescaneo de la biblioteca en tiempo real.
 * Se conecta al endpoint SSE /api/rescan-stream del servidor.
 */
object RescanManager {
    private val _progress = MutableStateFlow(RescanProgress())
    val progress: StateFlow<RescanProgress> = _progress

    private var monitoringJob: Job? = null
    private val client = OkHttpClient()
    private val gson = Gson()

    /**
     * Inicia la orden de reescaneo en el servidor y arranca el monitoreo
     * del progreso a través del stream.
     */
    fun triggerRescan(scope: CoroutineScope) {
        if (_progress.value.phase != "idle" && _progress.value.phase != "done" && _progress.value.phase != "error") {
            return // Ya hay uno en curso
        }

        startMonitoring(scope)

        scope.launch(Dispatchers.IO) {
            try {
                _progress.value = RescanProgress(phase = "building", message = "Iniciando escaneo...")
                val response = RetrofitClient.api.rescanLibrary()
                if (!response.isSuccessful) {
                    _progress.value = RescanProgress(phase = "error", message = "El servidor rechazó la orden.")
                }
            } catch (e: Exception) {
                _progress.value = RescanProgress(phase = "error", message = "Fallo de conexión: ${e.message}")
            }
        }
    }

    /**
     * Se conecta al stream de eventos del servidor para recibir el progreso
     * segundo a segundo.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch(Dispatchers.IO) {
            val url = "${ApiConfig.BASE_URL}/api/rescan-stream"
            val request = Request.Builder().url(url).build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (_progress.value.phase == "idle") _progress.value = RescanProgress(phase = "idle")
                        return@use
                    }

                    val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                    var line: String?
                    while (isActive) {
                        line = reader.readLine() ?: break
                        if (line.startsWith("data: ")) {
                            val json = line.substring(6)
                            try {
                                val event = gson.fromJson(json, RescanStreamEvent::class.java)
                                updateFromEvent(event)
                            } catch (e: Exception) {
                                // Ignorar líneas corruptas o latidos del corazón (heartbeats)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // El stream puede cerrarse al terminar o por timeout
            } finally {
                monitoringJob = null
                // Si terminamos y la fase no es error o done, lo ponemos a idle tras un rato
                if (_progress.value.phase != "error" && _progress.value.phase != "done") {
                    // La conexión se cerró pero no vimos un final real (done/error):
                    // probablemente fue el timeout de la propia conexión HTTP, no del
                    // reescaneo. Reconectar en vez de dar por hecho que ya acabó.
                    delay(2000)
                    startMonitoring(scope)
                }
            }
        }
    }

    private fun updateFromEvent(event: RescanStreamEvent) {
        if (event.type == "progress") {
            var msg = event.message ?: ""
            
            // Si el servidor nos da números (procesados/total), creamos un mensaje más detallado
            if (event.total != null && event.total > 0 && event.processed != null) {
                msg = "Revisando canción ${event.processed} de ${event.total}"
            }

            _progress.value = RescanProgress(
                phase = event.phase ?: "running",
                pct = event.pct ?: 0,
                processed = event.processed ?: 0,
                total = event.total ?: 0,
                message = msg,
                totalSongsLibrary = event.totalSongsLibrary ?: 0,
                durationSec = event.durationSec ?: 0
            )
        } else if (event.type == "done") {
            _progress.value = _progress.value.copy(
                phase = "done", 
                pct = 100, 
                message = "¡Escaneo completado!"
            )
            // No limpiar inmediatamente para que el usuario vea el éxito
            GlobalScope.launch {
                delay(10000)
                if (_progress.value.phase == "done") {
                    _progress.value = RescanProgress(phase = "idle")
                }
            }
        }
    }
}
