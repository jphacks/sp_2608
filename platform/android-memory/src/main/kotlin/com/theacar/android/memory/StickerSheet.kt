package com.theacar.android.memory

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** L判（89×127 mm）600dpi の面付け。同じコマを 2×2 に並べ、境に切り取り線を入れる。 */
object StickerSheet {
    const val WIDTH_PX = 2102
    const val HEIGHT_PX = 3000
    const val JPEG_QUALITY = 95
    const val GALLERY_FOLDER = "旅れこ"
    private const val MUTED = 0xFF5C6B63.toInt()

    fun compose(frame: Bitmap): Bitmap {
        require(frame.width == FRAME_WIDTH_PX && frame.height == FRAME_HEIGHT_PX)
        val sheet = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFFFFFFFF.toInt())
        for (row in 0 until 2) for (column in 0 until 2) {
            canvas.drawBitmap(frame, column * FRAME_WIDTH_PX.toFloat(), row * FRAME_HEIGHT_PX.toFloat(), null)
        }
        val cut = Paint().apply {
            color = MUTED; style = Paint.Style.STROKE; strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
        }
        canvas.drawLine(FRAME_WIDTH_PX.toFloat(), 0f, FRAME_WIDTH_PX.toFloat(), HEIGHT_PX.toFloat(), cut)
        canvas.drawLine(0f, FRAME_HEIGHT_PX.toFloat(), WIDTH_PX.toFloat(), FRAME_HEIGHT_PX.toFloat(), cut)
        return sheet
    }

    fun writeJpeg(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    }

    /**
     * Android 10 以上では Pictures/旅れこ にも保存する。それ以外は filesDir と FileProvider 共有だけ。
     * [existing] に前回保存した行があれば上書きし、行が消えていれば新しく挿入する。
     */
    fun saveToGallery(context: Context, sheet: File, displayName: String, existing: Uri?): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        if (existing != null) {
            val overwritten = runCatching {
                resolver.openOutputStream(existing, "wt")?.use { out -> sheet.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (overwritten) return existing
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { out -> sheet.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        }.onFailure { resolver.delete(uri, null, null) }.getOrNull()
    }
}
