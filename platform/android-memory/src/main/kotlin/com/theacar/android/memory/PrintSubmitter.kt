package com.theacar.android.memory

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/** 印刷の受付結果。画面に出す案内文。 */
data class PrintTicket(val message: String)

/** コンビニ印刷に画像を渡す先。どちらもシャープのネットワークプリントに登録される。 */
enum class PrintRoute(
    val label: String,
    val detail: String,
    val packageName: String,
    val appName: String,
    val requiredTitle: String,
    val requiredMessage: String,
) {
    LINE(
        label = "ネットワークプリントの公式 LINE から印刷",
        detail = "印刷アプリ不要。LINE で公式アカウントに画像を送り、届いたユーザー番号を店頭で入力します",
        packageName = "jp.naver.line.android",
        appName = "LINE",
        requiredTitle = "LINE が必要です",
        requiredMessage = "この方法では LINE から「ネットワークプリント」の公式アカウントに画像を送ります。Play ストアで LINE を入手しますか？",
    ),
    APP(
        label = "ネットワークプリントの公式アプリから印刷",
        detail = "アプリに登録し、出てきた QR を店頭のマルチコピー機にかざします",
        packageName = "jp.co.sharp.printsystem.networkprint",
        appName = "ネットワークプリント",
        requiredTitle = "印刷アプリが必要です",
        requiredMessage = "コンビニでの印刷は、シャープのアプリ「ネットワークプリント」からシールの画像を登録します。Play ストアで入手しますか？",
    );

    val storeUri: String get() = "market://details?id=$packageName"
    val storeWebUrl: String get() = "https://play.google.com/store/apps/details?id=$packageName"

    /** 渡したあとの案内。[direct] はアプリを直接起動できたとき。 */
    fun ticketMessage(direct: Boolean): String = when {
        !direct -> "共有先で $appName を選んでください"
        this == LINE -> "LINE で「ネットワークプリント」の公式アカウントを選んで送り、「お願い」を押すとユーザー番号が届きます。店頭でその番号を入力し、シール紙（L判）を選んでください"
        else -> "ネットワークプリントに渡しました。登録後に出る QR を店頭のマルチコピー機にかざしてください"
    }
}

/** 印刷先の識別子と店頭の案内。 */
object NetworkPrint {
    const val MIME_TYPE = "image/jpeg"
    /** 公式サイト（networkprint.ne.jp）が案内している LINE 公式アカウントの友だち追加リンク。 */
    const val LINE_ADD_FRIEND_URL = "https://line.me/R/ti/p/%40wog2593e"
    const val LINE_ADD_FRIEND_LABEL = "公式アカウントを友だち追加"
    const val STEPS = "1. 公式 LINE か公式アプリでネットワークプリントに登録する\n2. 店頭のマルチコピー機でネットワークプリントを選び、シール紙（L判）で印刷する"
    const val OPEN_STORE_LABEL = "ストアを開く"
}

/** そのパッケージのアプリが入っているか。 */
fun Context.isAppInstalled(packageName: String): Boolean = try {
    packageManager.getPackageInfo(packageName, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

/** 面付け済みの JPEG をコンビニ印刷に渡す口。別の渡し方にするときはここを差し替える。 */
interface PrintSubmitter {
    suspend fun submit(sheet: File, route: PrintRoute): PrintTicket
}

/** 選んだ経路のアプリに直接渡し、起動できなければ共有シートで渡す。 */
class SharePrintSubmitter(private val context: Context) : PrintSubmitter {
    override suspend fun submit(sheet: File, route: PrintRoute): PrintTicket {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.memory.fileprovider", sheet)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = NetworkPrint.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startDirect(send, route.packageName)) return PrintTicket(route.ticketMessage(direct = true))
        context.startActivity(Intent.createChooser(send, "印刷に使うアプリ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return PrintTicket(route.ticketMessage(direct = false))
    }

    private fun startDirect(send: Intent, packageName: String): Boolean {
        val direct = Intent(send).setPackage(packageName)
        if (direct.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(direct)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
