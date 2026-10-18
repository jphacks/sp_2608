package com.theacar.android.memory

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 誤り訂正 M、静穏域 2 モジュール。1 モジュール = 1 セルの行列を返す。 */
fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))

/** 行列を [sizePx] 角の Bitmap にする。モジュール境界がぼけないよう整数倍に丸めて中央に置く。 */
fun qrBitmap(text: String, sizePx: Int, dark: Int, light: Int): Bitmap {
    val matrix = qrMatrix(text)
    val scale = maxOf(1, sizePx / matrix.width)
    val drawn = matrix.width * scale
    val offset = (sizePx - drawn) / 2
    val pixels = IntArray(sizePx * sizePx) { light }
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        if (!matrix[x, y]) continue
        for (dy in 0 until scale) for (dx in 0 until scale) {
            val px = offset + x * scale + dx; val py = offset + y * scale + dy
            if (px in 0 until sizePx && py in 0 until sizePx) pixels[py * sizePx + px] = dark
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}
