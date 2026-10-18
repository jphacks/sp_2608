package com.theacar.android.music

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.theacar.music.BgmMusicState
import com.theacar.music.GuestRequestState
import com.theacar.music.SongRequest

/**
 * みんなのリクエスト。QR コードを同乗者に読み取ってもらうと、ブラウザで曲を探して
 * 「次に予約」か「今すぐ流す」を送れる。予約の列は曲の終わりで先頭から流れ、ここで並べ替え・取り消しができる。
 */
@Composable
internal fun GuestRequestPanel(
    bgm: BgmMusicState,
    guests: GuestRequestState,
    guestUrl: (String) -> String,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    canReceive: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("みんなのリクエスト", style = MaterialTheme.typography.titleLarge)
            val code = guests.code
            when {
                code != null -> {
                    Text("同乗者がスマホのカメラで読み取ると、ログインなしで曲を探してリクエストできます。")
                    QrCode(guestUrl(code), Modifier.align(Alignment.CenterHorizontally))
                    Text(guestUrl(code), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (guests.received > 0) Text("この受付で${guests.received}曲届きました", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onClose) { Text("受付を終える") }
                }
                canReceive -> {
                    Text("QR コードを出すと、同乗者が自分のスマホから曲を検索してリクエストできます。景観BGMなしでも使えます。")
                    Button(onClick = onOpen, enabled = canReceive && !guests.opening, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (guests.opening) "準備しています…" else "QR コードで受け付ける")
                    }
                }
                else -> Text("Spotifyの接続・再生先と経路サーバーの設定を確認してください。", style = MaterialTheme.typography.bodyMedium)
            }
            guests.failure?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            bgm.playingRequest?.let { Text("再生中：${it.label()}", style = MaterialTheme.typography.titleSmall) }
            if (bgm.requests.isEmpty()) {
                Text("予約はありません。", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("このあと流れる曲（${bgm.requests.size}曲）", style = MaterialTheme.typography.titleSmall)
                bgm.requests.forEachIndexed { index, request ->
                    RequestRow(index, request, last = index == bgm.requests.lastIndex, onMove = onMove, onRemove = onRemove)
                }
            }
        }
    }
}

@Composable
private fun RequestRow(index: Int, request: SongRequest, last: Boolean, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f)) {
            Text(request.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(listOfNotNull(request.artist.takeIf { it.isNotBlank() }, request.requester?.let { "${it}さん" } ?: "自分")
                .joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onMove(request.id, -1) }, enabled = index > 0,
            modifier = Modifier.semantics { contentDescription = "${request.title}を前へ" }) { Text("↑") }
        TextButton(onClick = { onMove(request.id, 1) }, enabled = !last,
            modifier = Modifier.semantics { contentDescription = "${request.title}を後ろへ" }) { Text("↓") }
        TextButton(onClick = { onRemove(request.id) }) { Text("取消") }
    }
}

private fun SongRequest.label() = "$title · " + (requester?.let { "${it}さんのリクエスト" } ?: "自分のリクエスト")

@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { qrBitmap(text, 600) }
    // 暗い画面でも読み取れるよう、白い余白を付けて黒で描く
    Surface(color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Image(bitmap.asImageBitmap(), "リクエスト用の QR コード", Modifier.padding(12.dp).size(220.dp), filterQuality = FilterQuality.None)
    }
}

/** 誤り訂正 M、静穏域 2 モジュール。モジュール境界がぼけないよう整数倍に拡大して中央に置く。 */
internal fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val scale = maxOf(1, sizePx / matrix.width)
    val drawn = matrix.width * scale
    val offset = (sizePx - drawn) / 2
    val pixels = IntArray(sizePx * sizePx) { android.graphics.Color.WHITE }
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        if (!matrix[x, y]) continue
        for (dy in 0 until scale) for (dx in 0 until scale) {
            pixels[(offset + y * scale + dy) * sizePx + offset + x * scale + dx] = android.graphics.Color.BLACK
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}
