package com.theacar.android.memory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theacar.memory.records.RoadRecordEntry
import com.theacar.memory.records.roadRecord
import com.theacar.ui.Cream
import com.theacar.ui.Ink
import com.theacar.ui.Navajo
import com.theacar.ui.TheaCarTheme
import com.theacar.ui.jost
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 着いたとき（または次にアプリを開いたとき）に、まだ見せていない新しいレコードを1枚ずつ見せる。
 * ジャケットからレコードが滑り出て回り、道の名前・手に入れた日・収録曲が出る。
 * 走行中には開かない（開く側が案内中でないことを確かめる）。
 */
class RecordCelebrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as RoadRecordOwner).roadRecords
        val book = store.book.value
        val records = book.pendingCelebration.mapNotNull { book.entries[it] }.filter { roadRecord(it.id) != null }
        if (records.isEmpty()) { finish(); return }
        setContent {
            TheaCarTheme {
                val scope = rememberCoroutineScope()
                var index by remember { mutableIntStateOf(0) }
                val done = { openCollection: Boolean ->
                    scope.launch {
                        runCatching { store.celebrated(records.map { it.id }) }
                        if (openCollection) startActivity(collectionIntent(this@RecordCelebrationActivity))
                        finish()
                    }
                    Unit
                }
                BackHandler { done(false) }
                Celebration(records[index], index, records.size,
                    onNext = { if (index < records.lastIndex) index++ else done(false) },
                    onCollection = { done(true) })
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, RecordCelebrationActivity::class.java)

        /** RECORD タブを開く（MainActivity の EXTRA_TAB と同じ値。このモジュールは androidApp に依存しない） */
        private fun collectionIntent(context: Context) = Intent().setClassName(context.packageName, "com.theacar.android.MainActivity")
            .putExtra("com.theacar.TAB", "RECORD").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun Celebration(entry: RoadRecordEntry, index: Int, count: Int, onNext: () -> Unit, onCollection: () -> Unit) {
    val info = roadRecord(entry.id) ?: return
    // レコードが袖から右へ滑り出る量（0 → 1）。1枚ごとにやり直す
    val slide = remember(entry.id) { Animatable(0f) }
    var revealed by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(entry.id) {
        slide.animateTo(1f, tween(1_100, delayMillis = 350, easing = FastOutSlowInEasing))
        revealed = true
    }
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f,
        infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            Text(if (count > 1) "NEW RECORD  ${index + 1} / $count" else "NEW RECORD", style = MaterialTheme.jost.label, color = Navajo)
            BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                val disc = 220.dp
                val travel = (maxWidth - disc) / 2
                // レコードは袖の後ろから出てきて、出きったら回り続ける
                recordArt(entry.id)?.let { art ->
                    Image(painterResource(art), contentDescription = "${info.name}のレコード",
                        modifier = Modifier.size(disc).offset(x = -travel * (1 - slide.value) * .6f + travel * slide.value * .25f)
                            .rotate(if (revealed) spin else slide.value * 120f))
                }
                // ジャケット（袖）。レコードが出たら左へ下がって薄くなる
                Surface(Modifier.size(disc).offset(x = -travel * .6f - travel * slide.value * .5f),
                    shape = RoundedCornerShape(6.dp), color = Cream.copy(alpha = 1f - slide.value * .85f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(info.title, style = MaterialTheme.jost.label, color = Ink.copy(alpha = 1f - slide.value), textAlign = TextAlign.Center)
                    }
                }
            }
            AnimatedVisibility(revealed, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 3 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.name, style = MaterialTheme.typography.headlineMedium, color = Cream, fontWeight = FontWeight.Bold)
                    Text("${info.title} · ${info.area}", style = MaterialTheme.jost.label, color = Navajo)
                    Text("${SimpleDateFormat("yyyy.MM.dd", Locale.JAPAN).format(Date(entry.firstUnlockedAtEpochMillis))} に手に入れた",
                        style = MaterialTheme.typography.bodyMedium, color = Cream.copy(alpha = .85f))
                    entry.song?.let { song ->
                        Surface(color = Color.White.copy(alpha = .08f), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("収録曲", style = MaterialTheme.jost.label, color = Navajo)
                                Text(song.title, style = MaterialTheme.typography.titleMedium, color = Cream, textAlign = TextAlign.Center)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Cream.copy(alpha = .7f))
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(revealed, enter = fadeIn(tween(500, delayMillis = 300))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = Navajo, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth()) { Text(if (index < count - 1) "次のレコード" else "閉じる") }
                    TextButton(onClick = onCollection) { Text("コレクションを見る", color = Cream) }
                }
            }
        }
    }
}
