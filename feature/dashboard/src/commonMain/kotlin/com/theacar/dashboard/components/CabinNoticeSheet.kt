package com.theacar.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theacar.cabin.CabinNotice
import com.theacar.ui.CardCorner
import com.theacar.ui.Ink
import com.theacar.ui.Mist
import com.theacar.ui.Muted
import kotlinx.coroutines.delay

/**
 * 換気したことを画面の下からそっと知らせる。数秒で自分から消える。
 *
 * 運転中に見る画面なので、画面を覆わず視線も奪わない。閉じる操作は任意で、
 * 押さなくても消える。[notice] が null のときは何も出さない。
 */
@Composable
fun CabinNoticeSheet(notice: CabinNotice?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // 消えていく途中も中身が必要なので、最後に出した知らせを覚えておく
    var shown by remember { mutableStateOf<CabinNotice?>(null) }
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        shown = current
        delay(VISIBLE_MS)
        onDismiss()
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        shown?.let { Sheet(it, onDismiss) }
    }
}

@Composable
private fun Sheet(notice: CabinNotice, onDismiss: () -> Unit) {
    Surface(
        color = Mist,
        shape = RoundedCornerShape(topStart = CardCorner, topEnd = CardCorner),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("≋", fontSize = 28.sp, color = Muted)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(notice.spoken, color = Ink, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Text(notice.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("閉じる", color = Muted)
            }
        }
    }
}

/** 読み終えるのに足りて、居座らない長さ。車載画面の Alert と揃えている。 */
private const val VISIBLE_MS = 6_000L
