package com.theacar.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiveReporterState(
    val enabled: Boolean = false,
    val code: String? = null,
    /** 最後に届いた時刻。まだ一度も届いていなければ null。 */
    val lastSentAtMillis: Long? = null,
    /** 直近の送信が届かなかった。 */
    val failing: Boolean = false,
)

/**
 * 案内中の様子を [intervalMillis] ごとに Web のライブ表示へ送る。
 *
 * 案内中（[route] が値を返す間）だけ送る。経路の形は大きいので、経路が変わったときだけ添える。
 * 目的地に着いたら「着いた」を1回だけ送り、次の案内が始まるまで送らない。
 * 送れなかったときも走行や選曲は止めず、次の間隔でまた送る。
 */
class LiveReporter(
    private val sender: LiveSender,
    private val scope: CoroutineScope,
    /** 実際の時刻（ミリ秒）。ページの「何秒前」の表示に使う。 */
    private val nowMillis: () -> Long,
    private val route: () -> LiveRoute?,
    private val car: () -> LiveCar?,
    private val cabinState: () -> CabinState?,
    private val nowPlaying: () -> LiveNowPlaying?,
    private val evaluation: () -> LiveEvaluation?,
    private val intervalMillis: Long = 2_000,
    private val speechWindowMillis: Long = 60_000,
    /** 送れなかった理由などの記録先。 */
    private val trace: (String) -> Unit = {},
) {
    init { require(intervalMillis > 0 && speechWindowMillis > 0) }

    private val mutableState = MutableStateFlow(LiveReporterState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var sentRouteKey: String? = null
    private var arrivedRouteKey: String? = null
    private var lastRouteKey: String? = null
    private val played = ArrayDeque<LivePlayed>()
    private val speech = ArrayDeque<Pair<Long, Boolean>>()

    fun start(code: String) {
        require(CODE.matches(code))
        stop()
        sentRouteKey = null
        arrivedRouteKey = null
        mutableState.value = LiveReporterState(enabled = true, code = code)
        job = scope.launch {
            while (isActive) {
                tick(code)
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(enabled = false, failing = false) }
    }

    /** 1秒ごとの「誰かが話していたか」。音声は受け取らない。 */
    fun observeSpeech(speaking: Boolean) {
        val atMillis = nowMillis()
        speech.addLast(atMillis to speaking)
        while (speech.isNotEmpty() && atMillis - speech.first().first > speechWindowMillis) speech.removeFirst()
    }

    /** 景観BGMの曲が流れ始めた。決めたときの評価から、題材と景色を添えて残す。 */
    fun onBgmTrackStarted(track: RecommendedTrack, message: String?) {
        val position = route()?.positionMeters
        val decided = evaluation()?.takeIf { it.decided && it.chosen?.trackId == track.trackId }
        val candidate = decided?.evaluation?.candidates?.firstOrNull { it.trackId == track.trackId }
        val theme = candidate?.theme?.takeIf { candidate.themeMatch > 0 }
        val scene = decided?.evaluation?.shares?.maxByOrNull { it.value }?.key
        played.firstOrNull()?.let { if (it.endMeters == null) played[0] = it.copy(endMeters = position) }
        played.addFirst(LivePlayed(track.title, track.artist, message, theme, scene, position, null))
        while (played.size > MAX_PLAYED) played.removeLast()
    }

    internal suspend fun tick(code: String) {
        val r = route() ?: return
        if (r.key != lastRouteKey) {
            // 再探索で経路が入れ替わると距離の基準が変わる。前の経路で流した曲の地点は帯に置けないので外す
            if (lastRouteKey != null) {
                val kept = played.map { it.copy(startMeters = null, endMeters = null) }
                played.clear(); played.addAll(kept)
            }
            lastRouteKey = r.key
        }
        if (r.arrived && arrivedRouteKey == r.key) return
        val now = nowMillis()
        val snapshot = LiveSnapshot(
            atMillis = now,
            arrived = r.arrived,
            destination = r.destination,
            car = car(),
            routeKey = r.key,
            positionMeters = r.positionMeters,
            totalMeters = r.totalMeters,
            segments = r.segments,
            cabin = cabinState()?.let { LiveCabin(it, speechRatio(), speakingNow(now)) },
            nowPlaying = nowPlaying(),
            evaluation = evaluation(),
            played = played.toList(),
        )
        val geometry = r.geometry.takeIf { r.key != sentRouteKey && it.size >= 2 }
        val delivered = try {
            sender.send(code, snapshot, geometry)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            trace("live: 送れなかった (${error::class.simpleName}: ${error.message?.take(120)})")
            false
        }
        if (delivered && state.value.lastSentAtMillis == null) trace("live: 送り始めた")
        if (delivered) {
            if (geometry != null) sentRouteKey = r.key
            if (r.arrived) arrivedRouteKey = r.key
            mutableState.update { it.copy(lastSentAtMillis = now, failing = false) }
        } else {
            if (!state.value.failing) trace("live: 送れなかった（受け口が受け付けなかった）")
            mutableState.update { it.copy(failing = true) }
        }
    }

    private fun speechRatio(): Double = if (speech.isEmpty()) 0.0 else speech.count { it.second }.toDouble() / speech.size

    private fun speakingNow(now: Long) = speech.lastOrNull()?.let { it.second && now - it.first <= 3_000 } ?: false

    companion object {
        val CODE = Regex("[A-Za-z0-9_-]{22}")
        const val MAX_PLAYED = 30
    }
}
