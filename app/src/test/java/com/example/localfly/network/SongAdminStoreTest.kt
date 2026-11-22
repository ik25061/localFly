package com.example.localfly.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongAdminStoreTest {

    @Test
    fun pendingDeletionsAreVisibleOnlyToTheAccountThatQueuedThem() {
        val pending = listOf(
            DislikedSong("song-1", "Canción 1", ownerUserId = "user-a"),
            DislikedSong("song-2", "Canción 2", ownerUserId = "user-b"),
            // Legacy requests without an owner are never auto-sent to an account.
            DislikedSong("song-3", "Canción 3")
        )

        assertEquals(
            listOf("song-1"),
            SongAdminStore.filterPendingDeletionsForUser(pending, "user-a").map { it.songId }
        )
        assertEquals(
            listOf("song-2"),
            SongAdminStore.filterPendingDeletionsForUser(pending, "user-b").map { it.songId }
        )
        assertTrue(SongAdminStore.filterPendingDeletionsForUser(pending, "").isEmpty())
    }
}
