package com.theacar.music

/**
 * 車内のマイクから取り出した1秒ぶんの判定。音声そのものは含まない。
 *
 * [voiceDb] はその秒の有声フレームの平均レベル（dBFS）で、発話でない秒は null。
 * [noiseDb] はゆっくり追従する雑音レベル。dB の絶対値は端末やマイクの位置で変わるため、
 * 声の大きさは [snrDb]（雑音との差）で比べる。
 */
data class CabinSecond(val speaking: Boolean, val voiceDb: Double?, val noiseDb: Double) {
    val snrDb: Double? get() = voiceDb?.let { it - noiseDb }
}
