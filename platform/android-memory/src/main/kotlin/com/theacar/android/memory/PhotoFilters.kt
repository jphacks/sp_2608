package com.theacar.android.memory

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 写真のデフォルメ。ARGB の IntArray を受け取り、同じ大きさの IntArray を返す。
 * Android の Bitmap に依存しないので JVM の単体テストで確認できる。
 */
@Suppress("NOTHING_TO_INLINE")
object PhotoFilters {
    /** 水彩: 色をなだらかにして淡い面に分け、輪郭を薄く重ね、紙の質感とにじみを足す。 */
    fun watercolor(pixels: IntArray, width: Int, height: Int): IntArray {
        check(pixels, width, height)
        val base = smooth(pixels, width, height, 6)
        val edges = sobel(smooth(pixels, width, height, 2), width, height)
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val c = base[i]
                val bleed = 1f + 0.05f * lowFrequencyNoise(x, y, 64, 11)
                val grain = 6f * hashNoise(x, y, 23)
                val edge = 1f - 0.35f * min(1f, edges[i] * 2.2f)
                out[i] = argb(
                    watercolorChannel(red(c), edge, bleed, grain),
                    watercolorChannel(green(c), edge, bleed, grain),
                    watercolorChannel(blue(c), edge, bleed, grain),
                )
            }
        }
        return out
    }

    private inline fun watercolorChannel(v: Int, edge: Float, bleed: Float, grain: Float): Int {
        val band = ((v / 26f).roundToInt() * 26f)
        val soft = band * 0.55f + v * 0.45f
        val light = soft + (255f - soft) * 0.12f
        return clamp((light * edge * bleed + grain).roundToInt())
    }

    /** ポスター風: 色数を減らして面で塗り分け、コントラストを上げ、細い輪郭線を入れる。 */
    fun poster(pixels: IntArray, width: Int, height: Int): IntArray {
        check(pixels, width, height)
        val base = smooth(pixels, width, height, 2)
        val edges = sobel(base, width, height)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = base[i]
            var r = red(c).toFloat(); var g = green(c).toFloat(); var b = blue(c).toFloat()
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            r = gray + (r - gray) * 1.35f; g = gray + (g - gray) * 1.35f; b = gray + (b - gray) * 1.35f
            var qr = posterQuantize(r); var qg = posterQuantize(g); var qb = posterQuantize(b)
            if (edges[i] > 0.28f) {
                qr = (qr * 0.25f + INK_R * 0.75f).roundToInt(); qg = (qg * 0.25f + INK_G * 0.75f).roundToInt(); qb = (qb * 0.25f + INK_B * 0.75f).roundToInt()
            }
            out[i] = argb(qr, qg, qb)
        }
        return out
    }

    private inline fun posterQuantize(v: Float): Int {
        val contrast = (v - 128f) * 1.3f + 128f
        return clamp(((contrast / 51f).roundToInt() * 51f).roundToInt())
    }

    /** フィルムカメラ風: 彩度を落として暖色に寄せ、黒を持ち上げ、粒子と四隅の減光を足す。 */
    fun film(pixels: IntArray, width: Int, height: Int): IntArray {
        check(pixels, width, height)
        val out = IntArray(pixels.size)
        val cx = (width - 1) / 2f; val cy = (height - 1) / 2f
        val radius = sqrt(cx * cx + cy * cy)
        for (y in 0 until height) {
            val row = y * width
            val dy2 = (y - cy) * (y - cy)
            for (x in 0 until width) {
                val i = row + x
                val c = pixels[i]
                var r = red(c).toFloat(); var g = green(c).toFloat(); var b = blue(c).toFloat()
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                r = gray + (r - gray) * 0.8f + 12f; g = gray + (g - gray) * 0.8f + 4f; b = gray + (b - gray) * 0.8f - 14f
                val d = sqrt((x - cx) * (x - cx) + dy2) / radius
                val vignette = 1f - 0.35f * smoothstep(0.55f, 1.0f, d)
                val grain = 10f * hashNoise(x, y, 7)
                out[i] = argb(
                    clamp((filmLift(r) * vignette + grain).roundToInt()),
                    clamp((filmLift(g) * vignette + grain).roundToInt()),
                    clamp((filmLift(b) * vignette + grain).roundToInt()),
                )
            }
        }
        return out
    }

    private inline fun filmLift(v: Float) = 22f + v.coerceIn(0f, 255f) * (238f - 22f) / 255f

    private const val INK_R = 0x1E; private const val INK_G = 0x2A; private const val INK_B = 0x24

    private fun check(pixels: IntArray, width: Int, height: Int) { require(width > 0 && height > 0 && pixels.size == width * height) }

    /** 縮小してから拡大し、細部をならす。 */
    internal fun smooth(pixels: IntArray, width: Int, height: Int, factor: Int): IntArray {
        if (factor <= 1) return pixels.copyOf()
        val sw = max(1, width / factor); val sh = max(1, height / factor)
        val small = IntArray(sw * sh)
        for (sy in 0 until sh) for (sx in 0 until sw) {
            val x0 = sx * width / sw; val x1 = max(x0 + 1, (sx + 1) * width / sw)
            val y0 = sy * height / sh; val y1 = max(y0 + 1, (sy + 1) * height / sh)
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in y0 until y1) for (x in x0 until x1) { val c = pixels[y * width + x]; r += red(c); g += green(c); b += blue(c); n++ }
            small[sy * sw + sx] = argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            val fy = (y + 0.5f) * sh / height - 0.5f
            val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = min(sh - 1, y0 + 1); val ty = (fy - y0).coerceIn(0f, 1f)
            val row0 = y0 * sw; val row1 = y1 * sw; val outRow = y * width
            for (x in 0 until width) {
                val fx = (x + 0.5f) * sw / width - 0.5f
                val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = min(sw - 1, x0 + 1); val tx = (fx - x0).coerceIn(0f, 1f)
                val a = small[row0 + x0]; val bb = small[row0 + x1]; val cc = small[row1 + x0]; val d = small[row1 + x1]
                out[outRow + x] = argb(
                    bilinear(red(a), red(bb), red(cc), red(d), tx, ty),
                    bilinear(green(a), green(bb), green(cc), green(d), tx, ty),
                    bilinear(blue(a), blue(bb), blue(cc), blue(d), tx, ty),
                )
            }
        }
        return out
    }

    private inline fun bilinear(a: Int, b: Int, c: Int, d: Int, tx: Float, ty: Float): Int {
        val top = a * (1 - tx) + b * tx
        val bottom = c * (1 - tx) + d * tx
        return (top * (1 - ty) + bottom * ty).roundToInt()
    }

    /** 明度の勾配の強さ（0..1 程度）。 */
    internal fun sobel(pixels: IntArray, width: Int, height: Int): FloatArray {
        val gray = FloatArray(pixels.size)
        for (i in pixels.indices) { val c = pixels[i]; gray[i] = (0.299f * red(c) + 0.587f * green(c) + 0.114f * blue(c)) / 255f }
        val out = FloatArray(pixels.size)
        for (y in 0 until height) {
            val up = max(0, y - 1) * width; val mid = y * width; val down = min(height - 1, y + 1) * width
            for (x in 0 until width) {
                val xl = max(0, x - 1); val xr = min(width - 1, x + 1)
                val ul = gray[up + xl]; val uc = gray[up + x]; val ur = gray[up + xr]
                val ml = gray[mid + xl]; val mr = gray[mid + xr]
                val dl = gray[down + xl]; val dc = gray[down + x]; val dr = gray[down + xr]
                val gx = -ul - 2 * ml - dl + ur + 2 * mr + dr
                val gy = -ul - 2 * uc - ur + dl + 2 * dc + dr
                out[mid + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /** 画素ごとに決まる -1..1 の擬似乱数。 */
    private fun hashNoise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /** 粗い格子の乱数を滑らかにつないだ、にじみ用の低周波ノイズ（-1..1）。 */
    private fun lowFrequencyNoise(x: Int, y: Int, cell: Int, seed: Int): Float {
        val gx = x / cell; val gy = y / cell
        val tx = smoothstep(0f, 1f, (x % cell) / cell.toFloat()); val ty = smoothstep(0f, 1f, (y % cell) / cell.toFloat())
        val a = hashNoise(gx, gy, seed); val b = hashNoise(gx + 1, gy, seed); val c = hashNoise(gx, gy + 1, seed); val d = hashNoise(gx + 1, gy + 1, seed)
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    private fun smoothstep(edge0: Float, edge1: Float, v: Float): Float {
        val t = ((v - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun clamp(v: Int) = v.coerceIn(0, 255)
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
}
