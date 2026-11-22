package com.example.localfly

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Proporciona la configuracion del framework de Chromecast.
 *
 * Usa el receptor personalizado de LocalFly cuando se configura
 * CAST_RECEIVER_APP_ID. Si no se define, conserva el Default Media Receiver
 * (CC1AD845) como fallback; ese receptor no puede mostrar la interfaz karaoke
 * personalizada.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastReceiverAppId)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
        val CastReceiverAppId: String
            get() = BuildConfig.CAST_RECEIVER_APP_ID
        val isCustomReceiverEnabled: Boolean
            get() = CastReceiverAppId != DEFAULT_MEDIA_RECEIVER_APP_ID
    }
}