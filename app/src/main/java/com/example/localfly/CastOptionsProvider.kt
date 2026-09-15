package com.example.localfly

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Proporciona la configuracion del framework de Chromecast.
 *
 * Usa el "Default Media Player Receiver" de Google (CC1AD845), gratuito y
 * suficiente para reproducir URLs HTTP. Para un despliegue en produccion se
 * recomienda registrar una aplicacion de receptor propia en la Cast Developer
 * Console y reemplazar [CastReceiverAppId].
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastReceiverAppId)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        const val CastReceiverAppId = "CC1AD845"
    }
}