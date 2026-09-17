package com.example.localfly.utils

import com.example.localfly.network.Song
import java.util.Locale

/** Agrupa por género, nunca por artista. No inventa estilos si faltan metadatos. */
object GenreQueueOrder {
    fun genreKey(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun reorder(songs: List<Song>, seed: Song? = songs.firstOrNull()): List<Song> {
        val preferred = seed?.genre.orEmpty().map(::genreKey).filter { it.isNotEmpty() }.distinct()
        val groups = songs.distinctBy { it.id }.groupBy { song ->
            val genres = song.genre.orEmpty().map(::genreKey).filter { it.isNotEmpty() }
            preferred.firstOrNull { it in genres } ?: genres.firstOrNull().orEmpty()
        }
        val order = preferred + groups.keys.filter { it !in preferred && it.isNotEmpty() }.sorted() + ""
        return order.distinct().flatMap { groups[it].orEmpty() }
    }
}
