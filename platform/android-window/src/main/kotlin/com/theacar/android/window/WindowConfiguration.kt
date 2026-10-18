package com.theacar.android.window

import java.util.UUID

/**
 * 窓を動かす機器の BLE の口。機器のファームウェア（`car_v3_ble.ino`）と同じ値でなければつながらない。
 *
 * 機器はサービス UUID を広告に載せるので、接続先をビルド時に設定する必要はない。
 * 名前は広告の 31 バイトに収まらないため走査応答へ回してあり、拾えないこともある。
 */
data class WindowConfiguration(
    val deviceName: String,
    val service: UUID,
    val command: UUID,
    val log: UUID,
) {
    /**
     * 広告で拾った名前がこの機器のものか。
     * 名前が分からないうちは拒まない。サービス UUID で既に絞り込んでいるため。
     */
    fun matches(advertisedName: String?): Boolean {
        val name = advertisedName?.trim()
        return name.isNullOrEmpty() || name == deviceName
    }

    companion object {
        const val DEVICE_NAME = "stepwgn-window"

        val SERVICE: UUID = UUID.fromString("6e6b1f00-5f3a-4b7c-9d21-8c4f1a2b3c01")
        val COMMAND: UUID = UUID.fromString("6e6b1f01-5f3a-4b7c-9d21-8c4f1a2b3c01")
        val LOG: UUID = UUID.fromString("6e6b1f02-5f3a-4b7c-9d21-8c4f1a2b3c01")

        val Default = WindowConfiguration(DEVICE_NAME, SERVICE, COMMAND, LOG)
    }
}
