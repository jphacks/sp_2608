package com.theacar.music

import com.theacar.sensing.AudioFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechActivityDetectorTest {
    private val rate = 16_000
    private val frame = rate / 50

    /** [seconds] 秒ぶんを流し、1秒ごとの判定を返す。 */
    private fun SpeechActivityDetector.seconds(seconds: Int, sample: (Int) -> Double): List<CabinSecond> {
        val results = mutableListOf<CabinSecond>()
        val buffer = FloatArray(frame)
        for (start in 0 until seconds * rate step frame) {
            for (i in 0 until frame) buffer[i] = sample(start + i).coerceIn(-1.0, 1.0).toFloat()
            accept(AudioFrame.fromPcm(buffer, rate))?.let(results::add)
        }
        return results
    }

    private fun SpeechActivityDetector.feed(seconds: Int, sample: (Int) -> Double) = seconds(seconds, sample).map { it.speaking }

    private val noise = Random(7)
    private fun hum(i: Int) = .01 * sin(2 * PI * 60 * i / rate) + .002 * (noise.nextDouble() - .5)
    private fun tone(i: Int, amplitude: Double = .3) = amplitude * sin(2 * PI * 220 * i / rate)
    private fun speech(i: Int, amplitude: Double = .3) = hum(i) + if ((i * 4 / rate) % 2 == 0) tone(i, amplitude) else 0.0

    @Test
    fun roadNoiseIsNotSpeech() {
        assertEquals(List(5) { false }, SpeechActivityDetector().feed(5, ::hum))
    }

    @Test
    fun steadyMusicIsNotSpeech() {
        assertEquals(List(5) { false }, SpeechActivityDetector().feed(5) { hum(it) + tone(it) })
    }

    @Test
    fun syllabicBurstsAreSpeech() {
        // 4Hz で強弱をつけた声の帯域の音。音節の上下を模している。
        val results = SpeechActivityDetector().feed(5) { i ->
            val syllable = (i * 4 / rate) % 2 == 0
            hum(i) + if (syllable) tone(i) else 0.0
        }
        assertEquals(List(5) { true }, results)
    }

    @Test
    fun speechAfterSilenceIsDetected() {
        val detector = SpeechActivityDetector()
        assertEquals(List(3) { false }, detector.feed(3, ::hum))
        val results = detector.feed(3) { i -> hum(i) + if ((i * 4 / rate) % 2 == 0) tone(i) else 0.0 }
        assertEquals(List(3) { true }, results)
    }

    @Test
    fun silentSecondsHaveNoVoiceLevel() {
        val seconds = SpeechActivityDetector().seconds(3, ::hum)
        assertTrue(seconds.all { it.voiceDb == null && it.snrDb == null })
    }

    @Test
    fun noiseLevelFollowsRoadNoise() {
        // hum の実効値は約 0.0071（-43dBFS）。
        val noise = SpeechActivityDetector().seconds(3, ::hum).last().noiseDb
        assertTrue(noise in -46.0..-40.0, "noiseDb=$noise")
    }

    @Test
    fun louderVoiceHasHigherSnr() {
        val loud = SpeechActivityDetector().seconds(5) { speech(it, .3) }.last()
        val soft = SpeechActivityDetector().seconds(5) { speech(it, .1) }.last()
        val loudSnr = assertNotNull(loud.snrDb)
        val softSnr = assertNotNull(soft.snrDb)
        // 振幅が 3 倍なら約 9.5dB 大きい。雑音レベルは同じなので差はそのまま残る。
        val expected = 20 * log10(3.0)
        assertTrue(abs(loudSnr - softSnr - expected) < 1.5, "loud=$loudSnr soft=$softSnr")
    }

    @Test
    fun nonSpeechSecondsDropVoiceLevelEvenWithVoicedFrames() {
        // 強弱のない音は有声フレームがあっても発話ではないので、声の大きさを返さない。
        val seconds = SpeechActivityDetector().seconds(3) { hum(it) + tone(it) }
        seconds.forEach { assertNull(it.voiceDb) }
    }
}
