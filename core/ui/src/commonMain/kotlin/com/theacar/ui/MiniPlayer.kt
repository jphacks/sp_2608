package com.theacar.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * タブバー上のミニプレーヤー。バー本体のタップで音楽画面、右のボタンで再生/一時停止。
 *
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
    progress: MiniPlayerProgress? = null,
    clock: () -> Long = { 0L },
) {
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showIndicator) EqualizerBars(isPlaying, Sunglow)
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.slowMarquee())
                subtitle?.let {
                    Text(it, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, modifier = Modifier.slowMarquee())
                }
                progress?.let { SongProgress(it, clock) }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(actionLabel, color = Sunglow, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Surface(
                onClick = onToggle,
                modifier = Modifier.size(44.dp).semantics { contentDescription = if (isPlaying) "一時停止" else "再生" },
                color = Sunglow,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Ink)
                }
            }
        }
    }
    if (onOpen != null) Surface(onClick = onOpen, modifier = modifier, color = Mountains, content = row)
    else Surface(modifier = modifier, color = Mountains, content = row)
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

/** 道に見立てた細い線の上を、車が曲の進み具合に合わせて走る。 */
@Composable
private fun SongProgress(progress: MiniPlayerProgress, clock: () -> Long) {
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
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(2.dp)))
            Box(Modifier.width(maxWidth * fraction).height(3.dp).background(Sunglow, RoundedCornerShape(2.dp)))
            Icon(Icons.Rounded.DirectionsCar, null, tint = Sunglow,
                modifier = Modifier.size(car).offset(x = (maxWidth - car) * fraction))
        }
        Text(label, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** 収まらない1行だけを、先頭で間を置いてからゆっくり流す。収まる文字は動かさない。 */
private fun Modifier.slowMarquee() = basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000,
    repeatDelayMillis = 3_000, spacing = MarqueeSpacing(48.dp), velocity = 24.dp)

/** 再生中だけ動く3本の音量バー。止まっているときは低い位置で静止させる。 */
@Composable
private fun EqualizerBars(playing: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    Row(Modifier.size(width = 18.dp, height = 18.dp).semantics { contentDescription = if (playing) "再生中" else "一時停止中" },
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(420, 560, 360).forEachIndexed { index, duration ->
            val level by transition.animateFloat(.25f, 1f,
                infiniteRepeatable(tween(duration, delayMillis = index * 80), RepeatMode.Reverse), label = "bar$index")
            Box(Modifier.weight(1f).fillMaxHeight(if (playing) level else .25f).background(color, RoundedCornerShape(1.dp)))
        }
    }
}
