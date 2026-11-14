package com.theacar.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 今の曲が何をきっかけに選ばれたか。レコードのラベルの色と絵柄、バーの地色に出す。
 * 色は景色の分類の色（[Sea] など）に揃える。
 */
enum class MiniPlayerMood(val color: Color, val description: String?) {
    SEA(Sea, "海沿いの景色に合わせた曲"),
    FOREST(Forest, "森や山あいの景色に合わせた曲"),
    WATER(Lake, "湖や川の景色に合わせた曲"),
    NATURE(Meadow, "野山や田畑の景色に合わせた曲"),
    TOWN(Sunglow, "街並みに合わせた曲"),
    REQUEST(FadedOrange, "同乗者のリクエスト曲"),
    PLAIN(Navajo, null),
    ;

    companion object {
        /** bgm-api の景観カテゴリから。分からない景色は [PLAIN]。 */
        fun ofScene(scene: String?): MiniPlayerMood = when (scene) {
            "sea" -> SEA
            "forest", "valley" -> FOREST
            "lake", "river", "inland_water" -> WATER
            "open_nature", "farmland" -> NATURE
            "urban", "townscape" -> TOWN
            else -> PLAIN
        }
    }
}

/**
 * タブバー上のミニプレーヤー。バー本体のタップで音楽画面、右のボタンで再生/一時停止。
 *
 * 左のレコードは再生中だけ回り、ラベルの色と絵柄で [mood]（曲を選んだきっかけ）を表す。
 * [subtitle] には「なぜこの曲か」や車内の様子など今の状況を1行で出す。
 * [actionLabel] は直し方や「BGMに戻す」など、その場でできる操作が1つあるときだけ出す。
 * 収まらない文字は、運転中でも読めるようゆっくり横に流す。
 */
@Composable
fun MiniPlayer(
    title: String,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    showIndicator: Boolean = false,
    actionReplacesPlay: Boolean = false,
    progress: MiniPlayerProgress? = null,
    mood: MiniPlayerMood = MiniPlayerMood.PLAIN,
    clock: () -> Long = { 0L },
) {
    // 曲が変わったときに色がぱっと切り替わらないよう、ゆっくり移す
    val accent by animateColorAsState(mood.color, tween(900), label = "accent")
    val base by animateColorAsState(if (mood == MiniPlayerMood.PLAIN) Spruce else lerp(Spruce, mood.color, .11f), tween(900), label = "base")
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpinningRecord(spinning = isPlaying && showIndicator, label = accent, mood = mood, playing = isPlaying,
                modifier = Modifier.size(58.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Cream, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.weight(1f).slowMarquee())
                    // 曲名の出どころとして、曲名の横に Spotify のアイコンを置く（間はアイコンの高さの半分）
                    Image(SpotifyIconWhite, contentDescription = "Spotify", modifier = Modifier.padding(start = 11.dp).size(21.dp))
                }
                subtitle?.let {
                    Text(it, color = Cream.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, modifier = Modifier.slowMarquee())
                }
                progress?.let { SongProgress(it, accent, clock) }
            }
            if (actionReplacesPlay && actionLabel != null && onAction != null) {
                // 流せる曲がないときは、再生ボタンの場所を操作のボタンにする
                Surface(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp), color = Navajo, shape = CircleShape) {
                    Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text(actionLabel, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                return@Row
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(actionLabel, color = Navajo, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Surface(
                onClick = onToggle,
                modifier = Modifier.size(46.dp).semantics { contentDescription = if (isPlaying) "一時停止" else "再生" },
                color = Navajo,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Ink)
                }
            }
        }
    }
    // 地色は文字色と同じ Spruce に、きっかけの色を少しだけ混ぜる。上端にはタブバーと同じ細い線を引く
    val background = Modifier.drawBehind {
        drawRect(base)
        drawRect(accent, size = Size(size.width, 2.dp.toPx()))
    }
    if (onOpen != null) Surface(onClick = onOpen, modifier = modifier, color = Color.Transparent) { Box(background) { row() } }
    else Surface(modifier = modifier, color = Color.Transparent) { Box(background) { row() } }
}

/**
 * 曲の再生位置。見せるだけで、つまんで動かすことはできない（運転中に細かい操作をさせないため）。
 * [positionMillis] は [anchorMillis]（[MiniPlayer] の clock と同じ時計）の時点の位置。
 * Spotify への問い合わせは数秒おきなので、その間は経過時間で進めて描く。
 */
data class MiniPlayerProgress(
    val positionMillis: Long,
    val durationMillis: Long,
    val anchorMillis: Long,
    val playing: Boolean,
) {
    fun positionAt(nowMillis: Long): Long {
        val moved = if (playing) (nowMillis - anchorMillis).coerceAtLeast(0) else 0
        return (positionMillis + moved).coerceIn(0, durationMillis)
    }
}

