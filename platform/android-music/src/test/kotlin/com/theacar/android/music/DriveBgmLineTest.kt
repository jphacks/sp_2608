package com.theacar.android.music

import com.theacar.music.CabinListening
import com.theacar.music.CabinStatus
import com.theacar.music.NowPlaying
import com.theacar.music.BgmMusicState
import com.theacar.music.CabinState
import com.theacar.music.MusicFailure
import com.theacar.music.MusicPlayback
import com.theacar.music.MusicTrack
import com.theacar.music.RecommendedTrack
import com.theacar.music.SongRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DriveBgmLineTest {
    private val matsuri = MusicTrack("spotify:track:3OdkC5pG8vc26S26qHyBo8", "まつり", "藤井 風", "", 225_000)
    private fun now(playing: Boolean, track: MusicTrack? = matsuri) = NowPlaying(MusicPlayback(track, playing, "phone", positionMillis = 60_000), 0)
    private fun state(bgm: BgmMusicState = BgmMusicState(), cabin: CabinStatus = CabinStatus(),
        readiness: DriveBgmReadiness = DriveBgmReadiness.READY, problem: PhonePlayerProblem? = null) =
        DriveBgmState(true, readiness, "このスマホ", false, bgm, cabin, problem)
    private val playingBgm = BgmMusicState(enabled = true, playing = RecommendedTrack("isrc:JPPO02200420", "まつり", "藤井 風"),
        message = "海が近いので、爽やかな曲を流します", playingUri = "spotify:track:3OdkC5pG8vc26S26qHyBo8")

    @Test fun scenicBgmShowsTheSongAndWhyItWasChosen() {
        val line = driveBgmLine(state(playingBgm), now(true), connected = true, guiding = true)
        assertEquals("まつり · 藤井 風", line.title)
        assertEquals("海が近いので、爽やかな曲を流します", line.subtitle)
        assertNull(line.actionLabel)
    }

    @Test fun withoutAReasonTheCabinMoodIsShown() {
        val line = driveBgmLine(state(playingBgm.copy(message = null), CabinStatus(CabinListening.ACTIVE, CabinState.LIVELY)),
            now(true), connected = true, guiding = true)
        assertEquals("車内：会話が弾んでいます", line.subtitle)
    }

    @Test fun pausedScenicBgmSaysItWillContinue() {
        val line = driveBgmLine(state(playingBgm), now(false), connected = true, guiding = true)
        assertEquals("一時停止中 · 再生すると景観BGMを続けます", line.subtitle)
    }

    @Test fun aSongPickedByHandCanGoBackToScenicBgmDuringGuidance() {
        val line = driveBgmLine(state(), now(true), connected = true, guiding = true)
        assertEquals("まつり · 藤井 風", line.title)
        assertEquals("BGMに戻す", line.actionLabel)
        assertEquals(DriveBgmAction.START_BGM, line.action)
        // 案内中でなければ景観BGMの操作は出さない
        assertNull(driveBgmLine(state(), now(true), connected = true, guiding = false).actionLabel)
    }

    @Test fun problemsComeWithTheWayToFixThem() {
        val failed = BgmMusicState(failure = MusicFailure.NO_DEVICE)
        val line = driveBgmLine(state(failed, problem = PhonePlayerProblem.NOT_INSTALLED), null, connected = true, guiding = true)
        assertEquals("Spotifyアプリが入っていません", line.title)
        assertEquals(DriveBgmAction.INSTALL_SPOTIFY, line.action)
    }

    @Test fun notConnectedAsksToConnect() {
        val line = driveBgmLine(state(), null, connected = false, guiding = true)
        assertEquals(DriveBgmAction.CONNECT, line.action)
    }

    @Test fun nothingPlayingDuringGuidanceOffersToStart() {
        val line = driveBgmLine(state(), null, connected = true, guiding = true)
        assertEquals("流す", line.actionLabel)
        assertEquals("再生していません", driveBgmLine(state(), null, connected = true, guiding = false).title)
    }

    @Test fun aGuestRequestShowsWhoAskedForItAndWhatComesNext() {
        val request = SongRequest("guest:1", matsuri.uri, "まつり", "藤井 風", requester = "たろう")
        val bgm = playingBgm.copy(playingRequest = request, message = null)
        assertEquals("たろうさんのリクエスト · 終わったら景観BGMに戻ります",
            driveBgmLine(state(bgm), now(true), connected = true, guiding = true).subtitle)
        val queued = bgm.copy(requests = listOf(SongRequest("guest:2", "spotify:track:" + "b".repeat(22), "次", "B", requester = "はな")))
        assertEquals("たろうさんのリクエスト · このあと予約1曲", driveBgmLine(state(queued), now(true), connected = true, guiding = true).subtitle)
        // 自分で選んだ曲は名前を出さない
        assertEquals("リクエスト曲 · 終わったら景観BGMに戻ります",
            driveBgmLine(state(bgm.copy(playingRequest = request.copy(requester = null))), now(true), connected = true, guiding = true).subtitle)
    }

    @Test fun aSongTheUserQueuedIsShownAsARequest() {
        val requested = MusicTrack("spotify:track:0000000000000000000001", "ハルジオン", "YOASOBI", "", 200_000)
        val line = driveBgmLine(state(playingBgm), now(true, requested), connected = true, guiding = true)
        assertEquals("ハルジオン · YOASOBI", line.title)
        assertEquals("リクエスト曲 · 終わったら景観BGMに戻ります", line.subtitle)
        assertNull(line.actionLabel)
    }

    @Test fun aScenicSongIsNotARequestEvenIfSpotifySpellsTheTitleDifferently() {
        // カタログは「まつり」、Spotify は別の表記。同じ曲なので URI で同じと判断する
        val sameSong = MusicTrack("spotify:track:3OdkC5pG8vc26S26qHyBo8", "Matsuri", "Fujii Kaze", "", 225_000)
        val line = driveBgmLine(state(playingBgm), now(true, sameSong), connected = true, guiding = true)
        assertEquals("海が近いので、爽やかな曲を流します", line.subtitle)
    }

    @Test fun theNextScenicSongJustStartingIsNotARequest() {
        // 次の曲が始まった直後、表示の切り替え前でも、入れておいた景観BGMの曲ならリクエスト扱いしない
        val next = MusicTrack("spotify:track:0000000000000000000002", "なないろ", "BUMP OF CHICKEN", "", 200_000)
        val line = driveBgmLine(state(playingBgm.copy(upNextUri = next.uri)), now(true, next), connected = true, guiding = true)
        // 選曲の一言は前の曲のものなので出さず、表示の切り替えまでは一般的な説明にする
        assertEquals("景色と車内の様子で選曲中", line.subtitle)
    }
}
