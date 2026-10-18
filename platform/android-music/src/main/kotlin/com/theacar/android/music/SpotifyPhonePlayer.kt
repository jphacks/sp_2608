package com.theacar.android.music

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.theacar.music.MusicException
import com.theacar.music.MusicFailure
import com.theacar.music.MusicPlayback
import com.theacar.music.MusicTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** この端末の再生アプリへの操作。[PhoneAwareMusicService] が再生先ごとに振り分ける。 */
internal interface PhonePlayer {
    val installed: Boolean
    suspend fun play(uri: String)
    suspend fun resume()
    suspend fun queue(uri: String)
    suspend fun pause()
    suspend fun next()
    suspend fun previous()
    suspend fun playback(): MusicPlayback
}

/** App Remote で操作できない理由。画面で直し方を示すために、MusicFailure より細かく持つ。 */
enum class PhonePlayerProblem { NOT_INSTALLED, NOT_LOGGED_IN, NOT_AUTHORIZED, OFFLINE, UNAVAILABLE }

/**
 * この端末の Spotify アプリを App Remote SDK で操作する。
 *
 * Web API の Spotify Connect は、起動中の Spotify アプリしか再生先として返さない。
 * App Remote は Spotify アプリのサービスへ接続するので、利用者がアプリを開かなくても裏で起動して再生できる。
 * 初回だけ Spotify アプリ側の許可画面が出る。SDK の呼び出し結果はメインスレッドで返る。
 */
internal class SpotifyPhonePlayer(
    private val context: Context,
    private val clientId: () -> String,
    private val redirectUri: String,
) : PhonePlayer {
    private val mutex = Mutex()
    private var remote: SpotifyAppRemote? = null
    private val mutableProblem = MutableStateFlow<PhonePlayerProblem?>(null)
    val problem = mutableProblem.asStateFlow()

    override val installed: Boolean get() = SpotifyAppRemote.isSpotifyInstalled(context)

    override suspend fun play(uri: String) { connected().playerApi.play(uri).awaitResult() }
    override suspend fun resume() { connected().playerApi.resume().awaitResult() }
    override suspend fun queue(uri: String) { connected().playerApi.queue(uri).awaitResult() }
    override suspend fun pause() { connected().playerApi.pause().awaitResult() }
    override suspend fun next() { connected().playerApi.skipNext().awaitResult() }
    override suspend fun previous() { connected().playerApi.skipPrevious().awaitResult() }

    /** 現在の再生状態。購読ではなく毎回問い合わせて、再生位置を新しく保つ。 */
    override suspend fun playback(): MusicPlayback {
        val state = connected().playerApi.playerState.awaitResult()
        val track = state.track?.takeIf { !it.uri.isNullOrBlank() && !it.isEpisode && !it.isPodcast }?.let {
            MusicTrack(it.uri, it.name.orEmpty(), it.artist?.name.orEmpty(), "",
                it.duration.takeIf { duration -> duration in 1..Int.MAX_VALUE }?.toInt())
        }
        return MusicPlayback(track, !state.isPaused, PHONE_DEVICE_ID,
            positionMillis = state.playbackPosition.takeIf { it in 0..Int.MAX_VALUE }?.toInt())
    }

    fun disconnect() {
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
    }

    private suspend fun connected(): SpotifyAppRemote = mutex.withLock {
        remote?.takeIf { it.isConnected }?.let { return it }
        if (!installed) {
            mutableProblem.value = PhonePlayerProblem.NOT_INSTALLED
            throw MusicException(MusicFailure.NO_DEVICE)
        }
        val params = ConnectionParams.Builder(clientId()).setRedirectUri(redirectUri).showAuthView(true).build()
        // 初回は Spotify アプリの許可画面を待つので長めにとる。
        val connected = withTimeout(60_000) {
            suspendCancellableCoroutine { continuation ->
                SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) {
                        if (continuation.isActive) continuation.resume(appRemote) else SpotifyAppRemote.disconnect(appRemote)
                    }

                    override fun onFailure(error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure(error))
                    }
                })
            }
        }
        mutableProblem.value = null
        remote = connected
        connected
    }

    private fun failure(error: Throwable): MusicException {
        val problem = when (error) {
            is CouldNotFindSpotifyApp -> PhonePlayerProblem.NOT_INSTALLED
            is NotLoggedInException -> PhonePlayerProblem.NOT_LOGGED_IN
            is UserNotAuthorizedException, is AuthenticationFailedException -> PhonePlayerProblem.NOT_AUTHORIZED
            is OfflineModeException -> PhonePlayerProblem.OFFLINE
            else -> PhonePlayerProblem.UNAVAILABLE
        }
        mutableProblem.value = problem
        // Web API のログインとは別物なので AUTHORIZATION にはしない。そちらは Web API の資格情報を消してしまう。
        return MusicException(when (problem) {
            PhonePlayerProblem.NOT_INSTALLED -> MusicFailure.NO_DEVICE
            PhonePlayerProblem.OFFLINE, PhonePlayerProblem.UNAVAILABLE -> MusicFailure.NETWORK
            else -> MusicFailure.ACCESS_DENIED
        })
    }

    private suspend fun <T> CallResult<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        setResultCallback { if (continuation.isActive) continuation.resume(it) }
        setErrorCallback { error ->
            // 接続が切れた後の呼び出しは、次回に接続し直す。
            if (error is com.spotify.android.appremote.api.error.SpotifyDisconnectedException ||
                error is com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException) remote = null
            if (continuation.isActive) continuation.resumeWithException(failure(error))
        }
        continuation.invokeOnCancellation { cancel() }
    }

    companion object {
        /** Web API の端末 ID と衝突しない、この端末を表す固定の ID。 */
        const val PHONE_DEVICE_ID = "theacar:this-phone"
    }
}
