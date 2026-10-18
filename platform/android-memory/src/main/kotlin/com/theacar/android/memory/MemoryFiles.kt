package com.theacar.android.memory

import com.theacar.memory.TripMemory
import com.theacar.memory.dateLines
import java.io.File

/** filesDir/memories/{id}/ の中のファイル名。 */
object MemoryFiles {
    const val ROOT = "memories"
    const val MEMORY_JSON = "memory.json"
    const val ORIGINAL_JPG = "original.jpg"
    const val PHOTO_JPG = "photo.jpg"
    const val SHEET_JPG = "sheet.jpg"
    private val safeId = Regex("[A-Za-z0-9_-]{1,64}")

    fun directory(root: File, id: String): File {
        require(safeId.matches(id)) { "記録の ID に使えない文字があります" }
        return File(root, id)
    }

    /** 共有・ギャラリー用の表示名。例: tabireco-sticker-20261017-8f2c1a3b.jpg */
    fun sheetDisplayName(memory: TripMemory): String {
        val (year, monthDay) = dateLines(memory.arrivedAtEpochMillis, memory.timeZoneId)
        val day = monthDay.take(5).replace(".", "")
        return "tabireco-sticker-$year$day-${memory.id.replace("-", "").take(8)}.jpg"
    }
}
