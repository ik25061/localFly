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
 */
object RescanManager {
    private val _progress = MutableStateFlow(RescanProgress())
    val progress: StateFlow<RescanProgress> = _progress

    private var monitoringJob: Job? = null
    private val client = OkHttpClient()
    private val gson = Gson()
    
    private var lastUpdateMs = 0L
    private var lastProcessedCount = -1
    
    // Almacenamos el último scope recibido para tareas diferidas (ej: auto-hide)
    private var activeScope: CoroutineScope? = null

    fun triggerRescan(scope: CoroutineScope) {
        activeScope = scope
        val current = _progress.value
        val isStuck = (current.phase == "scanning" || current.phase == "building") && 
                      (System.currentTimeMillis() - lastUpdateMs > 30000)

        if (current.phase != "idle" && current.phase != "done" && current.phase != "error" && !isStuck) {
            return 
        }

        startMonitoring(scope)

        scope.launch(Dispatchers.IO) {
            try {
                _progress.value = RescanProgress(phase = "building", message = "Iniciando escaneo...")
                RetrofitClient.api.rescanLibrary()
            } catch (e: Exception) {
                _progress.value = RescanProgress(phase = "error", message = "Fallo de conexión: ${e.message}")
            }
        }
    }

    fun startMonitoring(scope: CoroutineScope) {
        activeScope = scope
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch(Dispatchers.IO) {
            val url = "${ApiConfig.BASE_URL}/api/rescan-stream"
            val request = Request.Builder().url(url).build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use

                    val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                    var line: String?
                    while (isActive) {
                        line = reader.readLine() ?: break
                        if (line.startsWith("data: ")) {
                            val json = line.substring(6)
                            try {
                                val event = gson.fromJson(json, RescanStreamEvent::class.java)
                                updateFromEvent(event)
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                monitoringJob = null
                if (_progress.value.phase != "error" && _progress.value.phase != "done") {
                    delay(2000)
                    startMonitoring(scope)
                }
            }
        }
    }

    private fun updateFromEvent(event: RescanStreamEvent) {
        if (event.type == "progress") {
            var msg = event.message ?: ""
            if (event.total != null && event.total > 0 && event.processed != null) {
                msg = "Revisando canción ${event.processed} de ${event.total}"
                
                if (event.processed != lastProcessedCount) {
                    lastProcessedCount = event.processed
                    lastUpdateMs = System.currentTimeMillis()
                }
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
            _progress.value = _progress.value.copy(phase = "done", pct = 100, message = "¡Escaneo completado!")
            
            // Auto-ocultar banner tras unos segundos de éxito
            activeScope?.launch {
                delay(8000)
                if (_progress.value.phase == "done") {
                    _progress.value = RescanProgress(phase = "idle")
                }
            }
        }
    }
}
