package com.theacar.android.memory

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.theacar.memory.records.RecordSong
import com.theacar.memory.records.RoadRecordBook
import com.theacar.memory.records.RoadShape
import com.theacar.memory.records.parseRoadShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手に入れたレコードを端末に残す（filesDir/records/unlocked.json）。旅の記録とは別に持つので、
 * 途中で案内をやめた旅で手に入れたレコードも消えない。
 */
class RoadRecordStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "records/unlocked.json"))
    private val mutableBook = MutableStateFlow(read())
    val book: StateFlow<RoadRecordBook> = mutableBook.asStateFlow()
    private val writing = Mutex()

    suspend fun unlock(id: String, atEpochMillis: Long, song: RecordSong?) = update { it.unlock(id, atEpochMillis, song) }

    /** 着いたときの演出を見せたレコードを、演出待ちから外す */
    suspend fun celebrated(ids: Collection<String>) = update { it.celebrated(ids) }

    private suspend fun update(change: (RoadRecordBook) -> RoadRecordBook) = writing.withLock {
        val next = change(mutableBook.value)
        mutableBook.value = next
        withContext(Dispatchers.IO) {
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try {
                stream.write(next.toJson().toByteArray())
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    private fun read(): RoadRecordBook = runCatching {
        if (!file.baseFile.exists()) RoadRecordBook() else RoadRecordBook.parse(String(file.readFully()))
    }.getOrElse {
        Log.w("RoadRecordStore", "手に入れたレコードを読めませんでした", it)
        RoadRecordBook()
    }

    companion object {
        /** アプリに入れた道の形（export_road_registry.py --format geojson --ids …）。無ければレコードは手に入らない */
        const val SHAPES_ASSET = "road-records.geojson"

        fun loadShapes(context: Context): List<RoadShape> = runCatching {
            context.assets.open(SHAPES_ASSET).use { parseRoadShapes(it.readBytes().decodeToString()) }
        }.getOrElse {
            Log.w("RoadRecordStore", "道の形（$SHAPES_ASSET）を読めませんでした。レコードは手に入りません", it)
            emptyList()
        }
    }
}
