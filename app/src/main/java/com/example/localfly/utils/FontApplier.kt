package com.example.localfly.utils

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Aplica la fuente de letra elegida en Ajustes a todos los textos de una vista
 * (recorrido del árbol de vistas). Se usa en [com.example.localfly.MainActivity]
 * y [com.example.localfly.NowPlayingActivity].
 *
 * Se apoya en familias de fuente nativas de Android (no requiere archivos).
 * "Default" equivale a la familia base de la app (sans-serif-light).
 */
object FontApplier {

    /** Devuelve el Typeface correspondiente al nombre guardado en Ajustes. */
    fun typefaceFor(name: String): Typeface = when (name) {
        "Serif" -> Typeface.SERIF
        "Monospace" -> Typeface.MONOSPACE
        "Sans (Condensada)" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        "Sans (Media)" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        "Sans (Thin)" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
        "Sans (Ligera)" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        "Sans (Pequeñas caps)" -> Typeface.create("sans-serif-smallcaps", Typeface.NORMAL)
        "Cursiva" -> Typeface.create("cursive", Typeface.NORMAL)
        else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    /** Aplica la familia elegida a toda la vista (y sus descendientes). */
    fun apply(root: View, name: String) {
        applyRecursive(root, typefaceFor(name))
    }

    private fun applyRecursive(view: View, tf: Typeface) {
        if (view is TextView) view.typeface = tf
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), tf)
            }
        }
    }
}