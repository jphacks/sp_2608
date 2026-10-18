package com.theacar.android.memory

import com.theacar.memory.TripMemory
import com.theacar.model.Coordinate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryFilesTest {
    private val memory = TripMemory("8f2c1a3b-0000-4000-8000-000000000001", 0, 1_792_206_000_000L, 357_600, 16_200, "八雲",
        Coordinate(42.25, 140.26), timeZoneId = "Asia/Tokyo")

    @Test fun directoryIsUnderRootById() {
        assertEquals(File("/data/memories/${memory.id}"), MemoryFiles.directory(File("/data/memories"), memory.id))
    }

    @Test fun rejectsIdsThatEscapeTheDirectory() {
        assertFailsWith<IllegalArgumentException> { MemoryFiles.directory(File("/data/memories"), "../other") }
        assertFailsWith<IllegalArgumentException> { MemoryFiles.directory(File("/data/memories"), "") }
    }

    @Test fun sheetDisplayNameUsesLocalDateAndShortId() {
        assertEquals("tabireco-sticker-20261017-8f2c1a3b.jpg", MemoryFiles.sheetDisplayName(memory))
    }
}
