package com.theacar.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TripMemorySender {
    val configured: Boolean
    suspend fun send(memory: TripMemory, photo: ByteArray?): Boolean
}

data class TripPublication(val memory: TripMemory, val photo: ByteArray?, val revision: String)

interface TripPublicationStore {
    suspend fun pendingIds(): List<String>
    suspend fun publication(id: String): TripPublication?
    suspend fun markPublished(id: String, revision: String)
}

/** 新しい書き出しが送信中に入っても、その再送予約は消さない。失敗は記録ごとに扱う。 */
class TripMemoryPublisher(
    private val sender: TripMemorySender,
    private val store: TripPublicationStore,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()

    suspend fun retryPending() {
        if (!sender.configured) return
        mutex.withLock {
            val ids = try { store.pendingIds() } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError("送信待ちの記録を取得できませんでした", error)
                return
            }
            for (id in ids) {
                try {
                    val item = store.publication(id) ?: continue
                    if (sender.send(item.memory, item.photo)) store.markPublished(id, item.revision)
                    else onError("旅の記録を送信できませんでした: $id", null)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    onError("旅の記録を送信できませんでした: $id", error)
                }
            }
        }
    }
}
