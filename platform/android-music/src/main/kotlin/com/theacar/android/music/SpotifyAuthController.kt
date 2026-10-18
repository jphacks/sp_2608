package com.theacar.android.music

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.theacar.android.security.EncryptedSecretFile
import com.theacar.music.MusicException
import com.theacar.music.MusicFailure
import com.theacar.spotify.SpotifyAuthorization
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SpotifyConnectionState(val configured: Boolean, val loading: Boolean = true, val connected: Boolean = false, val failure: MusicFailure? = null)

/**
 * Spotify のアプリIDとログイン状態を持つ。アプリIDは端末で保存した値を優先し、無ければビルド時の値を使う。
 * 資格情報の保存・復元・破棄はここだけで行い、再生や推薦の後始末は呼び出し側から渡してもらう。
 */
internal class SpotifyAuthController(context: Context, private val scope: CoroutineScope, client: HttpClient) {
    @Suppress("DEPRECATION")
    private val buildClientId = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData?.getString("com.theacar.SPOTIFY_CLIENT_ID").orEmpty().trim()
    private val clientIdFile = EncryptedSecretFile(context, "spotify-client.enc", "com.theacar.spotify-client.v1")
    @Volatile private var savedClientId = ""
    val clientId: String get() = savedClientId.ifEmpty { buildClientId }
    private val storage = SpotifyCredentialStore(context) { clientId }
    val authorization = SpotifyAuthorization({ clientId }, SpotifyAuthSettings.REDIRECT_URI, client, storage, System::currentTimeMillis)
    private val mutex = Mutex()
    private val mutableConnection = MutableStateFlow(SpotifyConnectionState(SpotifyAuthSettings.isClientId(buildClientId)))
    val connection = mutableConnection.asStateFlow()

    /** 保存したアプリIDと資格情報を読み戻す。起動時に1回呼ぶ。 */
    fun restore() = locked {
        savedClientId = withContext(Dispatchers.IO) { runCatching { clientIdFile.read().trim() }.getOrDefault("") }
            .takeIf { SpotifyAuthSettings.isClientId(it) }.orEmpty()
        val configured = SpotifyAuthSettings.isClientId(clientId)
        mutableConnection.update { it.copy(configured = configured) }
        val connected = configured && authorization.restore()
        mutableConnection.update { it.copy(connected = connected) }
    }

    /** 別のアプリIDに切り替えるか。形が正しく、今と違うときだけ真。 */
    fun accepts(value: String) = value.trim().let { SpotifyAuthSettings.isClientId(it) && it != clientId }

    /** アプリIDを保存し、前のアプリIDで取得した資格情報を捨てる。[onSwitched] で再生や推薦の状態を消す。 */
    fun saveClientId(value: String, onSwitched: suspend () -> Unit) {
        val trimmed = value.trim()
        if (!accepts(trimmed)) return
        locked(clearFailure = true) {
            withContext(Dispatchers.IO) { clientIdFile.write(trimmed) }
            savedClientId = trimmed
            authorization.disconnect()
            storage.saveLogin(null)
            onSwitched()
            mutableConnection.update { it.copy(configured = true, connected = false, failure = null) }
        }
    }

    suspend fun loginUrl(): String? = mutex.withLock {
        if (!connection.value.configured) return@withLock null
        mutableConnection.update { it.copy(loading = true, failure = null) }
        try {
            val request = withContext(Dispatchers.Default) { SpotifyLogin.create(System.currentTimeMillis()) }
            storage.saveLogin(request)
            authorization.loginUrl(request.state, SpotifyLogin.challenge(request.verifier))
        } catch (error: Exception) { report(error); null }
        finally { mutableConnection.update { it.copy(loading = false) } }
    }

    /** ログイン画面からの戻り。受け付けたら [onConnected] を呼ぶ。 */
    fun finishLogin(uri: Uri?, onConnected: () -> Unit) = locked(clearFailure = true) {
        if (uri?.scheme != "com.theacar.android.spotify" || uri.host != "oauth" || uri.path != "/callback" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) {
            throw MusicException(MusicFailure.AUTHORIZATION)
        }
        val request = storage.readLogin() ?: throw MusicException(MusicFailure.AUTHORIZATION)
        val state = uri.getQueryParameters("state").singleOrNull()
        val code = uri.getQueryParameters("code").singleOrNull()?.takeIf { it.isNotBlank() }
        if (!request.accepts(state, System.currentTimeMillis())) {
            throw MusicException(MusicFailure.AUTHORIZATION)
        }
        storage.saveLogin(null)
        if (code == null || uri.getQueryParameter("error") != null) throw MusicException(MusicFailure.AUTHORIZATION)
        authorization.exchange(code, request.verifier)
        mutableConnection.update { it.copy(connected = true) }
        onConnected()
    }

    /** [beforeDisconnect] で再生の状態を消してから、資格情報を捨てる。 */
    fun disconnect(beforeDisconnect: () -> Unit) = locked {
        beforeDisconnect()
        authorization.disconnect()
        storage.saveLogin(null)
        mutableConnection.update { it.copy(connected = false, failure = null) }
    }

    /** 再生側で認証が切れたと分かったとき。 */
    fun signedOut(failure: MusicFailure) {
        mutableConnection.update { it.copy(connected = false, failure = failure) }
    }

    private fun locked(clearFailure: Boolean = false, action: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                mutableConnection.update { if (clearFailure) it.copy(loading = true, failure = null) else it.copy(loading = true) }
                try { action() } catch (error: Exception) { report(error) }
                finally { mutableConnection.update { it.copy(loading = false) } }
            }
        }
    }

    private fun report(error: Exception) {
        if (error is CancellationException) throw error
        mutableConnection.update { it.copy(failure = (error as? MusicException)?.failure ?: MusicFailure.NETWORK) }
    }
}
