package com.theacar.music

import com.theacar.sensing.AudioFrame
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 20ms ごとのモノラル音声から、1秒ごとに「誰かが話していたか」と声・雑音のレベルを返す。音声は保持しない。
 *
 * 走行音は定常なので、ゆっくり追従する雑音レベルより十分大きいフレームを有声とみなす。
 * 音楽も音量の揺れが小さいので、話し声らしい強弱（音節ごとの上下）がある秒だけを発話とする。
 * しきい値は経験的な初期値で、実車で調整する前提。
 */
class SpeechActivityDetector(
    private val framesPerDecision: Int = 50,
    private val marginDb: Double = 8.0,
    private val minimumDb: Double = -60.0,
    private val voicedRatio: Double = 0.25,
    private val minimumSpreadDb: Double = 4.0,
) {
    private var noiseDb = Double.NaN
    private val levels = DoubleArray(framesPerDecision)
    private var frames = 0
    private var voiced = 0
    private var voicedDbSum = 0.0

    /** 1フレームを受け取る。1秒ぶん溜まったときだけ判定を返す。 */
    fun accept(frame: AudioFrame): CabinSecond? {
        require(frame.channels == 1)
        val count = frame.sampleCount
        var energy = 0.0
        var crossings = 0
        var previous = 0f
        for (index in 0 until count) {
            val value = frame.sampleAt(index)
            energy += value.toDouble() * value
            if (index > 0 && (value >= 0f) != (previous >= 0f)) crossings++
            previous = value
        }
        val db = 20 * log10(sqrt(energy / count).coerceAtLeast(1e-6))
        val zeroCrossing = crossings.toDouble() / count
        // 静かになったらすぐ下げ、大きい音には 1 秒あたり 0.5dB ずつしか上げない。
        noiseDb = when {
            noiseDb.isNaN() -> db
            db < noiseDb -> noiseDb * .9 + db * .1
            else -> noiseDb + .01
        }
        // 低い唸り（走行音）と高い擦過音（風切り）は声の帯域外として除く。
        if (db > noiseDb + marginDb && db > minimumDb && zeroCrossing in .01..0.3) {
            voiced++
            voicedDbSum += db
        }
        levels[frames++] = db
        if (frames < framesPerDecision) return null
        val mean = levels.average()
        val spread = sqrt(levels.sumOf { (it - mean) * (it - mean) } / frames)
        val speaking = voiced >= framesPerDecision * voicedRatio && spread >= minimumSpreadDb
        // 声の大きさは発話と判定した秒だけ返す。ドアの音のような単発の音を声量に数えないため。
        val second = CabinSecond(speaking, if (speaking) voicedDbSum / voiced else null, noiseDb)
        frames = 0
        voiced = 0
        voicedDbSum = 0.0
        return second
    }
}
