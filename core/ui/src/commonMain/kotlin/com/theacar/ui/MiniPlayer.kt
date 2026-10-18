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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
