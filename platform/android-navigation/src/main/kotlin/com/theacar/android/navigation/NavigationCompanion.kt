package com.theacar.android.navigation

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ナビ画面に、ナビに依存しない機能（景観BGMなど）を差し込む口。
 *
 * ナビのモジュールは差し込む側の型を知らない。Application が実装を組み立てて渡す。
 * 案内の開始・終了の通知はナビの状態から Application 側が行うので、ここでは画面の部品と権限だけを扱う。
 */
interface NavigationCompanion {
    /** ルートのプレビューで「案内を開始」の直前に出す。 */
    @Composable
    fun PreviewContent()

    /** 案内中に、残り時間のパネルの上に出す。再生中の表示はミニプレーヤーが担うので、通常は何も出さない。 */
    @Composable
    fun GuidanceContent() = Unit

    /** 「案内を開始」で先に求める実行時権限。許可されなくても案内は始める。 */
    fun permissionsBeforeStart(): List<String> = emptyList()

    /** ナビ画面が前面に戻ったとき。別アプリから戻った後の状態の取り直しに使う。 */
    fun onScreenResumed() = Unit

    /**
     * 車の画面に QR コードで出せるもの（同乗者のリクエストの受付など）。
     * [CarShareState.available] が偽なら、出すためのボタンを置かない。
     */
    val carShare: StateFlow<CarShareState> get() = NO_CAR_SHARE

    /** QR コードを出す直前に呼ぶ。受付がまだ無ければ作る。URL は [carShare] に届く。 */
    fun prepareCarShare() = Unit
}

/** 車の画面に出す QR コード。[url] が null の間は準備中。 */
data class CarShareState(val available: Boolean = false, val url: String? = null, val label: String = "")

private val NO_CAR_SHARE: StateFlow<CarShareState> = MutableStateFlow(CarShareState())

interface NavigationCompanionOwner {
    val navigationCompanion: NavigationCompanion?
}
