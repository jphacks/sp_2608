package com.theacar.android.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theacar.music.*

@Composable
internal fun PlaylistInputPanel(
    state: PlaylistInputState,
    enabled: Boolean,
    onImport: (String, String) -> Unit,
    onCancel: () -> Unit,
    onRemove: (String, String) -> Unit,
    onMode: (RecommendationMode) -> Unit,
    onReconnect: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val editable = enabled && !state.importing
    MusicPanel {
        Text("みんなのプレイリスト", style = MaterialTheme.typography.titleLarge)
        Text("ログイン中のSpotifyアカウントが所有・共同編集するリストを取り込めます。同乗者のリストは、そのアカウントを共同編集者に追加してください。")
        OutlinedTextField(name, { name = it }, label = { Text("この好みを使う人の表示名") },
            singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("SpotifyプレイリストのURL") },
            singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onImport(name, url) }, enabled = editable && name.isNotBlank() && spotifyPlaylistId(url) != null) {
            Text("取り込む")
        }
        if (state.importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("曲を取得し、推薦に使う準備をしています…")
            TextButton(onClick = onCancel) { Text("取込を中止") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onReconnect, enabled = editable) { Text("読取権限を追加してSpotifyに再ログイン") }
        state.lists.forEach { list ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("${list.member.name} · ${list.tracks.size}曲")
                    TextButton(onClick = { onOpenUrl("https://open.spotify.com/playlist/${list.id}") }) { Text("Spotifyでリストを見る") }
                    if (list.skipped > 0) Text("利用できない曲 ${list.skipped}件を除外", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onRemove(list.id, list.member.id) }, enabled = editable) { Text("削除") }
            }
        }
        Text("重複を除いた候補：${state.pool.size}曲")
        listOf(RecommendationMode.BLEND to "好み＋新しい曲との出会い", RecommendationMode.PLAYLIST to "プレイリスト内だけ").forEach { (mode, label) ->
            FilterChip(selected = state.mode == mode, onClick = { onMode(mode) }, enabled = editable, label = { Text(label) })
        }
        Text(if (state.mode == RecommendationMode.PLAYLIST) "取り込んだリスト内から、景観と車内の様子に合う曲を選びます。候補が尽きてもリスト外には切り替えません。"
            else "リストの曲と同じアーティストを優先し、TheaCarのカタログの曲も候補にします。")
        Text("表示名・曲名・アーティスト・曲IDを景観BGMサーバーへ送ります。未登録の曲は特徴の推定に外部AIを使います。取込結果はアプリ終了まで保持します。変更は景観BGMを終了してから行ってください。",
            style = MaterialTheme.typography.bodySmall)
    }
}
