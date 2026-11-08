package com.theacar.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.ComposeView

/**
 * Google Play の「目立つ開示」。OS の許可のダイアログを出す直前に、何に使い、どこへ送るかを見せる。
 * 文面はプライバシーポリシー（docs/privacy-policy.md）と合わせる。
 */
enum class PermissionDisclosure(val title: String, val body: String) {
    LOCATION(
        "現在地の使いみち",
        "旅れこは、現在地を地図に出し、経路を調べて案内するために、端末の位置情報を使います。" +
            "取得するのはアプリを使っている間と案内中だけで、バックグラウンドでは取得しません。\n\n" +
            "・経路を調べるとき、出発地と目的地の座標を運営者の経路サーバーへ送ります。\n" +
            "・天気を調べるため、丸めた座標を天気予報サービスの Open-Meteo へ送ります。\n" +
            "・案内中に走った道のりは、旅の記録として端末に保存します。ステッカーを保存すると、共有ページを作るために運営者のサーバーへ送ります。\n\n" +
            "詳しくは、設定のタブのプライバシーポリシーをご覧ください。",
    ),
    MICROPHONE(
        "マイクの使いみち",
        "旅れこは、車内で会話しているかどうかを判定し、音楽の音量と選曲を変えるためにマイクを使います。\n\n" +
            "・音声は端末の中だけで処理し、録音・保存・送信はしません。\n" +
            "・判定の結果（車内のにぎやかさの段階と、1分ごとの会話の割合）を運営者のサーバーへ送り、選曲と旅の記録に使います。\n" +
            "・マイクを使っている間は、通知でお知らせします。\n\n" +
            "許可しなくても、車内の雰囲気を「ふつう」として曲を選びます。詳しくは、設定のタブのプライバシーポリシーをご覧ください。",
    ),
}

/** 開示を見せ、「続ける」を押したら [onContinue] で OS の許可を求める。「今はしない」なら [onDismiss]。 */
fun Activity.disclose(disclosure: PermissionDisclosure, onDismiss: () -> Unit = {}, onContinue: () -> Unit) {
    val view = ComposeView(this)
    fun close(then: () -> Unit) {
        view.post { (view.parent as? ViewGroup)?.removeView(view) }
        then()
    }
    view.setContent {
        TheaCarTheme {
            AlertDialog(
                onDismissRequest = { close(onDismiss) },
                title = { Text(disclosure.title) },
                text = { Text(disclosure.body) },
                confirmButton = { TextButton(onClick = { close(onContinue) }) { Text("続ける") } },
                dismissButton = { TextButton(onClick = { close(onDismiss) }) { Text("今はしない") } },
            )
        }
    }
    addContentView(view, ViewGroup.LayoutParams(0, 0))
}
