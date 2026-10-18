package com.theacar.android.music

import com.theacar.android.music.SpotifyPhonePlayer.Companion.PHONE_DEVICE_ID
import com.theacar.music.MusicDevice
import com.theacar.music.MusicPlayback
import com.theacar.music.MusicService
import com.theacar.music.MusicTrack

/**
 * 再生先に「このスマホ」を加える。その再生先への操作だけを App Remote へ送り、それ以外は Web API のまま。
 *
 * 検索と他の端末の操作は Web API が担う。再生状態は、最後に操作した再生先から取る。
 * Spotify アプリが起動していると Web API にも同じ端末が別名で載るが、どちらを選んでも同じアプリで鳴る。
 */
internal class PhoneAwareMusicService(
    private val web: MusicService,
    private val phone: PhonePlayer,
    /** 再生操作の記録（原因調査用）。曲の URI と再生先の種類だけを書く。 */
    private val log: (String) -> Unit = {},
) : MusicService {
    @Volatile private var usingPhone = false

    override suspend fun search(query: String) = web.search(query)

    override suspend fun devices(): List<MusicDevice> {
        val devices = web.devices()
        if (!phone.installed) return devices
        return devices + MusicDevice(PHONE_DEVICE_ID, PHONE_DEVICE_NAME, active = usingPhone)
    }

    override suspend fun playback(): MusicPlayback = if (usingPhone) phone.playback() else web.playback()

    override suspend fun play(trackUri: String?, deviceId: String) {
        log("play $trackUri via ${route(deviceId)}")
        if (deviceId != PHONE_DEVICE_ID) {
            web.play(trackUri, deviceId)
            usingPhone = false
            return
        }
        if (trackUri == null) phone.resume() else phone.play(trackUri)
        usingPhone = true
    }

    override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) {
        if (deviceId != PHONE_DEVICE_ID) {
            web.playMix(tracks, deviceId)
            usingPhone = false
            return
        }
        val first = tracks.firstOrNull() ?: return
        phone.play(first.uri)
        usingPhone = true
        tracks.drop(1).forEach { phone.queue(it.uri) }
    }

    override suspend fun enqueue(trackUri: String, deviceId: String) {
        if (deviceId == PHONE_DEVICE_ID) phone.queue(trackUri) else web.enqueue(trackUri, deviceId)
    }

    private fun route(deviceId: String) = if (deviceId == PHONE_DEVICE_ID) "app-remote" else "web-api id=${deviceId.take(8)}"
    override suspend fun pause(deviceId: String) = if (deviceId == PHONE_DEVICE_ID) phone.pause() else web.pause(deviceId)
    override suspend fun next(deviceId: String) = if (deviceId == PHONE_DEVICE_ID) phone.next() else web.next(deviceId)
    override suspend fun previous(deviceId: String) = if (deviceId == PHONE_DEVICE_ID) phone.previous() else web.previous(deviceId)

    companion object {
        const val PHONE_DEVICE_NAME = "このスマホ"
    }
}
