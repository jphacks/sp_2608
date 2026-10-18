package com.theacar.android.memory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.theacar.memory.PhotoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

const val PHOTO_LONG_SIDE = 1600

/** 選んだ写真を長辺 1600px に縮めて読み、端末内でデフォルメする。 */
class PhotoStylizer(private val context: Context) {
    suspend fun load(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val scale = max(info.size.width, info.size.height) / PHOTO_LONG_SIDE.toFloat()
                    if (scale > 1f) decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_LONG_SIDE) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()?.let { it.copy(Bitmap.Config.ARGB_8888, false) }
    }

    suspend fun stylize(source: Bitmap, style: PhotoStyle): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width; val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val styled = when (style) {
            PhotoStyle.WATERCOLOR -> PhotoFilters.watercolor(pixels, width, height)
            PhotoStyle.POSTER -> PhotoFilters.poster(pixels, width, height)
            PhotoStyle.FILM -> PhotoFilters.film(pixels, width, height)
        }
        Bitmap.createBitmap(styled, width, height, Bitmap.Config.ARGB_8888)
    }
}
