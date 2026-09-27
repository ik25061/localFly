package com.example.localfly

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencia de "donde suena el audio" cuando se transmite al Chromecast.
 *
 * Hay dos modos posibles y el usuario elige cual quiere:
 *  - [AudioTarget.TV]     la musica suena en el televisor (y la letra sale como
 *                         subtitulo). El telefono queda en silencio.
 *  - [AudioTarget.PHONE]  la musica sigue sonando en el telefono y el
 *                         televisor solo muestra la letra (karaoke).
 *
 * Se guarda en preferencias normales: no es informacion sensible y asi se
 * lee de forma sincrona al construir la pista para el receptor.
 */
object CastSettings {

    /** Destino del audio durante una transmision al televisor. */
    enum class AudioTarget {
        /** El audio se reproduce en el televisor. */
        TV,

        /** El audio se reproduce solo en el telefono (TV en modo karaoke). */
        PHONE;

        val label: String
            get() = when (this) {
                TV -> "En el televisor"
                PHONE -> "En este teléfono"
            }
    }

    private const val PREFS = "localfly_cast"
    private const val KEY_AUDIO_TARGET = "audio_target"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Destino elegido; por defecto el audio sigue en el telefono (karaoke). */
    fun audioTarget(context: Context): AudioTarget {
        val raw = prefs(context).getString(KEY_AUDIO_TARGET, AudioTarget.PHONE.name)
        return runCatching { AudioTarget.valueOf(raw ?: AudioTarget.PHONE.name) }
            .getOrDefault(AudioTarget.PHONE)
    }

    /** true si la musica debe seguir sonando en el telefono. */
    fun audioStaysOnPhone(context: Context): Boolean =
        audioTarget(context) == AudioTarget.PHONE

    fun setAudioTarget(context: Context, target: AudioTarget) {
        prefs(context).edit().putString(KEY_AUDIO_TARGET, target.name).apply()
    }
}
