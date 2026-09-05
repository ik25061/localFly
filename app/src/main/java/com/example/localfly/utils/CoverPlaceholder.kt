package com.example.localfly.utils

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * Portadas de respaldo estéticas: sustituye el antiguo icono de música
 * por un color "aleatorio" pero estable (determinista por nombre/id),
 * con un degradado sutil, para canciones, artistas, álbumes, géneros,
 * años o listas sin portada.
 Un mismo elemento siempre recibe el mismo color
 * (evita parpadeos al hacer scroll) y elementos distintos reciben
 * colores distintos de la paleta.
 */
object CoverPlaceholder {

    private val PALETTE = intArrayOf(
        Color.parseColor("#E53935"), // rojo
        Color.parseColor("#D81B60"), // rosa
        Color.parseColor("#8E24AA"), // púrpura
        Color.parseColor("#5E35B1"), // violeta
        Color.parseColor("#3949AB"), // índigo
        Color.parseColor("#1E88E5"), // azul
        Color.parseColor("#039BE5"), // azul claro
        Color.parseColor("#00897B"), // verde azulado
        Color.parseColor("#43A047"), // verde
        Color.parseColor("#7CB342"), // verde lima
        Color.parseColor("#FDD835"), // amarillo
        Color.parseColor("#FB8C00"), // ámbar
        Color.parseColor("#F4511E"), // naranja
        Color.parseColor("#6D4C41"), // marrón
        Color.parseColor("#546E7A"), // azul grisáceo
        Color.parseColor("#C0CA33")  // lima
    )

    /** Devuelve el color pseudoaleatorio estable para un elemento (por nombre/id). */
    fun colorFor(seed: String?): Int {
        if (seed.isNullOrBlank()) return PALETTE[0]
        var hash = 7
        for (c in seed) {
            hash = Math.floorMod(hash * 31 + c.code, Int.MAX_VALUE)
        }
        return PALETTE[Math.floorMod(hash, PALETTE.size)]
    }

    /** Degradado diagonal con el color elegido, más oscuro hacia la esquina inferior derecha. */
    fun drawable(seed: String?): Drawable {
        val base = colorFor(seed)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(base, darken(base, 0.65f))
        )
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}