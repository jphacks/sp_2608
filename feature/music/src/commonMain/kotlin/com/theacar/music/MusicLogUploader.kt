package com.theacar.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 旅の音楽の記録の送り先（Web 版）。2xx で受け付けたときだけ true を返す。通信の失敗は例外で返す。 */
interface MusicLogSender {
    /** 送り先が設定されていなければ false。記録を貯めても送れないので、保存もしない。 */
    val configured: Boolean
    suspend fun send(json: String): Boolean
}

/** 送れていない記録の置き場所。[id] ごとに JSON の文字列を1つ持つ。 */
interface PendingMusicLogStore {
    suspend fun save(id: String, json: String)
    /** 古い順。 */
    suspend fun pending(): List<String>
    suspend fun read(id: String): String
    suspend fun delete(id: String)
}

/**
 * 旅の音楽の記録を Web 版へ送る。送れなかった記録は [store] に残し、[retryPending] で再送する。
 * 送り先が未設定なら何もしない。
 */
class MusicLogUploader(
    private val sender: MusicLogSender,
    private val store: PendingMusicLogStore,
    private val deviceId: () -> String,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()

    suspend fun submit(record: MusicLogRecord) {
        if (!sender.configured) return
        mutex.withLock {
            val json = try {
                record.toJson(deviceId()).also { store.save(record.id, it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError("音楽の記録を保存できませんでした", error)
                return
            }
            send(record.id, json, "音楽の記録を送れませんでした")
        }
    }

    suspend fun retryPending() {
        if (!sender.configured) return
        mutex.withLock {
            val pending = try {
                store.pending()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError("送れていない音楽の記録を読めませんでした", error)
                return
            }
            pending.forEach { id ->
                val json = try {
                    store.read(id)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    onError("音楽の記録を読めませんでした: $id", error)
                    return@forEach
                }
                // 古い端末 ID で書かれたものも、そのまま送る。壊れたものは捨てる。
                if (runCatching { parseMusicLogRecord(json) }.isFailure) {
                    runCatching { store.delete(id) }
                    return@forEach
                }
                send(id, json, "音楽の記録を再送できませんでした")
            }
        }
    }

    /** 受け付けられたら消す。失敗は残して次回に回す。 */
    private suspend fun send(id: String, json: String, failure: String) {
        try {
            if (sender.send(json)) store.delete(id) else onError("音楽の記録の送信が拒否されました", null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            onError(failure, error)
        }
    }
}
