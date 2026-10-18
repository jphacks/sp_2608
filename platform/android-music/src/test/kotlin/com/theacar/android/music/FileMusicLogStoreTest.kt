package com.theacar.android.music

import com.theacar.music.*
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class FileMusicLogStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun failedWritePreservesThePreviousLog() = runTest {
        val directory = temporaryFolder.newFolder("logs")
        val store = FileMusicLogStore(directory)
        store.save("record", "original")
        val failing = FileMusicLogStore(directory) { file, _ -> file.writeText("partial"); throw java.io.IOException("disk full") }
        assertFailsWith<java.io.IOException> { failing.save("record", "new") }
        assertEquals("original", store.read("record"))
        assertEquals(listOf("record.json"), directory.list()?.toList())
    }

    @Test fun temporaryFilesAreNotRetried() = runTest {
        val directory = temporaryFolder.newFolder("logs")
        val store = FileMusicLogStore(directory)
        store.save("record", "original")
        store.save("record", "updated")
        File(directory, "interrupted.tmp").writeText("partial")
        assertEquals(listOf("record"), store.pending())
        assertEquals("updated", FileMusicLogStore(directory).read("record"))
    }

    @Test fun unreadableFileDoesNotPreventSendingOtherFiles() = runTest {
        val directory = temporaryFolder.newFolder("logs")
        val store = FileMusicLogStore(directory)
        val record = MusicLogRecord("a", 0, 60_000,
            listOf(LoggedTrack("isrc:A", "A", "X", null, 0, 60_000)), emptyList())
        store.save("a", record.toJson("device"))
        // 同名のディレクトリなら、実行ユーザーの権限にかかわらず読み取りに失敗する。
        val unreadable = File(directory, "b.json").apply { mkdir() }
        store.save("c", record.copy(id = "c").toJson("device"))
        val sent = mutableListOf<String>()
        val failures = mutableListOf<Throwable?>()
        val sender = object : MusicLogSender {
            override val configured = true
            override suspend fun send(json: String): Boolean {
                sent += parseMusicLogRecord(json).id
                return true
            }
        }
        MusicLogUploader(sender, store, { "device" }) { _, error -> failures += error }.retryPending()

        assertEquals(listOf("a", "c"), sent)
        assertEquals(1, failures.size)
        assertNotNull(failures.single())
        assertTrue(unreadable.exists())
        assertEquals(listOf("b"), store.pending())
    }
}
