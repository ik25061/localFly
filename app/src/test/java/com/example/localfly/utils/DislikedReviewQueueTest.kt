package com.example.localfly.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DislikedReviewQueueTest {

    private fun order(
        pending: List<String>,
        currentId: String?,
        available: Set<String> = pending.toSet(),
        played: Set<String> = emptySet()
    ) = DislikedReviewQueue.order(
        pending = pending,
        idOf = { it },
        currentId = currentId,
        isAvailable = { it in available },
        alreadyPlayed = played
    )

    @Test fun currentSongIsNeverIncluded() {
        assertEquals(listOf("b", "c"), order(listOf("a", "b", "c"), currentId = "a"))
    }

    @Test fun keepsTheOrderOfThePendingList() {
        assertEquals(listOf("c", "b"), order(listOf("c", "b", "a"), currentId = "a"))
    }

    @Test fun excludesUnavailableSongsWhenOffline() {
        // Sin conexión solo se pueden revisar las descargadas.
        assertEquals(
            listOf("b"),
            order(listOf("a", "b", "c"), currentId = "a", available = setOf("a", "b"))
        )
    }

    @Test fun prioritizesSongsNotPlayedYetInTheSession() {
        assertEquals(
            listOf("c"),
            order(listOf("b", "c"), currentId = "a", played = setOf("b"))
        )
    }

    @Test fun cyclesAgainWhenEverythingWasAlreadyPlayed() {
        assertEquals(
            listOf("b"),
            order(listOf("b"), currentId = "a", played = setOf("b"))
        )
    }

    @Test fun emptyWhenThereIsNoOtherPendingSong() {
        assertTrue(order(listOf("a"), currentId = "a").isEmpty())
        assertTrue(order(emptyList(), currentId = "a").isEmpty())
    }
}
