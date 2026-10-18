package com.theacar.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MusicLogUploaderTest {
    private val record = MusicLogRecord("r1", 0, 60_000, listOf(LoggedTrack("isrc:A", "A", "X", null, 0, 60_000)), emptyList())

    private class Store : PendingMusicLogStore {
        val files = linkedMapOf<String, String>()
        val readFailures = mutableMapOf<String, Exception>()
        override suspend fun save(id: String, json: String) { files[id] = json }
        override suspend fun pending() = files.keys.toList()
        override suspend fun read(id: String): String {
            readFailures[id]?.let { throw it }
            return files.getValue(id)
        }
        override suspend fun delete(id: String) { files.remove(id) }
    }

    private class Sender(override val configured: Boolean = true, var reply: () -> Boolean = { true }) : MusicLogSender {
        val sent = mutableListOf<String>()
        override suspend fun send(json: String): Boolean { sent += json; return reply() }
    }

    @Test fun deletesTheRecordOnceAccepted() = runTest {
        val store = Store()
        val sender = Sender()
        MusicLogUploader(sender, store, { "device" }).submit(record)
        assertEquals(record.id, parseMusicLogRecord(sender.sent.single()).id)
        assertTrue(store.files.isEmpty())
    }

    @Test fun keepsRejectedOrFailedRecordsAndRetriesThemLater() = runTest {
        val store = Store()
        val sender = Sender(reply = { false })
        val errors = mutableListOf<String>()
        val uploader = MusicLogUploader(sender, store, { "device" }) { message, _ -> errors += message }
        uploader.submit(record)
        sender.reply = { error("offline") }
        uploader.retryPending()
        assertEquals(listOf("r1"), store.files.keys.toList())
        assertEquals(2, errors.size)
        sender.reply = { true }
        uploader.retryPending()
        assertTrue(store.files.isEmpty())
    }

    @Test fun dropsBrokenRecordsWithoutSending() = runTest {
        val store = Store().apply { files["broken"] = "{" }
        val sender = Sender()
        MusicLogUploader(sender, store, { "device" }).retryPending()
        assertTrue(store.files.isEmpty())
        assertTrue(sender.sent.isEmpty())
    }

    @Test fun doesNothingWithoutADestination() = runTest {
        val store = Store()
        MusicLogUploader(Sender(configured = false), store, { "device" }).submit(record)
        assertTrue(store.files.isEmpty())
    }

    @Test fun unreadableRecordDoesNotBlockOtherRecordsAndCanBeRetried() = runTest {
        val store = Store().apply {
            files["r1"] = record.toJson("device")
            files["r2"] = record.copy(id = "r2").toJson("device")
            files["r3"] = record.copy(id = "r3").toJson("device")
            readFailures["r2"] = IllegalStateException("unreadable")
        }
        val sender = Sender()
        val errors = mutableListOf<Throwable?>()
        val uploader = MusicLogUploader(sender, store, { "device" }) { _, error -> errors += error }
        uploader.retryPending()
        assertEquals(listOf("r1", "r3"), sender.sent.map { parseMusicLogRecord(it).id })
        assertEquals(listOf("r2"), store.files.keys.toList())
        assertSame(store.readFailures["r2"], errors.single())

        store.readFailures.clear()
        uploader.retryPending()
        assertEquals(listOf("r1", "r3", "r2"), sender.sent.map { parseMusicLogRecord(it).id })
        assertTrue(store.files.isEmpty())
    }

    @Test fun failedSendDoesNotBlockFollowingRecords() = runTest {
        val store = Store().apply {
            files["r1"] = record.toJson("device")
            files["r2"] = record.copy(id = "r2").toJson("device")
        }
        var attempts = 0
        val sender = Sender(reply = { if (attempts++ == 0) error("offline") else true })
        MusicLogUploader(sender, store, { "device" }).retryPending()
        assertEquals(listOf("r1", "r2"), sender.sent.map { parseMusicLogRecord(it).id })
        assertEquals(listOf("r1"), store.files.keys.toList())
    }

    @Test fun cancelledReadStopsRetryWithoutDeletingRecords() = runTest {
        val store = Store().apply {
            files["r1"] = record.toJson("device")
            files["r2"] = record.copy(id = "r2").toJson("device")
            readFailures["r1"] = CancellationException("cancelled")
        }
        val sender = Sender()
        assertFailsWith<CancellationException> {
            MusicLogUploader(sender, store, { "device" }).retryPending()
        }
        assertTrue(sender.sent.isEmpty())
        assertEquals(listOf("r1", "r2"), store.files.keys.toList())
    }
}
