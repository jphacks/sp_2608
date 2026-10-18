package com.theacar.android.window

import java.io.ByteArrayOutputStream

/**
 * BLE の notify で届く断片を、改行で区切られた行に組み立て直す。
 *
 * 機器は1行ずつ送るが、notify で一度に運べるのは MTU-3 バイトなので、行が途中で切れて届く。
 * 逆に1回の notify に複数行が入ることもある。日本語が複数の断片に分かれても壊れないように、
 * 改行までは生のバイトで溜めてから文字へ直す。
 */
internal class BleLines(private val maxLineBytes: Int = MAX_LINE_BYTES) {
    private val line = ByteArrayOutputStream()

    /** 受け取った断片から、できあがった行を順に返す。行の途中で終わる分は次の呼び出しへ持ち越す。 */
    fun feed(chunk: ByteArray): List<String> {
        val completed = mutableListOf<String>()
        for (byte in chunk) {
            when (val value = byte.toInt()) {
                NEW_LINE -> {
                    completed.add(line.toString("UTF-8"))
                    line.reset()
                }
                CARRIAGE_RETURN -> Unit
                // 改行が来ないまま延々と届いても溢れないように、長すぎる行は切り捨てる
                else -> if (line.size() < maxLineBytes) line.write(value)
            }
        }
        return completed
    }

    private companion object {
        const val MAX_LINE_BYTES = 512
        const val NEW_LINE = '\n'.code
        const val CARRIAGE_RETURN = '\r'.code
    }
}
