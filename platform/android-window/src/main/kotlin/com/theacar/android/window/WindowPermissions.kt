package com.theacar.android.window

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * BLE で機器を探して繋ぐために利用者へ求める許可。
 * 窓の画面を開いたときに求める。地図や音楽の通信には関係しない。
 */
object WindowPermissions {
    fun required(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Android 11 以下は BLE の探索に位置情報の許可が要る。アプリが地図のために宣言している分を使う。
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(context: Context): Boolean = required().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
