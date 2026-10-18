package com.theacar.android.window

import com.theacar.cabin.CabinAir

/**
 * 機器が1行ずつ送るログの読み取り。
 * 測定行は `ガス 204.4kΩ 普段比 -0% 湿度 32.0%` の形式で、暖機中は末尾に `(暖機中)` が付く。
 * 検知したときは機械が読む `EVENT smell drop=42` と、人が読む `におい検知 → 窓を少し開ける` が続く。
 */
internal object Esp32Log {
    private val MEASUREMENT = Regex("ガス ([0-9.]+)kΩ 普段比 -([0-9.]+)% 湿度 ([0-9.]+)%")
    private val SMELL_EVENT = Regex("""^EVENT smell drop=([0-9.]+)$""")

    fun air(line: String): CabinAir? {
        val found = MEASUREMENT.find(line) ?: return null
        val gas = found.groupValues[1].toDoubleOrNull() ?: return null
        val drop = found.groupValues[2].toDoubleOrNull() ?: return null
        val humidity = found.groupValues[3].toDoubleOrNull() ?: return null
        return CabinAir(gas, drop, humidity, line.contains("(暖機中)"))
    }

    /**
     * においを検知して窓を開けた行なら、そのときの普段比を返す。
     * 人が読む行ではなく `EVENT` 行だけを見る。表示用の文言を変えても壊れないようにするため。
     */
    fun smellDrop(line: String): Double? =
        SMELL_EVENT.find(line.trim())?.groupValues?.get(1)?.toDoubleOrNull()
}
