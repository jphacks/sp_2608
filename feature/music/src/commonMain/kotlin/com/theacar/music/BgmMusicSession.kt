package com.theacar.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface BgmPlaybackListener {
    fun onPlayed(track: RecommendedTrack, nowMillis: Long)
    fun onStopped(nowMillis: Long)
}

data class BgmMusicState(
    val enabled: Boolean = false,
    /** 推薦を行わず、同乗者のリクエストだけを順番に流す。 */
    val requestsOnly: Boolean = false,
    val requesting: Boolean = false,
    val playing: RecommendedTrack? = null,
    val message: String? = null,
    val recommendedVolume: Float? = null,
    val revealEtaSeconds: Int? = null,
    val coverage: Double? = null,
    val sceneryAvailable: Boolean = false,
    val note: String? = null,
    val missing: List<String> = emptyList(),
    val playedCount: Int = 0,
    val failure: MusicFailure? = null,
    /** 景観BGMが流している曲の再生先 URI。流れている曲が景観BGMの曲かどうかは、曲名ではなくこれで判断する。 */
    val playingUri: String? = null,
    /** 今の曲の終わりで流す曲（予約の先頭か、準備した景観BGMの曲）。 */
    val upNextUri: String? = null,
    /** 予約の列。今の曲の終わりで先頭から流し、空になったら景観BGMに戻る。 */
    val requests: List<SongRequest> = emptyList(),
    /** 流れているリクエスト曲。景観BGMの曲が流れているときは null。 */
    val playingRequest: SongRequest? = null,
)

/**
 * 推薦サービスから1曲ずつ受け取り、選択済みの再生先で流す。
 *
 * 曲の終わり [leadMillis] 前に次の曲を決めて準備しておき、今の曲の残りが [switchMillis] を切ったら流す。
 * 再生先の「次に再生」（Spotify のキュー）は使わない。端末によっては受け付けても実際には入らず、
 * 前に聴いていたプレイリストの続きが流れてしまうため（2026-10-18 に実機で確認）。
 * 再生先が先に別の曲へ進んでしまったときも、すぐ準備した曲へ切り替える。
 * 予約の列（[enqueue]）に曲があれば、景観BGMの曲より先にそれを流す。予約がある間は次の曲を推薦しない。
 * 利用者が曲の途中で自分で選んだ曲（[playRequest] やアプリ外での操作）は、その曲が終わるまで任せる。
 * 再生位置が取得できない再生先では、再生が止まったときにだけ次を流すため、曲間に間が空く。
 * 選んだ曲は history として次の要求へ渡すので、同じドライブ中は選び直されない。
 */
