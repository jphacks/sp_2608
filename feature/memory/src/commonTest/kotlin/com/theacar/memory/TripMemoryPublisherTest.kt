package com.theacar.memory

import com.theacar.model.Coordinate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TripMemoryPublisherTest {
    private val memory = TripMemory("trip", 0, 1_000, 100, 1, "目的地", Coordinate(43.0, 141.0))
    private class Store : TripPublicationStore {
        val items = linkedMapOf<String, TripPublication>()
        var broken: String? = null
        override suspend fun pendingIds() = items.keys.toList()
        override suspend fun publication(id: String): TripPublication? {
            check(id != broken)
            return items[id]
        }
        override suspend fun markPublished(id: String, revision: String) {
            if (items[id]?.revision == revision) items.remove(id)
        }
    }
    private class Sender(val send: suspend (TripMemory) -> Boolean) : TripMemorySender {
        override val configured = true
        override suspend fun send(memory: TripMemory, photo: ByteArray?) = send(memory)
    }

    @Test fun unreadableRecordDoesNotBlockOtherRecords() = runTest {
        val store = Store().apply {
            items["bad"] = TripPublication(memory.copy(id = "bad"), null, "1")
            items["trip"] = TripPublication(memory, null, "1")
            broken = "bad"
        }
        val sent = mutableListOf<String>()
        TripMemoryPublisher(Sender { sent += it.id; true }, store).retryPending()
        assertEquals(listOf("trip"), sent)
        assertEquals(listOf("bad"), store.items.keys.toList())
    }

    @Test fun failedUploadRemainsPendingAndCanBeRetried() = runTest {
        val store = Store().apply { items["trip"] = TripPublication(memory, null, "1") }
        TripMemoryPublisher(Sender { false }, store).retryPending()
        assertEquals(1, store.items.size)
        TripMemoryPublisher(Sender { true }, store).retryPending()
        assertTrue(store.items.isEmpty())
    }

    @Test fun newExportDuringUploadIsNotRemoved() = runTest {
        val store = Store().apply { items["trip"] = TripPublication(memory, null, "1") }
        TripMemoryPublisher(Sender {
            store.items["trip"] = TripPublication(memory, null, "2")
            true
        }, store).retryPending()
        assertEquals("2", store.items["trip"]?.revision)
    }

    @Test fun cancellationDoesNotRemoveTheRecord() = runTest {
        val store = Store().apply { items["trip"] = TripPublication(memory, null, "1") }
        assertFailsWith<CancellationException> {
            TripMemoryPublisher(Sender { throw CancellationException() }, store).retryPending()
        }
        assertEquals(1, store.items.size)
    }
}
