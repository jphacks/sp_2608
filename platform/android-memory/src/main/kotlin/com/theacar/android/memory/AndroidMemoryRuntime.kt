package com.theacar.android.memory

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.theacar.memory.TripMemoryEditor
import com.theacar.memory.TripMemoryPublisher
import com.theacar.memory.data.TripMemoryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface AndroidMemoryOwner { val memoryRuntime: AndroidMemoryRuntime }

/** 保存と送信は画面の寿命から切り離し、アプリ全体で共有する。 */
class AndroidMemoryRuntime(application: Application, deviceId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = TripMemoryStore(application)
    @Suppress("DEPRECATION")
    private val metadata = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA).metaData
    private val api = TripMemoryApi(metadata?.getString("com.theacar.MEMORY_WEB_URL").orEmpty(),
        metadata?.getString("com.theacar.MEMORY_WEB_TOKEN").orEmpty().trim(), deviceId)
    private val publisher = TripMemoryPublisher(api, store) { message, error -> Log.w("TripMemory", message, error) }
    private val uploads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            store.refresh()
            uploads.trySend(Unit)
            for (ignored in uploads) publisher.retryPending()
        }
    }

    fun pageUrl(id: String) = api.pageUrl(id)

    suspend fun loadThumbnail(id: String) = withContext(Dispatchers.IO) {
        val file = store.photoFile(id)
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    fun persist(editor: TripMemoryEditor) {
        scope.launch {
            try { editor.commit() } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("TripMemory", "入力を保存できませんでした", error)
            }
        }
    }

    suspend fun publish(id: String) {
        if (!api.configured) return
        store.enqueuePublication(id)
        uploads.trySend(Unit)
    }
}