class BgmMusicSession(
    private val recommendations: BgmRecommendationService,
    private val music: MusicService,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val leadMillis: Int = 30_000,
    private val switchMillis: Int = 1_500,
    private val minimumIntervalMillis: Long = 10_000,
    private val historyLimit: Int = 200,
    private val listener: BgmPlaybackListener? = null,
) {
    init { require(leadMillis >= 0 && minimumIntervalMillis >= 0 && historyLimit > 0) }

    private val mutableState = MutableStateFlow(BgmMusicState())
    val state = mutableState.asStateFlow()
    private val history = mutableListOf<PlayedTrack>()
    private var members: List<MusicMember> = emptyList()
    private var pool: List<PlaylistEntry> = emptyList()
    private var mode = RecommendationMode.BLEND
    private var recentTrackIds: List<String> = emptyList()
    private var previousTarget: MusicTarget? = null
    /** 今流れている（または最後に流れた）曲。景観BGMの曲のほか、利用者がリクエストした曲もここに入る。 */
    private var currentUri: String? = null
    /** 次に流す景観BGMの曲。今の曲の終わりで流す（予約があれば、その後に）。 */
    private var prepared: Prepared? = null
    private val queue = mutableListOf<SongRequest>()
    /** 直前に見た今の曲の残り時間。曲の終わりで再生先が勝手に進んだのか、利用者が途中で変えたのかの区別に使う。 */
    private var lastRemaining: Int? = null
    /**
     * 切り替える前に流れていた曲と、その曲の再生状態を無視する期限。
     * 再生を指示した直後の数秒は、再生先が前の曲の状態を返すことがある。それを利用者の操作と読み違えないため。
     */
    private var replacedUri: String? = null
    private var replacedUntil = 0L

    /** 次に流す曲。景観BGMの曲なら [track]、予約の曲なら [request] を持つ。 */
    private class Prepared(val uri: String, val track: RecommendedTrack? = null, val message: String? = null, val request: SongRequest? = null)
    private enum class Next { PLAY_NOW, PREPARE }
    private var lastRequestAt: Long? = null
    private var job: Job? = null
    private var generation = 0L
    private var closed = false

    fun start(
        members: List<MusicMember>,
        pool: List<PlaylistEntry>,
        mode: RecommendationMode = RecommendationMode.BLEND,
        recentTrackIds: List<String> = emptyList(),
        requestsOnly: Boolean = false,
    ) {
        if (closed || members.isEmpty()) return
        stop()
        this.members = members
        this.pool = pool
        this.mode = mode
        this.recentTrackIds = recentTrackIds
        // 始めたら、流れている曲（前の推薦の曲や利用者が選んだ曲）の終わりを待たずにすぐ1曲選ぶ。
        // 履歴は残すので、同じドライブで流した曲は選び直さない。
        currentUri = null
        prepared = null
        lastRemaining = null
        lastRequestAt = null
        replacedUri = null
        mutableState.update { it.copy(enabled = true, requestsOnly = requestsOnly, failure = null, note = null, playingUri = null, playingRequest = null,
            upNextUri = queue.firstOrNull()?.uri) }
    }

    /** プレイリストの途中参加・途中離脱は次の要求から反映する。 */
    fun updatePool(members: List<MusicMember>, pool: List<PlaylistEntry>) {
        if (closed || members.isEmpty()) return
        this.members = members
        this.pool = pool
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        mutableState.update { it.copy(enabled = false, requesting = false) }
        listener?.onStopped(nowMillis())
    }

    fun reset() {
        stop()
        history.clear()
        queue.clear()
        previousTarget = null
        currentUri = null
        prepared = null
        lastRemaining = null
        lastRequestAt = null
        mutableState.value = BgmMusicState()
    }

    fun close() { closed = true; reset() }

    fun clearFailure() { mutableState.update { it.copy(failure = null) } }

    /**
     * 定期的に呼ぶ。次の曲を決める条件を満たしたときだけ要求する。
     * [playbackBusy] が真のあいだは、他の再生操作と競合しないよう何もしない。
     */
    fun update(
        scenery: SceneryRoute?,
        cabin: CabinState,
        deviceId: String?,
        playback: MusicPlayback,
        playbackBusy: Boolean,
        conditions: DrivingConditions = DrivingConditions(),
    ) {
        if (closed || !state.value.enabled || job?.isActive == true) return
        mutableState.update { it.copy(sceneryAvailable = scenery != null) }
        if (playbackBusy) return
        if (deviceId == null) { fail(MusicFailure.NO_DEVICE); return }
        val track = playback.track
        val now = nowMillis()
        if (track != null && track.uri == replacedUri && now < replacedUntil) return
        val remaining = remainingMillis(playback)
        // 受付を開いた時点で流れていた曲も、終わりまで待ってから予約を流す。
        if (state.value.requestsOnly && currentUri == null) currentUri = track?.uri
        upcoming()?.let { next ->
            if (track?.uri == next.uri) { started(next); return }
            val atEnd = playback.playing && track?.uri == currentUri && remaining != null && remaining <= switchMillis
            // 曲の終わりで、再生先が準備した曲ではない曲へ進んだ（前に聴いていたプレイリストの続きなど）。
            val movedOnByItself = track?.uri != currentUri && (lastRemaining ?: Int.MAX_VALUE) <= AUTO_ADVANCE_MILLIS
            val ended = !playback.playing && !pausedMidway(playback)
            if (atEnd || movedOnByItself || ended) { playPrepared(next, deviceId); return }
        }
        // 曲の途中で別の曲に変わった。利用者が自分で選んだので、その曲を今の曲として終わりまで任せる。
        if (track != null && currentUri != null && track.uri != currentUri) {
            currentUri = track.uri
            prepared = null
            mutableState.update { it.copy(upNextUri = queue.firstOrNull()?.uri, playingRequest = null) }
        }
        lastRemaining = remaining.takeIf { track?.uri == currentUri }
        lastRequestAt?.let { if (now - it < minimumIntervalMillis) return }
        val next = next(playback) ?: return
        val currentGeneration = generation
        lastRequestAt = now
        // 次の曲の長さは要求時点では分からない。窓の長さの目安として、今の曲の長さを渡す。
        val route = scenery?.let { it.copy(nextDurationMillis = it.nextDurationMillis ?: playback.track?.durationMillis) }
        mutableState.update { it.copy(requesting = true, failure = null) }
        job = scope.launch {
            try {
                val response = recommendations.recommend(
                    BgmRecommendationRequest(members, pool, mode, cabin, route, conditions,
                        history.toList(), recentTrackIds, previousTarget, BgmRecommendationService.MAX_LINKS)
                )
                if (currentGeneration != generation || closed) return@launch
                previousTarget = response.target ?: previousTarget
                mutableState.update {
                    it.copy(recommendedVolume = response.volume, revealEtaSeconds = response.revealEtaSeconds,
                        coverage = response.coverage, note = response.note, missing = response.missing)
                }
                val chosen = response.chosen ?: return@launch
                val ordered = listOf(chosen) + response.candidates.filter { it.trackId != chosen.trackId }
                val resolved = resolve(ordered.take(BgmRecommendationService.MAX_LINKS))
                    ?: throw MusicException(MusicFailure.NO_RESULTS)
                val (picked, uri) = resolved
                // 照合を待つ間に止められていたら流さない。止めた直後に新しい曲が始まるのを防ぐ。
                if (currentGeneration != generation || closed) return@launch
                // 演出の一言は chosen に対して書かれているので、次点へ回ったときは出さない。
                val entry = Prepared(uri, picked, picked.message.takeIf { picked.trackId == chosen.trackId })
                history += PlayedTrack(picked.trackId, picked.ownerId, picked.artist)
                if (history.size > historyLimit) history.removeAt(0)
                if (next == Next.PLAY_NOW) {
                    replacing(playback.track?.uri)
                    music.play(uri, deviceId)
                    if (currentGeneration == generation && !closed) started(entry)
                } else {
                    prepared = entry
                    mutableState.update { it.copy(upNextUri = upcoming()?.uri) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation && !closed) fail(error.musicFailure())
            } finally {
                if (currentGeneration == generation && !closed) mutableState.update { it.copy(requesting = false) }
            }
        }
    }

    /**
     * 「今すぐ流す」で、いま再生させたリクエスト曲。その曲が終わるまで任せ、終わったら予約の続きか景観BGMを流す。
     * 準備していた景観BGMの曲は流さない（もう履歴に入っているので、このドライブで選び直されることもない）。
     */
    fun playRequest(request: SongRequest) {
        if (closed || !state.value.enabled) return
        generation++
        job?.cancel()
        job = null
        replacing(currentUri)
        currentUri = request.uri
        prepared = null
        lastRemaining = null
        lastRequestAt = null
        queue.removeAll { it.id == request.id }
        mutableState.update {
            it.copy(requesting = false, playingRequest = request, playingUri = request.uri, message = null,
                upNextUri = queue.firstOrNull()?.uri, requests = queue.toList())
        }
    }

    /** 予約の列に入れる。[first] なら先頭に入れる。同じ [SongRequest.id] がすでにあれば入れ直す。 */
    fun enqueue(request: SongRequest, first: Boolean = false) {
        if (closed) return
        queue.removeAll { it.id == request.id }
        if (first) queue.add(0, request) else queue += request
        publishQueue()
    }

    /** 予約の順番を入れ替える。[offset] が負なら前へ、正なら後ろへ。 */
    fun moveRequest(id: String, offset: Int) {
        val from = queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val to = (from + offset).coerceIn(0, queue.lastIndex)
        if (to == from) return
        queue.add(to, queue.removeAt(from))
        publishQueue()
    }

    fun removeRequest(id: String) {
        if (queue.removeAll { it.id == id }) publishQueue()
    }

    private fun publishQueue() {
        mutableState.update { it.copy(requests = queue.toList(), upNextUri = upcoming()?.uri) }
    }

    /** 今の曲の終わりで流す曲。予約があればその先頭、無ければ準備した景観BGMの曲。 */
    private fun upcoming(): Prepared? = queue.firstOrNull()?.let { Prepared(it.uri, request = it) } ?: prepared

    /** 今の曲の残り時間。曲の終わりが近いときは、呼ぶ側が再生状態を細かく確かめる目安にする。 */
    fun remainingMillis(playback: MusicPlayback): Int? {
        val duration = playback.track?.durationMillis ?: return null
        val position = playback.positionMillis ?: return null
        return (duration - position).coerceAtLeast(0)
    }

    /**
     * 次の曲を決めるか、決めるならすぐ流すか、準備して今の曲の終わりで流すか。
     * まだ何も流していないときと、再生が止まっているときはすぐ流す。曲の途中の一時停止では決めない。
     * 流れている曲（景観BGMの曲でも、利用者が選んだ曲でも）の終わりが近づいたら、次の曲を準備する。
     */
    private fun next(playback: MusicPlayback): Next? {
        if (state.value.requestsOnly) return null
        if (currentUri == null && prepared == null) return Next.PLAY_NOW
        if (!playback.playing) return if (pausedMidway(playback)) null else Next.PLAY_NOW
        // 予約がある間は、次の景観BGMの曲を選ばない（予約が終わる頃の景色で選ぶ）
        if (prepared != null || queue.isNotEmpty()) return null
        val remaining = remainingMillis(playback) ?: return null
        return if (remaining <= leadMillis) Next.PREPARE else null
    }

    private fun started(entry: Prepared) {
        currentUri = entry.uri
        if (prepared === entry) prepared = null
        lastRemaining = null
        val request = entry.request
        if (request != null) {
            queue.removeAll { it.id == request.id }
            mutableState.update {
                it.copy(message = null, playedCount = it.playedCount + 1, playingUri = entry.uri, playingRequest = request,
                    requests = queue.toList(), upNextUri = upcoming()?.uri)
            }
            return
        }
        val track = entry.track ?: return
        listener?.onPlayed(track, nowMillis())
        mutableState.update {
            it.copy(playing = track, message = entry.message, playedCount = it.playedCount + 1,
                playingUri = entry.uri, playingRequest = null, upNextUri = upcoming()?.uri)
        }
    }

    private fun playPrepared(entry: Prepared, deviceId: String) {
        val currentGeneration = generation
        replacing(currentUri)
        job = scope.launch {
            try {
                music.play(entry.uri, deviceId)
                if (currentGeneration == generation && !closed) started(entry)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation && !closed) fail(error.musicFailure())
            }
        }
    }

    private fun replacing(uri: String?) {
        replacedUri = uri
        replacedUntil = nowMillis() + REPLACED_GRACE_MILLIS
    }

    /**
     * 再生位置が曲の途中なら一時停止とみなす。曲が最後まで流れて止まると、
     * Spotify は位置を 0 に戻すか曲を外すので、そのときは曲の終わりとして扱う。
     */
    private fun pausedMidway(playback: MusicPlayback): Boolean {
        val duration = playback.track?.durationMillis ?: return false
        val position = playback.positionMillis ?: return false
        return position > 0 && position < duration - END_TOLERANCE_MILLIS
    }

    private suspend fun resolve(tracks: List<RecommendedTrack>): Pair<RecommendedTrack, String>? {
        if (tracks.isEmpty()) return null
        val links = recommendations.link(tracks.map { it.trackId }).links.associateBy { it.trackId }
        // not_found・no_isrc・pending は次の候補へ回す。
        return tracks.firstNotNullOfOrNull { track ->
            links[track.trackId]?.takeIf { it.status == TrackLinkStatus.FOUND }?.uri?.let { track to it }
        }
    }

    /** 認証・再生先・権限の失敗は継続できないので止める。通信とレート制限は次の間隔で再試行する。 */
    private fun fail(failure: MusicFailure) {
        val terminal = failure in setOf(MusicFailure.AUTHORIZATION, MusicFailure.ACCESS_DENIED, MusicFailure.NO_DEVICE)
        if (terminal) stop()
        mutableState.update { it.copy(enabled = it.enabled && !terminal, failure = failure) }
    }

    private companion object {
        /** 曲の終わりから、この範囲で止まっていれば曲の終わりとみなす。 */
        const val END_TOLERANCE_MILLIS = 3_000
        /** 直前に見た残り時間がこれ以下で別の曲に変わったら、利用者ではなく再生先が曲の終わりで進めたとみなす。 */
        const val AUTO_ADVANCE_MILLIS = 12_000
        /** 再生を指示してから、前の曲の再生状態を無視する長さ。 */
        const val REPLACED_GRACE_MILLIS = 10_000L
    }
}

private fun Exception.musicFailure() = (this as? MusicException)?.failure ?: MusicFailure.NETWORK
