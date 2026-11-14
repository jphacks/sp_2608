package com.theacar.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theacar.ui.CardCorner
import com.theacar.ui.Cream
import com.theacar.ui.Ink
import com.theacar.ui.Muted
import com.theacar.ui.jost
import com.theacar.ui.Surface as SurfaceWhite

/**
 * RECORD タブの棚に並べるレコード1枚。名のある道を走り切ると手に入る。
 * [unlockedDate] などは手に入れたレコードだけが持つ。日付の書き方は呼ぶ側で決める。
 */
data class RecordShelfItem(
    val id: String,
    val name: String,
    val title: String,
    val area: String,
    val unlocked: Boolean,
    val unlockedDate: String? = null,
    val timesDriven: Int = 0,
    val songTitle: String? = null,
    val songArtist: String? = null,
    val songUrl: String? = null,
)

/** 棚の見出し（「RECORDS 3 / 15」）と、3列に並べたレコード */
@Composable
internal fun RecordShelf(records: List<RecordShelfItem>, art: @Composable (String) -> Painter?, onOpen: (RecordShelfItem) -> Unit) {
    Surface(color = SurfaceWhite, shape = RoundedCornerShape(CardCorner), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("RECORDS", style = MaterialTheme.jost.label, modifier = Modifier.weight(1f))
                Text("${records.count { it.unlocked }} / ${records.size}", style = MaterialTheme.jost.date)
            }
            if (records.none { it.unlocked }) {
                Text("名のある道を走り切ると、その道のレコードが手に入ります。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            records.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { record -> RecordCell(record, art(record.id), Modifier.weight(1f)) { onOpen(record) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RecordCell(record: RecordShelfItem, painter: Painter?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordDisc(record.unlocked, painter, record.name, Modifier.fillMaxWidth())
        Text(record.name, style = MaterialTheme.typography.labelSmall, color = if (record.unlocked) Ink else Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** レコードの絵。まだ手に入れていないものは、盤の影だけにしてラベルを伏せる */
@Composable
internal fun RecordDisc(unlocked: Boolean, painter: Painter?, name: String, modifier: Modifier) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        if (painter == null) {
            Surface(Modifier.fillMaxWidth().aspectRatio(1f), shape = CircleShape, color = Ink.copy(alpha = .85f)) {}
        } else if (unlocked) {
            Image(painter, contentDescription = "$name のレコード", modifier = Modifier.fillMaxWidth())
        } else {
            Image(painter, contentDescription = "$name のレコード（まだ手に入れていない）",
                modifier = Modifier.fillMaxWidth().alpha(.82f),
                colorFilter = ColorFilter.tint(Ink, BlendMode.SrcIn))
            // ラベルの部分は伏せたまま、中心の穴だけを見せる
            Surface(Modifier.size(10.dp), shape = CircleShape, color = Cream) {}
        }
    }
}

/** レコードを押したときの詳しい表示。手に入れたものは日付と収録曲、まだのものは行き方 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordDetailSheet(record: RecordShelfItem, painter: Painter?, onGoToRoad: ((RecordShelfItem) -> Unit)?, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceWhite) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordDisc(record.unlocked, painter, record.name, Modifier.widthIn(max = 260.dp).fillMaxWidth())
            Text(record.name, style = MaterialTheme.typography.headlineSmall, color = Ink, fontWeight = FontWeight.Bold)
            Text("${record.title} · ${record.area}", style = MaterialTheme.jost.label, color = Muted)
            if (record.unlocked) {
                Text(listOfNotNull(record.unlockedDate?.let { "$it に手に入れた" },
                    record.timesDriven.takeIf { it > 1 }?.let { "${it}回走った" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Ink)
                val title = record.songTitle
                if (title != null) {
                    Surface(color = Cream, shape = RoundedCornerShape(CardCorner),
                        modifier = Modifier.fillMaxWidth().then(
                            record.songUrl?.let { url -> Modifier.clickable { uriHandler.openUri(url) } } ?: Modifier)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("収録曲", style = MaterialTheme.jost.label, color = Muted)
                            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            record.songArtist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
                            if (record.songUrl != null) Text("Spotify で開く", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Text("この道を走ったときは、曲が流れていませんでした。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            } else {
                Text("まだ手に入れていません。この道の大部分を走り切ると、レコードが手に入ります。",
                    style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center)
                if (onGoToRoad != null) {
                    Button(onClick = { onGoToRoad(record) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Cream)) { Text("この道へ行く") }
                }
            }
        }
    }
}
