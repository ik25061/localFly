package com.example.localfly.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineQueuePolicyTest {

    @Test
    fun excludesUnavailableSongsWithoutChangingTheSelectedOrder() {
        val selected = listOf("track-4", "track-1", "track-3", "track-2")
        val downloaded = setOf("track-1", "track-2", "track-4")

        val queue = OfflineQueuePolicy.filterAvailableInOriginalOrder(selected) {
            it in downloaded
        }

        assertEquals(listOf("track-4", "track-1", "track-2"), queue)
    }

    @Test
    fun emptyDownloadedSelectionProducesAnEmptyQueue() {
        val queue = OfflineQueuePolicy.filterAvailableInOriginalOrder(
            listOf("a", "b", "c")
        ) { false }

        assertEquals(emptyList<String>(), queue)
    }
}
