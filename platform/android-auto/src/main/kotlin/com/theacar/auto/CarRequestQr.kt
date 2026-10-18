package com.theacar.auto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 車の画面に重ねる QR コード。白地に黒で、読み取りに要る余白（静穏域）を含めて描く。
 * モジュール境界がぼけないよう、1モジュールを整数倍の画素にして中央に置く。
 */
internal fun carQrBitmap(text: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val size = maxOf(sizePx, matrix.width)
    val scale = size / matrix.width
    val offset = (size - matrix.width * scale) / 2
    val pixels = IntArray(size * size) { Color.WHITE }
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        if (!matrix[x, y]) continue
        for (dy in 0 until scale) for (dx in 0 until scale) pixels[(offset + y * scale + dy) * size + offset + x * scale + dx] = Color.BLACK
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** 見えている範囲（テンプレートの部品に隠れない範囲）の右下に置く QR の一辺。見えている範囲の短い辺の半分ほど。 */
internal fun carQrSide(visibleWidth: Int, visibleHeight: Int, density: Float): Int {
    val shorter = minOf(visibleWidth, visibleHeight)
    return (shorter * 0.5f).toInt().coerceIn((120 * density).toInt(), (260 * density).toInt()).coerceAtMost(shorter)
}
