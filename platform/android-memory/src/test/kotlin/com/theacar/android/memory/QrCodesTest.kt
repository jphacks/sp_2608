package com.theacar.android.memory

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrCodesTest {
    @Test fun matrixHasQuietZoneAndDecodesBack() {
        val text = "https://example.com/t/8f2c1a3b-0000-4000-8000-000000000001"
        val matrix = qrMatrix(text)
        assertEquals(matrix.width, matrix.height)
        // 外側 2 モジュールは静穏域
        for (i in 0 until matrix.width) for (edge in 0 until 2) {
            assertFalse(matrix[i, edge]); assertFalse(matrix[edge, i]); assertFalse(matrix[i, matrix.height - 1 - edge]); assertFalse(matrix[matrix.width - 1 - edge, i])
        }
        assertTrue(matrix[2, 2], "finder pattern starts after the quiet zone")
        val scale = 4
        val size = matrix.width * scale
        val luminance = ByteArray(size * size) { i ->
            val x = (i % size) / scale; val y = (i / size) / scale
            if (matrix[x, y]) 0 else 255.toByte()
        }
        val source = object : LuminanceSource(size, size) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = luminance.copyOfRange(y * size, (y + 1) * size)
            override fun getMatrix(): ByteArray = luminance
        }
        assertEquals(text, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
    }
}
