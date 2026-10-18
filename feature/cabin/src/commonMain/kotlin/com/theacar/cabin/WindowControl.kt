package com.theacar.cabin

import kotlinx.coroutines.flow.StateFlow

/** 窓ガラスを動かす向き。OPEN は窓を下げ、CLOSE は窓を上げる。 */
enum class WindowMove { OPEN, CLOSE }

/** 窓を動かす機器との接続状態。 */
enum class WindowLink { UNCONFIGURED, CONNECTING, CONNECTED, UNAVAILABLE }

/**
 * 車内の空気の読み取り値。
 * dropPercent は機器が保持する普段の値からの低下率で、大きいほどにおいが強い。
 */
data class CabinAir(
    val gasKiloOhm: Double,
    val dropPercent: Double,
    val humidityPercent: Double,
    val warmingUp: Boolean,
)

/** 窓の操作を受け付けられなかったときに送出する。 */
class WindowControlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 窓を動かす機器への指示。実装は data / platform が持つ。
 * suspend 関数は指示が受理されたときに返り、失敗時は WindowControlException を送出する。
 * 窓が動き終わるまで待つ契約ではない。
 */
interface WindowGateway {
    val link: StateFlow<WindowLink>
    val air: StateFlow<CabinAir?>

    suspend fun move(move: WindowMove)

    suspend fun stop()
}
