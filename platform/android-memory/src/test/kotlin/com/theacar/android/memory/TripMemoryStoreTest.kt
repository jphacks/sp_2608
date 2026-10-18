package com.theacar.android.memory

import com.theacar.memory.TripMemory
import com.theacar.memory.summary
import com.theacar.model.Coordinate
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TripMemoryStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val memory = TripMemory("trip", 0, 1_000, 100, 1, "目的地", Coordinate(43.0, 141.0))

    @Test fun pendingUploadsSurviveStoreRecreation() = runTest {
        val root = folder.newFolder()
        val store = TripMemoryStore(root)
        store.save(memory)
        store.enqueuePublication(memory.id)
        val reopened = TripMemoryStore(root)
        assertEquals(listOf(memory.id), reopened.pendingIds())
        val packet = assertNotNull(reopened.publication(memory.id))
        assertEquals(memory, packet.memory)
        reopened.markPublished(memory.id, packet.revision)
        assertTrue(reopened.pendingIds().isEmpty())
    }

    @Test fun newPendingRevisionIsNotClearedByOlderUpload() = runTest {
        val store = TripMemoryStore(folder.newFolder())
        store.save(memory)
        store.enqueuePublication(memory.id)
        val old = assertNotNull(store.publication(memory.id))
        store.enqueuePublication(memory.id)
        store.markPublished(memory.id, old.revision)
        assertEquals(listOf(memory.id), store.pendingIds())
        assertNotEquals(old.revision, store.publication(memory.id)?.revision)
    }

    @Test fun savingPhotoNotifiesTheListAfterTheFileIsReady() = runTest {
        val store = TripMemoryStore(folder.newFolder())
        store.save(memory)
        store.writePhoto(memory.id) { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val first = assertNotNull(store.memories.value.single().photoVersion)
        assertContentEquals(byteArrayOf(1, 2, 3), store.photoFile(memory.id).readBytes())
        store.writePhoto(memory.id) { it.writeBytes(byteArrayOf(4, 5)) }
        assertTrue(assertNotNull(store.memories.value.single().photoVersion) > first)
        assertEquals(store.memories.value.single(), store.read(memory.id)?.summary())
    }

    @Test fun failedPhotoWriteDoesNotOverwriteThePreviousFile() = runTest {
        val store = TripMemoryStore(folder.newFolder())
        store.save(memory)
        store.writePhoto(memory.id) { it.writeText("original") }
        val version = store.memories.value.single().photoVersion
        assertFailsWith<IllegalStateException> {
            store.writePhoto(memory.id) { it.writeText("partial"); error("disk failure") }
        }
        assertEquals("original", store.photoFile(memory.id).readText())
        assertEquals(version, store.memories.value.single().photoVersion)
    }

    @Test fun updatingOneSummaryPreservesOtherRecordsAndFullGeometry() = runTest {
        val store = TripMemoryStore(folder.newFolder())
        val geometry = listOf(Coordinate(43.0, 141.0), Coordinate(43.1, 141.1))
        store.save(memory.copy(geometry = geometry))
        store.save(memory.copy(id = "other", arrivedAtEpochMillis = 2_000))
        store.update(memory.id) { it.copy(members = listOf("葵")) }
        assertEquals(listOf("other", "trip"), store.memories.value.map { it.id })
        assertEquals(listOf("葵"), store.memories.value.last().members)
        assertEquals(geometry, store.read(memory.id)?.geometry)
    }
}
