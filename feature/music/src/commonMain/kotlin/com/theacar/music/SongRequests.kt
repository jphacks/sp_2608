package com.theacar.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** リクエストされた曲を流すタイミング。今の曲を止めてすぐ流すか、予約の列に入れて今の曲の後に流すか。 */
enum class RequestTiming { NOW, NEXT }

/**
 * 景観BGMの間にリクエストされた曲。[requester] は同乗者が入力した表示名で、自分で選んだ曲は null。
 * [id] は予約の列の中で曲を見分けるためのもの。同じ曲を2回予約しても別のものとして扱う。
 */
data class SongRequest(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val durationMillis: Int? = null,
    val requester: String? = null,
) {
    init { require(id.isNotBlank() && TRACK_URI.matches(uri)) }

    private companion object { val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]{22}") }
}

/** 同乗者の画面に出す曲。 */
data class SharedTrack(val title: String, val artist: String, val requester: String? = null, val request: Boolean = false)

/** 同乗者のリクエストの受付（1回の案内ぶん）。[code] を知っている人だけがリクエストできる。 */
data class RequestParty(val code: String, val expiresAtMillis: Long)

data class GuestRequest(val id: Long, val request: SongRequest, val timing: RequestTiming)

/** 受け取ったリクエストと、受付がまだ開いているか。 */
data class RequestBatch(val open: Boolean, val requests: List<GuestRequest>)

/**
 * 同乗者のリクエストの窓口（bgm-api の /parties）。同乗者は QR コードから開いたブラウザで曲を探して送る。
 * [sync] は今の曲と予約の列を同乗者の画面へ渡し、[after] より後に届いたリクエストを返す。
 */
interface RequestInbox {
    suspend fun open(): RequestParty
    suspend fun sync(code: String, after: Long, nowPlaying: SharedTrack?, queue: List<SharedTrack>): RequestBatch
    suspend fun close(code: String)
}

data class GuestRequestState(
    /** 受付中の code。null なら受け付けていない。 */
    val code: String? = null,
    val opening: Boolean = false,
    /** 受け取ったリクエストの数（この受付で）。 */
    val received: Int = 0,
    val failure: MusicFailure? = null,
)

/**
 * 同乗者のリクエストを受け付ける。開いている間、数秒ごとに届いたリクエストを受け取り、
 * 「次に予約」は景観BGMの予約の列へ、「今すぐ流す」は [playNow] へ渡す。
 * 同時にいくつか「今すぐ」が届いたら、最後のものを流し、それ以外は予約の列の先頭に入れる。
 */
class GuestRequestSession(
    private val inbox: RequestInbox,
    private val bgm: BgmMusicSession,
    private val playNow: (SongRequest) -> Unit,
    private val nowPlaying: () -> SharedTrack?,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 5_000,
) {
    private val mutableState = MutableStateFlow(GuestRequestState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun reportFailure(failure: MusicFailure) { mutableState.update { it.copy(failure = failure) } }

    fun open() {
        if (job?.isActive == true) return
        mutableState.value = GuestRequestState(opening = true)
        job = scope.launch {
            val party = try {
                inbox.open()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = GuestRequestState(failure = error.failure())
                return@launch
            }
            mutableState.value = GuestRequestState(code = party.code)
            var after = 0L
            while (isActive) {
                try {
                    val batch = inbox.sync(party.code, after, nowPlaying(), bgm.state.value.requests.map { it.shared() })
                    after = batch.requests.maxOfOrNull { it.id }?.coerceAtLeast(after) ?: after
                    deliver(batch.requests)
                    mutableState.update { it.copy(received = it.received + batch.requests.size, failure = null) }
                    if (!batch.open) { mutableState.value = GuestRequestState(); return@launch }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val failure = error.failure()
                    // 合言葉が通らないときは続けても届かない。通信の失敗は次の間隔で再試行する
                    if (failure == MusicFailure.AUTHORIZATION) { mutableState.value = GuestRequestState(failure = failure); return@launch }
                    mutableState.update { it.copy(failure = failure) }
                }
                delay(intervalMillis)
            }
        }
    }

    /** 受付を終える。同乗者の画面は「終わりました」になり、それ以降は送れない。予約の列はそのまま残す。 */
    fun close() {
        job?.cancel()
        job = null
        val code = mutableState.value.code
        mutableState.value = GuestRequestState()
        if (code != null) scope.launch {
            try { withContext(NonCancellable) { inbox.close(code) } } catch (error: Exception) {
                if (error is CancellationException) throw error
                // 閉じられなくても受付は期限で終わる
            }
        }
    }

    private fun deliver(requests: List<GuestRequest>) {
        val now = requests.lastOrNull { it.timing == RequestTiming.NOW }
        val pushed = requests.filter { it.timing == RequestTiming.NOW && it !== now }
        requests.filter { it.timing == RequestTiming.NEXT }.forEach { bgm.enqueue(it.request) }
        pushed.asReversed().forEach { bgm.enqueue(it.request, first = true) }
        now?.let { playNow(it.request) }
    }
}

internal fun SongRequest.shared() = SharedTrack(title, artist, requester, request = true)

private fun Exception.failure() = (this as? MusicException)?.failure ?: MusicFailure.NETWORK
