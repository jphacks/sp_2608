package com.theacar.android.navigation

import com.theacar.navigation.NavigationFailure

fun NavigationFailure.message(): String = when (this) {
    NavigationFailure.SEARCH -> "検索できませんでした。緯度,経度でも指定できます。"
    NavigationFailure.NO_RESULTS -> "目的地が見つかりません。"
    NavigationFailure.NETWORK -> "経路サーバーへ接続できません。現在の経路は保持しています。"
    NavigationFailure.NO_ROUTE -> "経路が見つかりません。対象地域・時間予算・道路条件・寄り道を確認してください。"
    NavigationFailure.PREVIEW_EXPIRED -> "景観ルートの確認から時間が経過しました。「経路を変更」から再検索してください。"
    NavigationFailure.SCENIC_UNAVAILABLE -> "景観ルートを探せませんでした。景観APIが起動していないか、目的地が景観データの範囲外です。"
    NavigationFailure.LOCATION -> "利用できる位置情報がありません。端末の位置情報をオンにして「現在地」を押してください。屋内の場合は屋外でもお試しください。"
    NavigationFailure.PERMISSION -> "正確な位置情報の許可が必要です。"
    NavigationFailure.AUTHORIZATION -> "設定から経路 API のトークンを確認してください。"
    NavigationFailure.TERMS -> "利用条件を確認してください。"
    NavigationFailure.NOT_READY -> "経路サーバーの設定を確認してください。"
    NavigationFailure.TIMEOUT -> "時間がかかっています。少し待って再試行してください。"
    NavigationFailure.START -> "案内を開始できませんでした。"
    NavigationFailure.STOP -> "案内を終了できませんでした。"
    NavigationFailure.LIMIT_REACHED -> "検索回数の上限です。時間を空けて再試行してください。"
    NavigationFailure.AWAY_FROM_ROUTE -> "ルートから離れています。「経路を変更」から今いる場所で探し直してください。"
}
