package com.theacar.music

import com.theacar.sensing.AudioFrame
import com.theacar.sensing.InputStatus
import com.theacar.sensing.SharedSensorFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CabinListening { OFF, ACTIVE, PERMISSION_REQUIRED, UNAVAILABLE }

/** 車内の雰囲気の判定。[state] は判定中だけ値を持つ。 */
data class CabinStatus(val listening: CabinListening = CabinListening.OFF, val state: CabinState? = null)

/**
 * 共有のマイク入力から1秒ごとの発話の有無を取り出し、車内の状態（[CabinMoodTracker]）を決める。
 * 音声はこのクラスの外へ出さない。[onSecond] に渡るのは発話の有無と声・雑音のレベル（[CabinSecond]）だけ。
 * マイクは [start] から [stop] までのあいだだけ購読する。ほかに購読者がいなければ収音も止まる。
 */
class CabinSpeechMonitor(
    private val microphone: SharedSensorFeed<AudioFrame>,
    private val scope: CoroutineScope,
    /** 単調増加する時刻。長時間運転と無音の長さを数える。 */
    private val nowMillis: () -> Long,
    private val onSecond: (CabinSecond) -> Unit = {},
    private val tracker: CabinMoodTracker = CabinMoodTracker(),
) {
    private val mutableStatus = MutableStateFlow(CabinStatus())
    val status = mutableStatus.asStateFlow()
    private var job: Job? = null

    /** ドライブの始まり。長時間運転と無音の長さはここから数える。 */
    fun beginDrive() = tracker.start(nowMillis())

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                microphone.status.collect { input ->
                    val listening = input.listening()
                    mutableStatus.update { it.copy(listening = listening, state = it.state.takeIf { listening == CabinListening.ACTIVE }) }
                }
            }
            val detector = SpeechActivityDetector()
            microphone.samples.collect { sample ->
                val second = detector.accept(sample.value) ?: return@collect
                val now = nowMillis()
                tracker.observe(now, second.speaking)
                onSecond(second)
                mutableStatus.update { it.copy(state = tracker.state(now)) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableStatus.value = CabinStatus()
    }

    /** 推薦に渡す車内の状態。判定できないあいだは normal。 */
    fun current(): CabinState {
        if (status.value.listening != CabinListening.ACTIVE) return CabinState.NORMAL
        return tracker.state(nowMillis()).also { state -> mutableStatus.update { it.copy(state = state) } }
    }
}

private fun InputStatus.listening() = when (this) {
    InputStatus.Active -> CabinListening.ACTIVE
    InputStatus.PermissionRequired -> CabinListening.PERMISSION_REQUIRED
    InputStatus.Unavailable, InputStatus.Failed -> CabinListening.UNAVAILABLE
    InputStatus.Idle -> CabinListening.OFF
}
