package com.theacar.android.music

import com.theacar.music.CabinListening
import com.theacar.music.CabinStatus
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theacar.music.BgmMusicState
import com.theacar.music.CabinState
import com.theacar.music.MusicMember


/**
 * 景観と車内の雰囲気から1曲ずつ推薦して流す。
 * 車内の雰囲気はマイクから端末内で判定する。
 * プレイリストは「みんなのプレイリスト」（[PlaylistInputPanel]）で取り込み、Runtimeが音楽画面・ナビ共通で適用する。
 */
@Composable
internal fun BgmRecommendationPanel(
    state: BgmMusicState,
    cabin: CabinStatus,
    memberNames: String,
    configured: Boolean,
    canPlay: Boolean,
    onStart: (List<MusicMember>) -> Unit,
    onStop: () -> Unit,
    playlistMembers: List<MusicMember> = emptyList(),
) {
    val recommending = state.enabled && !state.requestsOnly
    var names by rememberSaveable { mutableStateOf(memberNames) }
    val members = playlistMembers.ifEmpty { parseMembers(names) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("景色と車内の空気で、次の1曲を。", style = MaterialTheme.typography.titleLarge)
            if (!configured) {
                Text("景観BGMは経路サーバーの /bgm/ を使います。local.properties の VALHALLA_URL を設定して再ビルドしてください。")
                return@Column
            }
            Text("案内中のルートの景観と車内の様子から、曲の終わりごとに次の曲を選びます。" +
                "ナビ画面の「案内を開始」の横からも、案内に合わせて自動で始められます。", style = MaterialTheme.typography.bodyMedium)
            if (playlistMembers.isEmpty()) {
                OutlinedTextField(names, { names = it }, label = { Text("同乗者（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.enabled)
                Text("みんなの好みを選曲に使うときは、「みんなのプレイリスト」でリストを取り込んでください。", style = MaterialTheme.typography.bodySmall)
            } else Text("選曲に使うメンバー：" + playlistMembers.joinToString("、") { it.name })
            Text(if (state.sceneryAvailable) "案内中のルートの景観を使っています。"
                else "ナビの案内を開始すると、これから通る景色を選曲に使います。", style = MaterialTheme.typography.bodySmall)
            state.playing?.let {
                Text("${it.title} · ${it.artist}", style = MaterialTheme.typography.titleMedium)
                state.message?.let { message -> Text(message) }
            }
            if (recommending) Text(cabin.label(), style = MaterialTheme.typography.bodySmall)
            state.coverage?.let { Text("景観の評価済み割合 ${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall) }
            state.revealEtaSeconds?.let { Text("約${it}秒後に海が見え始める見込みです。", style = MaterialTheme.typography.bodySmall) }
            state.recommendedVolume?.let {
                Text("推奨音量 ${(it * 100).toInt()}%（再生音量へは未反映）", style = MaterialTheme.typography.bodySmall)
            }
            if (state.requesting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.missing.isNotEmpty()) {
                Text("未注釈の曲が${state.missing.size}件あります。", style = MaterialTheme.typography.bodySmall)
            }
            state.failure?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { onStart(members) }, enabled = canPlay && members.isNotEmpty() && !recommending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (recommending) "推薦で再生中" else "景観BGMを始める")
            }
            if (recommending) TextButton(onClick = onStop) { Text("景観BGMを終了") }
            Text("曲名・距離・到着予測時刻と、車内の雰囲気（4段階のどれか）をサーバーへ送ります。" +
                "座標・道路名・物理道路ID・音声は送りません。マイクの音は端末内で会話の量の判定にだけ使い、保存しません。" +
                "画面を閉じてもナビの案内中も続きます。止めるときはこの画面で終了してください。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 休息中でも「寝ている」とは出さない（bgm-api の取り決め）。 */
private fun CabinStatus.label() = when (listening) {
    CabinListening.PERMISSION_REQUIRED -> "車内の雰囲気：マイクが許可されていないため「ふつう」として選曲しています。"
    CabinListening.UNAVAILABLE -> "車内の雰囲気：マイクを使えないため「ふつう」として選曲しています。"
    CabinListening.OFF -> "車内の雰囲気：判定していません（「ふつう」として選曲）。"
    CabinListening.ACTIVE -> "車内の雰囲気：" + when (state) {
        CabinState.LIVELY -> "会話が弾んでいます。ボーカル控えめの曲にします。"
        CabinState.QUIET -> "静かです。みんなが知っている明るめの曲にします。"
        CabinState.RESTING -> "落ち着いた時間です。穏やかな曲にします。"
        CabinState.NORMAL, null -> "ふつう"
    }
}
