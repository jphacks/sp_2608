package com.theacar.android.memory

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.theacar.model.Coordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 写真の EXIF から撮影位置を読む。書類の選択画面（OpenDocument）が返す URI は、API 29 以上では
 * MediaStore の URI に変換して setRequireOriginal を掛ける。変換できなければ元の URI のまま試す。
 * ACCESS_MEDIA_LOCATION が許可されていないと null になる。
 */
object PhotoMetadata {
    suspend fun readLocation(context: Context, uri: Uri): Coordinate? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 29) {
                val media = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: uri
                runCatching { MediaStore.setRequireOriginal(media) }.getOrNull()?.let(::add)
            }
            add(uri)
        }
        candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                context.contentResolver.openInputStream(candidate)?.use { stream ->
                    ExifInterface(stream).latLong?.let { Coordinate(it[0], it[1]) }
                }
            }.getOrNull()
        }
    }
}
