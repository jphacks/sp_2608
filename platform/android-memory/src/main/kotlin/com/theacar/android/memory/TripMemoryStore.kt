package com.theacar.android.memory

import android.content.Context
import com.theacar.memory.TripMemory
import com.theacar.memory.parseTripMemory
import com.theacar.memory.toJson
import com.theacar.memory.TripMemoryRepository
import com.theacar.memory.TripPublicationStore
import com.theacar.memory.TripPublication
import com.theacar.memory.TripMemorySummary
import com.theacar.memory.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** 記録・写真・送信待ちの印を保存する。書き込みと一覧通知は同じロック内で行う。 */
class TripMemoryStore internal constructor(private val root: File) : TripMemoryRepository, TripPublicationStore {
    constructor(context: Context) : this(File(context.filesDir, MemoryFiles.ROOT))
    private val lock = Mutex()
    private val mutableMemories = MutableStateFlow<List<TripMemorySummary>>(emptyList())
    /** 新しい順の一覧。[refresh] か書き込みのたびに更新される。 */
    val memories = mutableMemories.asStateFlow()

    fun directory(id: String): File = MemoryFiles.directory(root, id).also {
        if (!it.isDirectory && !it.mkdirs()) throw IOException("記録の保存先を作成できません")
    }
    fun originalFile(id: String): File = File(directory(id), MemoryFiles.ORIGINAL_JPG)
    fun photoFile(id: String): File = File(directory(id), MemoryFiles.PHOTO_JPG)
    fun sheetFile(id: String): File = File(directory(id), MemoryFiles.SHEET_JPG)

    suspend fun save(memory: TripMemory) = lock.withLock { withContext(Dispatchers.IO) { write(memory) } }

    /** 保存先を読み直して [memories] に反映する。起動時に一度呼ぶ。 */
    suspend fun refresh() = lock.withLock { withContext(Dispatchers.IO) { mutableMemories.value = list() } }

    /** 読み→変更→書きをひとまとめに直列化する。記録が無ければ null。戻り値は書いた後の最新の記録。 */
    override suspend fun update(id: String, transform: (TripMemory) -> TripMemory): TripMemory? = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = readRecord(id) ?: return@withContext null
            transform(current).also { if (it != current) write(it) }
        }
    }

    suspend fun read(id: String): TripMemory? = lock.withLock { withContext(Dispatchers.IO) { readRecord(id) } }

    private fun readRecord(id: String): TripMemory? {
        val file = File(MemoryFiles.directory(root, id), MemoryFiles.MEMORY_JSON)
        if (!file.isFile) return null
        return runCatching { parseTripMemory(file.readText()) }.getOrNull()
    }

    /** 新しい順の一覧。壊れた記録は飛ばす。 */
    private fun list(): List<TripMemorySummary> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { runCatching { readRecord(it.name)?.summary() }.getOrNull() }
        .sortedByDescending { it.arrivedAtEpochMillis }

    private fun write(memory: TripMemory) {
        val target = File(directory(memory.id), MemoryFiles.MEMORY_JSON)
        replace(target) { it.writeText(memory.toJson()) }
        mutableMemories.value = (mutableMemories.value.filterNot { it.id == memory.id } + memory.summary())
            .sortedByDescending { it.arrivedAtEpochMillis }
    }

    suspend fun writePhoto(id: String, writer: (File) -> Unit) = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = requireNotNull(readRecord(id))
            replace(photoFile(id), writer)
            write(current.copy(photoVersion = maxOf(System.currentTimeMillis(), (current.photoVersion ?: 0) + 1)))
        }
    }

    suspend fun enqueuePublication(id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            requireNotNull(readRecord(id))
            replace(File(directory(id), PENDING)) { it.writeText(UUID.randomUUID().toString()) }
        }
    }

    suspend fun writeSheet(id: String, writer: (File) -> Unit) = lock.withLock {
        withContext(Dispatchers.IO) { replace(sheetFile(id), writer) }
    }

    override suspend fun pendingIds(): List<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            root.listFiles().orEmpty().filter { it.isDirectory && File(it, PENDING).isFile }.map { it.name }.sorted()
        }
    }

    override suspend fun publication(id: String): TripPublication? = lock.withLock {
        withContext(Dispatchers.IO) {
            val marker = File(directory(id), PENDING)
            if (!marker.isFile) return@withContext null
            val memory = requireNotNull(readRecord(id))
            TripPublication(memory, photoFile(id).takeIf { it.isFile }?.readBytes(), marker.readText())
        }
    }

    override suspend fun markPublished(id: String, revision: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val marker = File(directory(id), PENDING)
            if (marker.isFile && marker.readText() == revision && !marker.delete()) throw IOException("送信済みの印を更新できません")
        }
    }

    private fun replace(target: File, writer: (File) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            writer(temp)
            if (!temp.renameTo(target)) throw IOException("記録を保存できません: ${target.name}")
        } finally { temp.delete() }
    }

    private companion object { const val PENDING = "upload.pending" }
}
