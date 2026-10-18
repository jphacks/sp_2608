package com.theacar.android.music

import com.theacar.music.CabinListening
import com.theacar.music.CabinStatus
import com.theacar.music.NowPlaying
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theacar.music.CabinState
import com.theacar.music.MusicFailure

/**
 * ルートのプレビューで「案内を開始」の直前に出す1行。
 * 開始前に、流すかどうかと、流せない理由・直し方だけを示す。
 */
@Composable
fun DriveBgmPreview(link: DriveBgmLink) {
    val state by link.state.collectAsState()
    if (state.readiness == DriveBgmReadiness.UNAVAILABLE) return
    val context = LocalContext.current
    val running = state.bgm.enabled
    val subtitle = when {
        running -> "案内を開始すると、この先の景色に合わせて選び直します"
        state.readiness == DriveBgmReadiness.LOGIN_REQUIRED -> "Spotifyと連携すると、景色と車内の様子に合わせて曲を選びます"
        state.readiness == DriveBgmReadiness.PLAYLIST_REQUIRED -> "音楽画面でプレイリストを取り込んでください"
        state.readiness == DriveBgmReadiness.PREPARING_PLAYLIST -> "プレイリストの曲を準備しています"
        !state.wanted -> "オフ · 案内中に後から流すこともできます"
        state.checking && state.deviceName == null -> "再生先を確認しています…"
        state.readiness == DriveBgmReadiness.NO_DEVICE && !state.spotifyInstalled -> "Spotifyアプリを入れると、このスマホで再生できます"
        state.readiness == DriveBgmReadiness.NO_DEVICE -> "Spotifyアプリを開くと再生先が見つかります"
        state.deviceName == PhoneAwareMusicService.PHONE_DEVICE_NAME -> "このスマホで再生 · Spotifyアプリは開かなくても自動で起動"
        else -> "${state.deviceName}で再生 · 景色と車内の会話で選曲"
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("♪ 景観BGM", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            running -> Unit
            state.readiness == DriveBgmReadiness.LOGIN_REQUIRED ->
                TextButton(onClick = { openMusicScreen(context) }) { Text("連携する") }
            state.readiness == DriveBgmReadiness.PLAYLIST_REQUIRED ->
                TextButton(onClick = { openMusicScreen(context) }) { Text("プレイリスト") }
            state.readiness == DriveBgmReadiness.PREPARING_PLAYLIST -> Unit
            else -> {
                if (state.wanted && state.readiness == DriveBgmReadiness.NO_DEVICE && !state.checking) {
                    if (state.spotifyInstalled) TextButton(onClick = { openSpotify(context) }) { Text("Spotifyを開く") }
                    else TextButton(onClick = { installSpotify(context) }) { Text("入手") }
                }
                Switch(checked = state.wanted, onCheckedChange = link::setWanted)
            }
        }
    }
}

/** ミニプレーヤーのボタンでできる操作。 */
enum class DriveBgmAction { CONNECT, RETRY, OPEN_SPOTIFY, INSTALL_SPOTIFY, START_BGM }

/**
 * ミニプレーヤーに出す1曲ぶんの表示。運転中に一目で分かるよう、
 * 1行目は実際に流れている曲、2行目は「なぜこの曲か」か今の状況、操作はその場で必要なものを1つだけにする。
 */
data class DriveBgmLine(
    val title: String,
    val subtitle: String?,
    val playing: Boolean,
    val indicator: Boolean,
    val actionLabel: String? = null,
    val action: DriveBgmAction? = null,
)

/**
 * 景観BGMの状態と実際の再生から、ミニプレーヤーの表示を決める。
 * [guiding] は案内中か。案内中でなければ景観BGMの操作は出さない（景観BGMは案内に合わせて動くため）。
 */
