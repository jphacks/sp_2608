package com.theacar.android.memory

import android.graphics.Bitmap
import com.theacar.memory.TripMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class StickerExporter(private val renderer: StickerRenderer, private val store: TripMemoryStore) {
    suspend fun export(memory: TripMemory, photo: Bitmap?) = withContext(Dispatchers.Default) {
        val frame = renderer.render(memory, photo, FRAME_WIDTH_PX)
        val sheet = try { StickerSheet.compose(frame) } finally { frame.recycle() }
        try { store.writeSheet(memory.id) { StickerSheet.writeJpeg(sheet, it) } }
        finally { sheet.recycle() }
        store.sheetFile(memory.id)
    }
}
