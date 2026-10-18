package com.theacar.android.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhotoFiltersTest {
    private val width = 64
    private val height = 48
    // 左右で色が変わるグラデーションと、中央に暗い四角を置いた見本
    private val source = IntArray(width * height) { i ->
        val x = i % width; val y = i / width
        val inBox = x in 24..40 && y in 16..32
        val r = if (inBox) 30 else 255 * x / (width - 1)
        val g = if (inBox) 40 else 180
        val b = if (inBox) 50 else 255 - 255 * x / (width - 1)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun assertValidArgb(pixels: IntArray) {
        assertEquals(width * height, pixels.size)
        pixels.forEach { assertEquals(0xFF, (it ushr 24), "alpha must be opaque") }
    }

    @Test fun watercolorKeepsSizeAndOpacity() {
        val out = PhotoFilters.watercolor(source, width, height)
        assertValidArgb(out)
        assertNotEquals(source.toList(), out.toList())
    }

    @Test fun posterKeepsSizeAndReducesColours() {
        val out = PhotoFilters.poster(source, width, height)
        assertValidArgb(out)
        val sourceColours = source.toSet().size
        assertTrue(out.toSet().size < sourceColours, "poster should use fewer colours than the source")
    }

    @Test fun filmKeepsSizeAndLiftsBlacks() {
        val out = PhotoFilters.film(source, width, height)
        assertValidArgb(out)
        val center = out[24 * width + 32]
        assertTrue(((center shr 16) and 0xFF) >= 10, "blacks should be lifted")
        // 四隅は中央より暗い
        val corner = out[0]
        fun luma(c: Int) = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
        val sourceCorner = source[0]
        assertTrue(luma(corner) < luma(sourceCorner))
    }

    @Test fun filtersAreDeterministic() {
        assertEquals(PhotoFilters.watercolor(source, width, height).toList(), PhotoFilters.watercolor(source, width, height).toList())
    }

    @Test fun largeImageFiltersFinishQuickly() {
        val w = 1600; val h = 1200
        val random = java.util.Random(42)
        val big = IntArray(w * h) { (0xFF shl 24) or (random.nextInt() and 0xFFFFFF) }
        // JIT の立ち上がりを含めないよう 1 回温めてから計る
        PhotoFilters.watercolor(big, w, h); PhotoFilters.poster(big, w, h); PhotoFilters.film(big, w, h)
        val limitMs = 1500L
        for ((name, filter) in listOf<Pair<String, (IntArray, Int, Int) -> IntArray>>(
            "watercolor" to PhotoFilters::watercolor, "poster" to PhotoFilters::poster, "film" to PhotoFilters::film,
        )) {
            val start = System.nanoTime()
            val out = filter(big, w, h)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("PhotoFilters.$name 1600x1200: ${elapsedMs}ms")
            assertEquals(w * h, out.size)
            assertTrue(elapsedMs < limitMs, "$name took ${elapsedMs}ms (limit ${limitMs}ms)")
        }
    }

    @Test fun smoothPreservesFlatColour() {
        val flat = IntArray(width * height) { 0xFF336699.toInt() }
        assertEquals(flat.toList(), PhotoFilters.smooth(flat, width, height, 6).toList())
    }
}
