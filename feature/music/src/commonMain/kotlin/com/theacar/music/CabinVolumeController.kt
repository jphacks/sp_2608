package com.theacar.music

/**
 * 1秒ごとの [CabinSecond] から、BGM を基準音量から何段下げるかを決める。音量そのものは変えない。
 * 発話秒の SNR の中央値を普段の声と比較する。初期学習中は下げない。
 * 減音によって声も変わるため、下げているあいだは基準を学び直さない。
 */
class CabinVolumeController(
    private val windowMillis: Long = 15_000,
    private val minSpeakingSeconds: Int = 5,
    private val defaultBaselineSnrDb: Double = 15.0,
    private val baselineSamples: Int = 30,
    private val loudMarginDb: Double = 6.0,
    private val calmMarginDb: Double = 3.0,
    private val stepIntervalMillis: Long = 10_000,
    private val restoreAfterMillis: Long = 30_000,
    private val maxSteps: Int = 3,
) {
    init {
        require(windowMillis > 0 && minSpeakingSeconds > 0 && baselineSamples in 1..MAX_VOICES)
        require(loudMarginDb > calmMarginDb)
        require(stepIntervalMillis >= 0 && restoreAfterMillis >= 0 && maxSteps >= 0)
    }

    /** 直近の窓の (時刻, snr)。発話でない秒の snr は null。 */
    private val recent = ArrayDeque<Pair<Long, Double?>>()
    private val voices = ArrayDeque<Double>()
    private var lastChangeAt: Long? = null
    private var unsettledAt: Long? = null

    /** いま基準音量から下げている段数（0..[maxSteps]）。 */
    var steps = 0
        private set

    /** このドライブの普段の声（snr, dB）。 */
    val baselineSnrDb: Double
        get() = if (voices.size < baselineSamples) defaultBaselineSnrDb else voices.median()

    val calibrated: Boolean get() = voices.size >= baselineSamples

    /** ドライブの始まり。普段の声も忘れる。 */
    fun start() {
        voices.clear()
        reset()
    }

    /** 下げ幅を 0 に戻す。ユーザーが手で音量を変えたときに呼ぶ。普段の声は覚えたまま。 */
    fun reset() {
        recent.clear()
        steps = 0
        lastChangeAt = null
        unsettledAt = null
    }

    /** 1秒ごとに呼ぶ。下げる段数を返す。 */
    fun observe(nowMillis: Long, second: CabinSecond): Int {
        val learning = !calibrated
        val baseline = baselineSnrDb
        val snr = second.snrDb
        if (snr != null && steps == 0) {
            voices.addLast(snr)
            while (voices.size > MAX_VOICES) voices.removeFirst()
        }
        if (learning) {
            // 仮の基準で減音すると学習自体が止まってしまう。比較用の窓も学習後から数える。
            recent.clear()
            return steps
        }
        recent.addLast(nowMillis to snr)
        while (nowMillis - recent.first().first >= windowMillis) recent.removeFirst()

        val voiced = recent.mapNotNull { it.second }
        // 発話が少ない窓では声の大きさを判断しない（落ち着いている扱い）。
        val excess = if (voiced.size >= minSpeakingSeconds) voiced.median() - baseline else null
        if (excess != null && excess > calmMarginDb) unsettledAt = nowMillis
        val canChange = lastChangeAt.let { it == null || nowMillis - it >= stepIntervalMillis }
        val calmSince = unsettledAt
        when {
            !canChange -> Unit
            excess != null && excess >= loudMarginDb && steps < maxSteps -> change(nowMillis, +1)
            steps > 0 && calmSince != null && nowMillis - calmSince >= restoreAfterMillis -> change(nowMillis, -1)
        }
        return steps
    }

    private fun change(nowMillis: Long, delta: Int) {
        steps += delta
        lastChangeAt = nowMillis
    }

    private fun Collection<Double>.median(): Double {
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    companion object {
        /** 普段の声として覚える発話秒の上限。30 分ぶん。 */
        const val MAX_VOICES = 1_800
    }
}
