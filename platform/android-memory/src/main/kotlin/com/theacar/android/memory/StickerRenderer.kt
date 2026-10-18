package com.theacar.android.memory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.theacar.memory.STICKER_HEIGHT_MM
import com.theacar.memory.STICKER_PHOTO_FRACTION
import com.theacar.memory.STICKER_WIDTH_MM
import com.theacar.memory.TripMemory
import com.theacar.memory.projectRoute
import com.theacar.memory.stickerText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** 600dpi での 1 コマの大きさ（1/4 L判 = 44.5×63.5 mm）。寸法はこの基準で書き、描画時に拡縮する。 */
const val FRAME_WIDTH_PX = 1051
const val FRAME_HEIGHT_PX = 1500
/** フチなし印刷で欠ける分を見込んだ安全余白 3 mm。 */
const val SAFE_MARGIN_PX = 71

private const val INK = 0xFF1E2A24.toInt()
private const val MIST = 0xFFACD0C0.toInt()
private const val SURFACE = 0xFFFFFFFF.toInt()

/** 1 コマを Canvas に描く。写真面の上に経路と写真の位置、下の白地に日付・地名・メンバー・距離・時間・QR。 */
class StickerRenderer(context: Context, private val memoryUrl: (String) -> String? = { null }) {
    private val assets = context.assets
    private val jostBold = typeface("jost.ttf", 700)
    private val jostSemiBold = typeface("jost.ttf", 600)
    private val jostRegular = typeface("jost.ttf", 400)
    private val notoMedium = typeface("noto_sans_jp.ttf", 500)

    private fun typeface(file: String, weight: Int): Typeface =
        Typeface.Builder(assets, "composeResources/com.theacar.ui.resources/font/$file")
            .setFontVariationSettings("'wght' $weight").build() ?: Typeface.DEFAULT

    fun render(memory: TripMemory, photo: Bitmap?, width: Int = FRAME_WIDTH_PX): Bitmap {
        require(width > 0)
        val height = (width * STICKER_HEIGHT_MM / STICKER_WIDTH_MM).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = width / FRAME_WIDTH_PX.toFloat()
        val photoHeight = (height * STICKER_PHOTO_FRACTION).toFloat()
        canvas.drawColor(SURFACE)
        drawPhotoArea(canvas, memory, photo, width.toFloat(), photoHeight, s)
        drawInfoArea(canvas, memory, width.toFloat(), photoHeight, height.toFloat(), s)
        return bitmap
    }

