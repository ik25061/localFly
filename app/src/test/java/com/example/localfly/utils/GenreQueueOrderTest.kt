package com.example.localfly.utils

import com.example.localfly.network.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class GenreQueueOrderTest {
    private fun song(id: String, genre: List<String>?, artist: String = "Artist") = Song(
        id = id, title = id, artist = artist, album = null, year = null, duration = null,
        bpm = null, key = null, liked = false, hasCover = false, genre = genre
    )

    @Test fun seedGenreComesFirstRegardlessOfArtist() {
        val seed = song("seed", listOf(" Rock "), "A")
        val songs = listOf(song("pop", listOf("pop"), "A"), song("rock", listOf("ROCK"), "B"))
        assertEquals(listOf("rock", "pop"), GenreQueueOrder.reorder(songs, seed).map { it.id })
    }

    @Test fun groupsAreContiguousAndMissingGenresComeLast() {
        val songs = listOf(song("unknown", null), song("p1", listOf("Pop")),
            song("r1", listOf("rock")), song("p2", listOf(" pop ")), song("blank", listOf(" ")))
        assertEquals(listOf("p1", "p2", "r1", "unknown", "blank"),
            GenreQueueOrder.reorder(songs, null).map { it.id })
    }

    @Test fun multipleGenresUseSeedAffinityWithoutDuplicatingSongs() {
        val seed = song("seed", listOf("Jazz", "jazz"))
        val track = song("fusion", listOf("funk", "JAZZ"))
        val songs = listOf(song("rock", listOf("rock")), track, track)
        assertEquals(listOf("fusion", "rock"), GenreQueueOrder.reorder(songs, seed).map { it.id })
    }

    @Test fun emptyQueueIsSupported() {
        assertEquals(emptyList<Song>(), GenreQueueOrder.reorder(emptyList()))
    }
}
