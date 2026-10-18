package com.theacar.android.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface RoutingTokenStorage {
    fun read(): String
    fun write(token: String)
}

internal class RoutingCredentials(
    private val storage: RoutingTokenStorage,
    scope: CoroutineScope,
    /** ビルド時に埋め込んだ共有トークン。端末で保存した値が無いときに使う。 */
    private val fallback: String = "",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val builtIn = fallback.trim()
    private val mutableToken = MutableStateFlow(builtIn)
    val token = mutableToken.asStateFlow()
    private val mutableUsingBuiltIn = MutableStateFlow(builtIn.isNotEmpty())
    /** 端末に保存した値ではなく、埋め込みの値で動いているか。 */
    val usingBuiltIn = mutableUsingBuiltIn.asStateFlow()
    private val mutableLoaded = MutableStateFlow(false)
    val loaded = mutableLoaded.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                try {
                    apply(withContext(io) { storage.read() })
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    apply("")
                    // 埋め込みの値で動けるなら、読み取り失敗を利用者に見せない。
                    if (builtIn.isEmpty()) {
                        mutableError.value = "保存済みトークンを読み込めません。もう一度入力して保存してください。"
                    }
                } finally {
                    mutableLoaded.value = true
                }
            }
        }
    }

    private fun apply(stored: String) {
        val saved = stored.trim()
        mutableToken.value = saved.ifEmpty { builtIn }
        mutableUsingBuiltIn.value = saved.isEmpty() && builtIn.isNotEmpty()
    }

    suspend fun save(token: String): Boolean = mutex.withLock {
        try {
            val normalized = token.trim()
            withContext(io) { storage.write(normalized) }
            // 削除したときは埋め込みの値へ戻す。
            apply(normalized)
            mutableError.value = null
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableError.value = "トークンを保存できませんでした。端末の空き容量を確認して再試行してください。"
            false
        }
    }
}