/** 「m:ss」。1時間を超える曲でも分で数える。 */
fun formatSongTime(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) / 1_000).toInt()
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** 道に見立てた細い線の上を、車が曲の進み具合に合わせて走る。走った道はきっかけの色で塗る。 */
@Composable
private fun SongProgress(progress: MiniPlayerProgress, accent: Color, clock: () -> Long) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(progress) {
        now = clock()
        while (progress.playing) {
            delay(500)
            now = clock()
        }
    }
    val position = progress.positionAt(now)
    val fraction = if (progress.durationMillis > 0) position.toFloat() / progress.durationMillis else 0f
    val label = "${formatSongTime(position)} / ${formatSongTime(progress.durationMillis)}"
    Row(Modifier.fillMaxWidth().padding(top = 2.dp).semantics { contentDescription = "再生位置 $label" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
            val car = 18.dp
            Box(Modifier.fillMaxWidth().height(3.dp).background(Cream.copy(alpha = .2f), RoundedCornerShape(2.dp)))
            Box(Modifier.width(maxWidth * fraction).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
            Icon(Icons.Rounded.DirectionsCar, null, tint = Navajo,
                modifier = Modifier.size(car).offset(x = (maxWidth - car) * fraction))
        }
        Text(label, style = MaterialTheme.jost.label.copy(color = Cream.copy(alpha = .72f), fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/** 収まらない1行だけを、先頭で間を置いてからゆっくり流す。収まる文字は動かさない。 */
private fun Modifier.slowMarquee() = basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000,
    repeatDelayMillis = 3_000, spacing = MarqueeSpacing(48.dp), velocity = 24.dp)

// 盤の黒は真っ黒にせず、地色の Spruce に寄せる
private val Vinyl = Color(0xFF16191C)
// 運転中に目を引かないよう、本物の 33⅓ 回転（1.8秒で1周）よりゆっくり回す
private const val TURN_MILLIS = 6_000

/**
 * 回るレコード。溝は同心円なので回っても見た目は変わらず、回っていることはラベルの絵柄で分かる。
 * 照り返しは光の向きで決まるので、盤と一緒には回さない。止めるときは、すっと止めずに少し惰性で回す。
 */
@Composable
private fun SpinningRecord(spinning: Boolean, label: Color, mood: MiniPlayerMood, playing: Boolean, modifier: Modifier) {
    val angle = remember { Animatable(0f) }
    var turned by remember { mutableStateOf(false) }
    LaunchedEffect(spinning) {
        if (spinning) {
            turned = true
            while (true) {
                angle.animateTo(angle.value + 360f, tween(TURN_MILLIS, easing = LinearEasing))
                angle.snapTo(angle.value % 360f)
            }
        } else if (turned) {
            angle.animateTo(angle.value + 24f, tween(900, easing = LinearOutSlowInEasing))
        }
    }
    val description = listOfNotNull(mood.description, if (playing) "再生中" else "一時停止中").joinToString("・")
    Canvas(modifier.semantics { contentDescription = description }) {
        val r = size.minDimension / 2
        val labelR = r * .42f
        drawCircle(Vinyl, r)
        drawGrooves(r, labelR, label)
        drawCircle(Color.White.copy(alpha = .16f), r - .5.dp.toPx(), style = Stroke(1.dp.toPx()))
        rotate(angle.value) {
            drawCircle(label, labelR)
            drawCircle(Ink.copy(alpha = .35f), labelR * .88f, style = Stroke(.6.dp.toPx()))
            drawMotif(mood, labelR)
        }
        drawCircle(Vinyl, r * .045f)
        drawSheen(r, labelR)
    }
}

/** 細い溝。ところどころにラベルの色をうっすら差して、盤の照りに色を持たせる。 */
private fun DrawScope.drawGrooves(r: Float, labelR: Float, tint: Color) {
    val step = 1.4.dp.toPx()
    val stroke = Stroke(.5.dp.toPx())
    var radius = r - 2.dp.toPx()
    var i = 0
    while (radius > labelR + 2.dp.toPx()) {
        val color = when (i % 6) {
            1 -> tint.copy(alpha = .26f)
            4 -> tint.copy(alpha = .14f)
            else -> Color.White.copy(alpha = .05f)
        }
        drawCircle(color, radius, style = stroke)
        radius -= step
        i++
    }
}

/** 左上と右下から差す、斜めの照り返し。ラベルの紙には照らない。 */
private fun DrawScope.drawSheen(r: Float, labelR: Float) {
    val clear = Color.Transparent
    val shine = Color.White.copy(alpha = .13f)
    val brush = Brush.sweepGradient(
        0f to clear, .06f to shine, .13f to clear, .5f to clear, .56f to shine, .63f to clear, 1f to clear, center = center)
    val width = r - labelR
    rotate(-50f) { drawCircle(brush, labelR + width / 2, style = Stroke(width)) }
}

/** ラベルに刷った小さな絵柄。中心の穴は避け、下半分に描く。 */
private fun DrawScope.drawMotif(mood: MiniPlayerMood, l: Float) {
    val ink = Ink.copy(alpha = .82f)
    val line = Stroke(1.1.dp.toPx(), cap = StrokeCap.Round)
    val c = center
    // ラベルの円の中で、高さ y（中心から）のところに描ける半分の幅
    fun half(y: Float) = sqrt((l * l - y * y).coerceAtLeast(0f)) * .82f
    when (mood) {
        MiniPlayerMood.SEA -> {
            // 波を2本と、上に小さな太陽
            listOf(.32f, .58f).forEach { k ->
                val y = l * k
                val w = half(y)
                val path = Path()
                val steps = 24
                for (s in 0..steps) {
                    val x = -w + 2 * w * s / steps
                    val dy = sin((x / w) * 2 * PI.toFloat()) * l * .07f
                    if (s == 0) path.moveTo(c.x + x, c.y + y + dy) else path.lineTo(c.x + x, c.y + y + dy)
                }
                drawPath(path, ink, style = line)
            }
            drawCircle(ink, l * .13f, Offset(c.x, c.y - l * .5f))
        }
        MiniPlayerMood.FOREST -> {
            // 高さの違う針葉樹を3本
            listOf(-.42f to .44f, 0f to .58f, .42f to .4f).forEach { (dx, h) ->
                val baseY = c.y + l * .62f
                val x = c.x + l * dx
                val w = l * .2f
                val path = Path().apply {
                    moveTo(x, baseY - l * h - l * .08f)
                    lineTo(x + w, baseY - l * .08f)
                    lineTo(x - w, baseY - l * .08f)
                    close()
                }
                drawPath(path, ink)
                drawLine(ink, Offset(x, baseY - l * .08f), Offset(x, baseY), strokeWidth = 1.dp.toPx())
            }
        }
        MiniPlayerMood.WATER -> {
            // 水面に広がる波紋
            listOf(.22f, .42f, .62f).forEach { k ->
                val w = l * k * 1.3f
                val h = l * k * .45f
                drawOval(ink.copy(alpha = ink.alpha * (1.1f - k)), Offset(c.x - w, c.y + l * .45f - h), Size(w * 2, h * 2), style = line)
            }
        }
        MiniPlayerMood.NATURE -> {
            // 重なる2つの丘
            val y = c.y + l * .62f
            val back = Path().apply {
                moveTo(c.x - half(l * .62f), y)
                quadraticTo(c.x - l * .2f, c.y + l * .02f, c.x + l * .35f, y)
            }
            val front = Path().apply {
                moveTo(c.x - l * .15f, y)
                quadraticTo(c.x + l * .35f, c.y + l * .14f, c.x + half(l * .62f), y)
            }
            drawPath(back, ink, style = line)
            drawPath(front, ink, style = line)
            drawLine(ink, Offset(c.x - half(l * .62f), y), Offset(c.x + half(l * .62f), y), strokeWidth = line.width, cap = StrokeCap.Round)
        }
        MiniPlayerMood.TOWN -> {
            // 高さの違うビルの並び
            val baseY = c.y + l * .66f
            val heights = listOf(.3f, .5f, .38f, .62f, .34f)
            val w = l * .2f
            val gap = l * .04f
            val total = heights.size * w + (heights.size - 1) * gap
            heights.forEachIndexed { i, h ->
                val x = c.x - total / 2 + i * (w + gap)
                drawRect(ink, Offset(x, baseY - l * h), Size(w, l * h))
            }
        }
        MiniPlayerMood.REQUEST -> {
            // 手渡しの小さなハート
            val s = l * .3f
            val top = c.y + l * .28f
            val path = Path().apply {
                moveTo(c.x, top + s * 1.1f)
                cubicTo(c.x - s * 1.4f, top + s * .2f, c.x - s * .7f, top - s * .7f, c.x, top)
                cubicTo(c.x + s * .7f, top - s * .7f, c.x + s * 1.4f, top + s * .2f, c.x, top + s * 1.1f)
                close()
            }
            drawPath(path, ink)
        }
        MiniPlayerMood.PLAIN -> {
            // 曲名を刷ったような短い線を、穴の上下に
            listOf(-.5f to .5f, -.32f to .34f, .36f to .44f, .54f to .26f).forEach { (k, w) ->
                val y = c.y + l * k
                drawLine(ink, Offset(c.x - l * w, y), Offset(c.x + l * w, y), strokeWidth = .9.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
