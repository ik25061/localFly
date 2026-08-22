package com.example.localfly.utils

import com.example.localfly.network.Genre
import java.util.Locale

object GenreUtils {
    /**
     * Aplanamiento automático de géneros concatenados con ';'.
     * Parche para datos escaneados antes del fix del servidor.
     */
    fun flattenLegacyGenres(rawGenres: List<Genre>): List<Genre> {
        return rawGenres.flatMap { genre ->
            if (genre.name.contains(";")) {
                genre.name.split(";").map { part ->
                    genre.copy(name = part.trim())
                }
            } else {
                listOf(genre)
            }
        }.distinctBy { it.name.lowercase(Locale.getDefault()) }
    }
}