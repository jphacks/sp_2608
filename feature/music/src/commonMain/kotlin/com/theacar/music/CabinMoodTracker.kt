package com.theacar.music

/**
 * 1秒ごとの「誰かが話していたか」から、車内の状態（bgm-api の cabin）を決める。
 *
 * 音声そのものは受け取らない。判定の目安は bgm-api の取り決めに合わせている。
 * - lively：直近 [livelyWindowMillis] のうち [livelyRatio] 以上の秒で発話がある
 * - quiet：発話がほとんど無い状態が [quietAfterMillis] 以上
 * - resting：静かな状態が [restingAfterMillis] 以上で、かつ [longDriveMillis] 以上走っている。
 *   いびきの判定は持たないので、長時間運転の条件だけで判断する
 *
 * 観測が [staleAfterMillis] 以上途切れたとき（マイクが使えないなど）は normal を返す。
 */
class CabinMoodTracker(
    private val livelyWindowMillis: Long = 60_000,
    private val livelyRatio: Double = 0.4,
    private val quietAfterMillis: Long = 180_000,
    private val restingAfterMillis: Long = 300_000,
    private val longDriveMillis: Long = 7_200_000,
    private val staleAfterMillis: Long = 10_000,
) {
    init {
        require(livelyWindowMillis > 0 && livelyRatio in 0.0..1.0)
        require(restingAfterMillis >= quietAfterMillis && quietAfterMillis > 0)
        require(longDriveMillis >= 0 && staleAfterMillis > 0)
    }

    private val recent = ArrayDeque<Pair<Long, Boolean>>()
    private var startedAt: Long? = null
    private var lastSpeechAt: Long? = null
    private var lastObservedAt: Long? = null

    /** ドライブの始まり。長時間運転と無音の長さはここから数える。 */
    fun start(nowMillis: Long) {
        recent.clear()
        startedAt = nowMillis
        lastSpeechAt = nowMillis
        lastObservedAt = null
    }

    fun observe(nowMillis: Long, speaking: Boolean) {
        if (startedAt == null) start(nowMillis)
        lastObservedAt = nowMillis
        if (speaking) lastSpeechAt = nowMillis
        recent.addLast(nowMillis to speaking)
        while (recent.isNotEmpty() && nowMillis - recent.first().first > livelyWindowMillis) recent.removeFirst()
    }

    fun state(nowMillis: Long): CabinState {
        val started = startedAt ?: return CabinState.NORMAL
        val observed = lastObservedAt ?: return CabinState.NORMAL
        if (nowMillis - observed > staleAfterMillis) return CabinState.NORMAL
        val silence = nowMillis - (lastSpeechAt ?: started)
        if (silence >= restingAfterMillis && nowMillis - started >= longDriveMillis) return CabinState.RESTING
        if (silence >= quietAfterMillis) return CabinState.QUIET
        // 窓の半分も観測していないうちは、盛り上がりを判断しない。
        val window = recent.filter { nowMillis - it.first <= livelyWindowMillis }
        if (window.size * 1_000L >= livelyWindowMillis / 2 &&
            window.count { it.second } >= window.size * livelyRatio) return CabinState.LIVELY
        return CabinState.NORMAL
    }
}
