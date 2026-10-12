package com.example.localfly.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utilidad para guardar logs persistentes en el dispositivo.
 * Ayuda a diagnosticar cierres inesperados o fallos de red.
 */
object LocalLogger {
    private const val TAG = "LocalFlyLogger"
    private const val MAX_LOG_SIZE = 1024 * 1024 // 1 MB

    fun log(context: Context, message: String, e: Throwable? = null) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message ${e?.let { "\n" + Log.getStackTraceString(it) } ?: ""}\n"
        
        Log.d(TAG, logLine)
        
        try {
            val logFile = File(context.filesDir, "app_debug_log.txt")
            
            // Rotación simple: si el archivo es muy grande, borrarlo
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                logFile.delete()
            }
            
            FileWriter(logFile, true).use { writer ->
                writer.append(logLine)
            }
        } catch (err: Exception) {
            Log.e(TAG, "No se pudo escribir en el archivo de log", err)
        }
    }

    /**
     * Configura un capturador global de excepciones para registrar crashes.
     */
    fun initCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Contexto del dispositivo y de la app: sin estos datos un crash
            // reportado por el usuario es casi imposible de reproducir.
            val device = buildString {
                append("modelo=").append(android.os.Build.MODEL)
                append(" fabricante=").append(android.os.Build.MANUFACTURER)
                append(" android=").append(android.os.Build.VERSION.RELEASE)
                append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(')')
                try {
                    val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                    append(" appVersion=").append(info.versionName)
                } catch (_: Exception) {}
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L
                val maxMb = rt.maxMemory() / 1048576L
                append(" memoria=${usedMb}MB/${maxMb}MB")
            }
            log(appContext, "=== CRASH FATAL === hilo='${thread.name}' $device", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun getLogPath(context: Context): String {
        return File(context.filesDir, "app_debug_log.txt").absolutePath
    }
}
