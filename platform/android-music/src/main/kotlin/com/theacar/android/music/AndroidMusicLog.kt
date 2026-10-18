package com.theacar.android.music

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.theacar.bgm.MusicLogApi
import com.theacar.music.MusicLogUploader
import com.theacar.music.PendingMusicLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 旅の音楽の記録を Web 版へ送る [MusicLogUploader] を組み立てる。
 * 送り先とトークンはビルド時の値（MEMORY_WEB_URL / MEMORY_WEB_TOKEN）。URL が未設定なら何もしない。
 */
internal fun androidMusicLogUploader(context: Context): MusicLogUploader {
    @Suppress("DEPRECATION")
    val metaData = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    val sender = MusicLogApi(metaData?.getString("com.theacar.MEMORY_WEB_URL").orEmpty(),
        metaData?.getString("com.theacar.MEMORY_WEB_TOKEN").orEmpty())
    return MusicLogUploader(sender, FileMusicLogStore(File(context.filesDir, "music-logs")), { installId(context) },
        onError = { message, error -> Log.w(TAG, message, error) })
}

/** 送れていない記録を、1件1ファイル（<id>.json）で端末に残す。 */
internal class FileMusicLogStore(
    private val directory: File,
    private val write: (File, String) -> Unit = { file, json ->
        FileOutputStream(file).use { stream -> stream.write(json.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
    },
) : PendingMusicLogStore {
    override suspend fun save(id: String, json: String) = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(id))
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("音楽ログの保存先を作成できません")
        val temp = File.createTempFile("log-$id-", ".tmp", directory)
        try {
            write(temp, json)
            Files.move(temp.toPath(), File(directory, "$id.json").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { temp.delete() }
    }

    override suspend fun pending() = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }
            .map { it.nameWithoutExtension }
    }

    override suspend fun read(id: String) = withContext(Dispatchers.IO) {
        File(directory, "$id.json").readText()
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { File(directory, "$id.json").delete() }
    }
}

private const val TAG = "MusicLogUploader"
