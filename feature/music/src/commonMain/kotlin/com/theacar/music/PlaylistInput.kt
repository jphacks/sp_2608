package com.theacar.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaylistTrack(val trackId: String, val title: String, val artist: String, val durationMillis: Int?)
data class PlaylistTracks(val tracks: List<PlaylistTrack>, val skipped: Int = 0)
class PlaylistTooLargeException : Exception("playlist exceeds 1000 items")
interface PlaylistSource { suspend fun playlist(id: String): PlaylistTracks }
interface PlaylistAnnotator { suspend fun annotate(tracks: List<PlaylistTrack>): Set<String> }
data class ImportedPlaylist(val id: String, val member: MusicMember, val tracks: List<PlaylistTrack>, val skipped: Int)
data class PlaylistInputState(
    val lists: List<ImportedPlaylist> = emptyList(),
    val mode: RecommendationMode = RecommendationMode.BLEND,
    val importing: Boolean = false,
    val error: String? = null,
) {
    val members get() = lists.map { it.member }.distinctBy { it.id }
    val pool get() = lists.flatMap { list -> list.tracks.map { it.trackId to list.member.id } }
        .groupBy({ it.first }, { it.second }).map { (id, owners) -> PlaylistEntry(id, owners.distinct()) }
}

/** 入力を検証してIDだけを取り出す。入力URLを認証付きで直接リクエストしない。 */
fun spotifyPlaylistId(input: String): String? {
    val value = input.trim()
    val id = when {
        value.startsWith("spotify:playlist:") -> value.removePrefix("spotify:playlist:")
        value.startsWith("https://open.spotify.com/playlist/") -> value.removePrefix("https://open.spotify.com/playlist/").substringBefore('?')
        else -> value
    }
    return id.takeIf { Regex("[A-Za-z0-9]{22}").matches(it) }
}

/** 取込完了時にだけ置き換える。失敗・キャンセルで既存の入力を消さない。端末メモリ内で保持する。 */
class PlaylistInputSession(
    private val source: PlaylistSource,
    private val annotator: PlaylistAnnotator,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(PlaylistInputState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var nextMemberId = 0L

    fun setMode(mode: RecommendationMode) { mutableState.value = state.value.copy(mode = mode) }

    fun import(name: String, input: String) {
        if (state.value.importing) return
        val owner = name.trim()
        val id = spotifyPlaylistId(input)
        if (owner.isEmpty() || owner.length > 40 || id == null) {
            mutableState.value = state.value.copy(error = "40文字以内の表示名とSpotifyのプレイリストURLを入力してください。")
            return
        }
        if (state.value.lists.size >= 20 && state.value.lists.none { it.id == id && it.member.name == owner }) {
            mutableState.value = state.value.copy(error = "プレイリストは20件まで取り込めます。")
            return
        }
        val current = ++generation
        mutableState.value = state.value.copy(importing = true, error = null)
        job = scope.launch {
            try {
                val loaded = source.playlist(id)
                if (loaded.tracks.isEmpty()) throw MusicException(MusicFailure.NO_RESULTS)
                val tracks = loaded.tracks.distinctBy { it.trackId }
                val retained = state.value.lists.filterNot { it.id == id && it.member.name == owner }
                if ((retained.flatMap { it.tracks }.map { it.trackId } + tracks.map { it.trackId }).distinct().size > 5000) {
                    if (generation == current) mutableState.value = state.value.copy(error = "入力できる曲は重複を除いて5,000曲までです。リストを削除してから取り込んでください。")
                    return@launch
                }
                for ((index, batch) in tracks.chunked(50).withIndex()) {
                    if (index > 0) delay(550) // Gatewayの120回/分・burst制限に収める。
                    val annotated = annotator.annotate(batch)
                    if (batch.any { it.trackId !in annotated }) {
                        if (generation == current) mutableState.value = state.value.copy(error = "曲の準備が完了しませんでした。サーバーの注釈用設定を確認し、しばらく待って再度取り込んでください。")
                        return@launch
                    }
                }
                if (generation != current) return@launch
                val member = state.value.members.firstOrNull { it.name == owner } ?: MusicMember("p${nextMemberId++}", owner)
                val list = ImportedPlaylist(id, member, tracks, loaded.skipped)
                mutableState.value = state.value.copy(lists = state.value.lists.filterNot { it.id == id && it.member.id == member.id } + list)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == current) mutableState.value = state.value.copy(error = if (error is PlaylistTooLargeException)
                    "1リストの上限は1,000項目です。小さいリストを選んでください。" else when ((error as? MusicException)?.failure) {
                    MusicFailure.ACCESS_DENIED -> "読取権限がありません。Spotifyに再ログインし、自分が所有または共同編集するプレイリストを選んでください。"
                    MusicFailure.AUTHORIZATION -> "Spotifyに再ログインしてください。"
                    MusicFailure.RATE_LIMIT -> "利用上限に達しました。しばらく待ってから取り込んでください。"
                    MusicFailure.NO_RESULTS -> "推薦に使える曲がありません。ローカル曲・ISRCのない曲などは対象外です。"
                    else -> "プレイリストを取り込めませんでした。URL・通信・景観BGMサーバーの設定を確認してください。"
                })
            } finally {
                if (generation == current) mutableState.value = state.value.copy(importing = false)
            }
        }
    }

    fun remove(id: String, memberId: String) {
        mutableState.value = state.value.copy(lists = state.value.lists.filterNot { it.id == id && it.member.id == memberId })
    }

    fun cancelImport() {
        generation++
        job?.cancel()
        mutableState.value = state.value.copy(importing = false)
    }

    fun clear() {
        cancelImport()
        mutableState.value = PlaylistInputState()
    }
}