    private fun drawPhotoArea(canvas: Canvas, memory: TripMemory, photo: Bitmap?, width: Float, photoHeight: Float, s: Float) {
        val area = RectF(0f, 0f, width, photoHeight)
        if (photo == null) {
            canvas.drawRect(area, Paint().apply { color = MIST })
        } else {
            // 写真面いっぱいに中央で切り抜く
            val scale = max(width / photo.width, photoHeight / photo.height)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((width - photo.width * scale) / 2, (photoHeight - photo.height * scale) / 2)
            }
            canvas.save(); canvas.clipRect(area); canvas.drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG)); canvas.restore()
        }
        val projection = projectRoute(memory.geometry, memory.photoLocation, (width / photoHeight).toDouble())
        val lineWidth = 22f * s
        if (projection.route.isNotEmpty()) {
            val path = Path()
            projection.route.forEachIndexed { i, p ->
                val x = (p.x * width).toFloat(); val y = (p.y * photoHeight).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SURFACE; style = Paint.Style.STROKE; strokeWidth = lineWidth
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            if (projection.route.size == 1) canvas.drawPoint((projection.route[0].x * width).toFloat(), (projection.route[0].y * photoHeight).toFloat(), stroke)
            else canvas.drawPath(path, stroke)
        }
        val spot = projection.photoSpot ?: return
        val cx = (spot.x * width).toFloat(); val cy = (spot.y * photoHeight).toFloat()
        val outer = lineWidth * 1.1f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE; style = Paint.Style.FILL }
        canvas.drawPath(star(cx, cy, outer, outer * 0.45f), fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE; typeface = jostBold; textSize = 34f * s; textAlign = Paint.Align.RIGHT }
        val lineHeight = 36f * s
        val labelWidth = max(text.measureText("PHOTO"), text.measureText("SPOT"))
        val safe = SAFE_MARGIN_PX * s
        // 星の左下に置き、はみ出すなら右側か内側に寄せる
        var right = cx - outer - 14f * s
        if (right - labelWidth < safe) right = (cx + outer + 14f * s + labelWidth).coerceAtMost(width - safe)
        val top = (cy + 6f * s).coerceIn(safe, photoHeight - safe - 2 * lineHeight)
        drawTextTop(canvas, "PHOTO", right, top, text)
        drawTextTop(canvas, "SPOT", right, top + lineHeight, text)
    }

    private fun drawInfoArea(canvas: Canvas, memory: TripMemory, width: Float, top: Float, bottom: Float, s: Float) {
        val text = memory.stickerText()
        val left = SAFE_MARGIN_PX * s
        val right = width - SAFE_MARGIN_PX * s
        val infoHeight = bottom - top
        fun paint(face: Typeface, size: Float, align: Paint.Align = Paint.Align.LEFT, color: Int = INK) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = face; textSize = size * s; textAlign = align }

        // 日付 2 行と罫線、地名
        val date = paint(jostSemiBold, 46f)
        drawTextTop(canvas, text.year, left, top + 47f * s, date)
        drawTextTop(canvas, text.monthDay, left, top + 99f * s, date)
        canvas.drawLine(left, top + 163f * s, left + 400f * s, top + 163f * s, Paint().apply { color = INK; strokeWidth = 2f * s })
        val at = paint(jostRegular, 38f)
        drawTextTop(canvas, "@", left, top + 183f * s, at)
        val place = paint(notoMedium, 38f)
        drawTextTop(canvas, text.place, left + at.measureText("@") + 4f * s, top + 183f * s, place)

        // メンバー（右寄せ）
        drawTextTop(canvas, "MEMBERS", right, top + 47f * s, paint(jostBold, 38f, Paint.Align.RIGHT))
        val member = paint(jostRegular, 26f, Paint.Align.RIGHT)
        text.members.forEachIndexed { i, name -> drawTextTop(canvas, name, right, top + (91f + 29f * i) * s, member) }

        // 距離と時間。大きい数字と小さい単位
        val label = paint(jostRegular, 26f).apply { letterSpacing = 0.08f }
        val number = paint(jostBold, 110f)
        val unit = paint(jostBold, 40f)
        val baseline = bottom - SAFE_MARGIN_PX * s
        drawTextTop(canvas, "DISTANCE", left, top + 325f * s, label)
        var x = left
        canvas.drawText(text.distanceKm, x, baseline, number); x += number.measureText(text.distanceKm) + 10f * s
        canvas.drawText("km", x, baseline, unit); x += unit.measureText("km") + 60f * s
        drawTextTop(canvas, "TIME", x, top + 325f * s, label)
        canvas.drawText(text.hours, x, baseline, number); x += number.measureText(text.hours) + 10f * s
        canvas.drawText("h", x, baseline, unit); x += unit.measureText("h") + 16f * s
        canvas.drawText(text.minutes, x, baseline, number); x += number.measureText(text.minutes) + 10f * s
        canvas.drawText("min", x, baseline, unit)

        // QR（情報面の高さの約 30% 角、右下）
        val qrSize = (infoHeight * 0.30f).roundToInt()
        val url = memoryUrl(memory.id) ?: return
        val qr = qrBitmap(url, qrSize, INK, SURFACE)
        canvas.drawBitmap(qr, right - qrSize, baseline - qrSize, null)
        qr.recycle()
    }

    /** 文字の上端を [top] に合わせて描く。 */
    private fun drawTextTop(canvas: Canvas, text: String, x: Float, top: Float, paint: Paint) {
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
    }

    private fun star(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / 5
            val x = cx + (r * cos(angle)).toFloat(); val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