fun driveBgmLine(state: DriveBgmState, now: NowPlaying?, connected: Boolean, guiding: Boolean): DriveBgmLine {
    if (!connected || state.readiness == DriveBgmReadiness.LOGIN_REQUIRED) {
        return DriveBgmLine("Spotifyと連携して音楽を流す", null, false, false, "連携", DriveBgmAction.CONNECT)
    }
    if (guiding && state.readiness == DriveBgmReadiness.PLAYLIST_REQUIRED) {
        return DriveBgmLine("プレイリストを選んでください", "音楽画面で取り込めます", false, false, "選ぶ", DriveBgmAction.CONNECT)
    }
    if (guiding && state.readiness == DriveBgmReadiness.PREPARING_PLAYLIST) {
        return DriveBgmLine("プレイリストを準備しています", "取込完了後に「流す」を押してください", false, false)
    }
    val bgm = state.bgm
    val failure = bgm.failure
    val track = now?.playback?.track
    val playing = now?.playback?.playing == true
    val trackTitle = track?.let { "${it.title} · ${it.artist}" }
    val useBgm = state.readiness != DriveBgmReadiness.UNAVAILABLE
    if (useBgm && bgm.enabled) {
        val recommended = bgm.playing
        // 選曲の一言は推薦した曲について書かれているので、その曲（URI が同じ）が流れているときだけ出す
        val message = bgm.message?.takeIf { track == null || track.uri == bgm.playingUri }
        val request = bgm.playingRequest?.takeIf { track != null && it.uri == track.uri }
        val subtitle = when {
            failure != null -> "${failure.shortMessage()} · 次の曲の切れ目で再試行します"
            bgm.requestsOnly && trackTitle == null -> "QRからのリクエストを受け付けています"
            bgm.requestsOnly -> if (bgm.requests.isEmpty()) "リクエスト再生中" else "このあと予約${bgm.requests.size}曲"
            trackTitle == null && recommended == null -> state.cabin.shortLabel() ?: "景色と車内の様子から選んでいます"
            !playing && track != null -> "一時停止中 · 再生すると景観BGMを続けます"
            request != null -> (request.requester?.let { "${it}さんのリクエスト" } ?: "リクエスト曲") + " · " +
                (if (bgm.requests.isEmpty()) "終わったら景観BGMに戻ります" else "このあと予約${bgm.requests.size}曲")
            // 利用者が「次に再生」に入れた曲。曲名ではなく再生先の URI で、景観BGMの曲（今の曲・次の曲）と区別する
            track != null && bgm.playingUri != null && track.uri != bgm.playingUri && track.uri != bgm.upNextUri ->
                "リクエスト曲 · 終わったら景観BGMに戻ります"
            else -> message ?: state.cabin.shortLabel() ?: "景色と車内の様子で選曲中"
        }
        val title = trackTitle ?: recommended?.let { "${it.title} · ${it.artist}" } ?: "景色に合う曲を選んでいます…"
        return DriveBgmLine(title, subtitle, playing, true)
    }
    // 景観BGMを使えなくなった理由は、直し方と一緒に出す
    val problem = state.phoneProblem.takeIf { failure != null }
    if (useBgm && guiding && failure != null) {
        return when {
            problem == PhonePlayerProblem.NOT_INSTALLED ->
                DriveBgmLine(problem.shortMessage(), "入れた後に「再試行」を押してください", false, false, "入手", DriveBgmAction.INSTALL_SPOTIFY)
            problem == PhonePlayerProblem.NOT_LOGGED_IN ->
                DriveBgmLine(problem.shortMessage(), "Spotifyアプリでログインしてから再試行してください", false, false, "Spotify", DriveBgmAction.OPEN_SPOTIFY)
            problem == PhonePlayerProblem.NOT_AUTHORIZED ->
                DriveBgmLine(problem.shortMessage(), "「再試行」でSpotifyアプリの許可画面を開きます", false, false, "再試行", DriveBgmAction.RETRY)
            else -> DriveBgmLine(problem?.shortMessage() ?: failure.shortMessage(),
                if (failure == MusicFailure.NO_DEVICE) "Spotifyアプリを開くと見つかることがあります" else null,
                false, false, "再試行", DriveBgmAction.RETRY)
        }
    }
    if (trackTitle != null) {
        // 利用者が自分で選んだ曲。案内中なら景観BGMに戻せる
        val subtitle = when {
            !guiding || !useBgm -> if (playing) null else "一時停止中"
            playing -> "選んだ曲を再生中 · BGMに戻すと景色に合う曲に切り替えます"
            else -> "一時停止中 · BGMに戻すと景色に合う曲を流します"
        }
        return if (guiding && useBgm) DriveBgmLine(trackTitle, subtitle, playing, true, "BGMに戻す", DriveBgmAction.START_BGM)
        else DriveBgmLine(trackTitle, subtitle, playing, true)
    }
    return if (guiding && useBgm) DriveBgmLine("景観BGM オフ", "流すと、景色と車内の様子に合わせて選びます", false, false, "流す", DriveBgmAction.START_BGM)
    else DriveBgmLine("再生していません", null, false, false)
}

/** ミニプレーヤーのボタンを押したとき。 */
fun DriveBgmLink.perform(action: DriveBgmAction, context: Context) = when (action) {
    DriveBgmAction.CONNECT -> openMusicScreen(context)
    DriveBgmAction.RETRY, DriveBgmAction.START_BGM -> begin()
    DriveBgmAction.OPEN_SPOTIFY -> openSpotify(context)
    DriveBgmAction.INSTALL_SPOTIFY -> installSpotify(context)
}

private fun PhonePlayerProblem.shortMessage() = when (this) {
    PhonePlayerProblem.NOT_INSTALLED -> "Spotifyアプリが入っていません"
    PhonePlayerProblem.NOT_LOGGED_IN -> "Spotifyアプリにログインしていません"
    PhonePlayerProblem.NOT_AUTHORIZED -> "Spotifyアプリでの許可が必要です"
    PhonePlayerProblem.OFFLINE -> "Spotifyアプリがオフラインです"
    PhonePlayerProblem.UNAVAILABLE -> "Spotifyアプリに接続できません"
}

private fun MusicFailure.shortMessage() = when (this) {
    MusicFailure.NO_DEVICE -> "Spotifyの再生先が見つかりません"
    MusicFailure.AUTHORIZATION -> "Spotifyへの再ログインが必要です"
    MusicFailure.ACCESS_DENIED -> "Spotifyが再生を許可しませんでした"
    MusicFailure.RATE_LIMIT -> "Spotifyの利用上限に達しました"
    MusicFailure.NO_RESULTS -> "流せる曲が見つかりませんでした"
    else -> "曲を選べませんでした"
}

/** 休息中でも「寝ている」とは出さない（bgm-api の取り決め）。 */
private fun CabinStatus.shortLabel(): String? = if (listening != CabinListening.ACTIVE) null else when (state) {
    CabinState.LIVELY -> "車内：会話が弾んでいます"
    CabinState.QUIET -> "車内：静かです"
    CabinState.RESTING -> "車内：落ち着いた時間です"
    CabinState.NORMAL, null -> "車内：ふつう"
}

internal fun openMusicScreen(context: Context) {
    context.startActivity(Intent(context, SpotifyMusicActivity::class.java))
}

internal fun installSpotify(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.spotify.music"))
    try { context.startActivity(market) } catch (_: ActivityNotFoundException) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.spotify.music"))) }
        catch (_: ActivityNotFoundException) { }
    }
}

internal fun openSpotify(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/"))
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
}
