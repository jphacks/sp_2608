package com.theacar.ui

import android.content.Context
import android.content.Intent

const val EXTRA_TAB = "com.theacar.TAB"

private const val NAVIGATION_ACTIVITY = "com.theacar.android.navigation.NavigationActivity"
private const val MAIN_ACTIVITY = "com.theacar.android.MainActivity"

/**
 * 他のタブの画面を開く。マップはアプリの最初の画面なので、その上に積まれた画面を閉じて戻る。
 * 旅の記録・設定はアプリ本体の MainActivity で切り替える。どちらも既にあればそれを再利用し、戻る履歴が増え続けないようにする。
 */
fun Context.openTab(tab: Tab) {
    val intent = Intent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    when (tab) {
        Tab.MAP -> intent.setClassName(this, NAVIGATION_ACTIVITY)
        Tab.RECORD, Tab.SETTINGS -> intent.setClassName(this, MAIN_ACTIVITY).putExtra(EXTRA_TAB, tab.name)
    }
    startActivity(intent)
}
