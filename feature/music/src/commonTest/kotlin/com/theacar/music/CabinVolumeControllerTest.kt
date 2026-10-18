package com.theacar.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CabinVolumeControllerTest {
    private val noise = -45.0

    /** snr が null の秒は発話なし。 */
    private fun second(snr: Double?) = CabinSecond(snr != null, snr?.let { noise + it }, noise)

    /** [fromSeconds] から [toSeconds] の手前まで1秒ずつ流し、各秒の段数を返す。 */
    private fun CabinVolumeController.run(fromSeconds: Long, toSeconds: Long, snr: (Long) -> Double?): List<Int> =
        (fromSeconds until toSeconds).map { observe(it * 1_000, second(snr(it))) }

    /** 普段の声（snr 15dB）を 40 秒覚えさせる。 */
    private fun warmed() = CabinVolumeController().apply { run(0, 40) { 15.0 } }

    @Test
    fun usualVoiceKeepsVolume() {
        val controller = CabinVolumeController()
        assertTrue(controller.run(0, 120) { 15.0 }.all { it == 0 })
    }

    @Test
    fun initialVoiceAboveDefaultBecomesBaselineWithoutLowering() {
        val controller = CabinVolumeController()
        assertTrue(controller.run(0, 120) { 25.0 }.all { it == 0 })
        assertEquals(25.0, controller.baselineSnrDb)
        assertTrue(controller.calibrated)
    }

    @Test
    fun onlySpeakingSecondsCountTowardsCalibration() {
        val controller = CabinVolumeController()
        controller.run(0, 60) { if (it % 3 == 0L) 25.0 else null }
        assertFalse(controller.calibrated)
        assertEquals(0, controller.steps)
        controller.run(60, 90) { if (it % 3 == 0L) 25.0 else null }
        assertTrue(controller.calibrated)
        assertEquals(25.0, controller.baselineSnrDb)
    }

    @Test
    fun loudVoiceLowersOneStepAtATime() {
        val controller = warmed()
        val steps = controller.run(40, 60) { 22.0 }
        // 窓の半分以上が大声になった秒で1段下げ、そこから 10 秒は次を下げない。
        val first = steps.indexOf(1)
        assertTrue(first >= 0)
        assertTrue(steps.drop(first).take(10).all { it == 1 }, "$steps")
        assertEquals(2, steps[first + 10])
    }

    @Test
    fun stopsAtMaxSteps() {
        val controller = warmed()
        assertEquals(3, controller.run(40, 200) { 22.0 }.last())
    }

    @Test
    fun restoresStepByStepAfterSilence() {
        val controller = warmed()
        controller.run(40, 200) { 22.0 }
        val steps = controller.run(200, 320) { null }
        // 静かになってすぐは戻さない。
        assertTrue(steps.take(30).all { it == 3 }, "$steps")
        assertEquals(0, steps.last())
        val changes = steps.indices.filter { it > 0 && steps[it] != steps[it - 1] }
        assertEquals(3, changes.size)
        assertTrue(changes.zipWithNext().all { (a, b) -> b - a >= 10 }, "$changes")
    }

    @Test
    fun restoresWhenVoiceCalmsDown() {
        val controller = warmed()
        controller.run(40, 50) { 22.0 }
        assertEquals(1, controller.steps)
        assertEquals(0, controller.run(50, 120) { 16.0 }.last())
    }

    @Test
    fun voiceBetweenThresholdsHoldsSteps() {
        val controller = warmed()
        controller.run(40, 50) { 22.0 }
        assertEquals(1, controller.steps)
        // 普段 + 4.5dB は、下げるほどでも戻すほどでもない。
        assertTrue(controller.run(50, 200) { 19.5 }.all { it == 1 })
    }

    @Test
    fun fewSpeakingSecondsAreIgnored() {
        val controller = warmed()
        controller.run(40, 60) { null }
        assertTrue(controller.run(60, 200) { if (it % 4 == 0L) 30.0 else null }.all { it == 0 })
    }

    @Test
    fun baselineIsFrozenWhileLowered() {
        val controller = warmed()
        controller.run(40, 50) { 22.0 }
        assertEquals(1, controller.steps)
        val baseline = controller.baselineSnrDb
        controller.run(50, 70) { 10.0 }
        assertEquals(1, controller.steps)
        assertEquals(baseline, controller.baselineSnrDb)
    }

    @Test
    fun baselineUsesDefaultUntilEnoughSamples() {
        val controller = CabinVolumeController()
        controller.run(0, 29) { 10.0 }
        assertEquals(15.0, controller.baselineSnrDb)
        controller.run(29, 30) { 10.0 }
        assertEquals(10.0, controller.baselineSnrDb)
    }

    @Test
    fun resetKeepsBaselineAndStartForgetsIt() {
        val controller = CabinVolumeController()
        controller.run(0, 40) { 10.0 }
        controller.run(40, 50) { 20.0 }
        assertEquals(1, controller.steps)
        controller.reset()
        assertEquals(0, controller.steps)
        assertEquals(10.0, controller.baselineSnrDb)
        controller.start()
        assertEquals(15.0, controller.baselineSnrDb)
    }
}
