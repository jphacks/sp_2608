package com.theacar.android.window

import android.app.Application
import com.theacar.cabin.CabinNoticeSource
import com.theacar.cabin.WindowControlException
import com.theacar.cabin.WindowGateway
import com.theacar.cabin.WindowMove
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface AndroidWindowOwner { val windowRuntime: AndroidWindowRuntime }

/**
 * 窓の機器への接続を1つ所有する。Activity が作り直されても同じ接続を使う。
 * 機器がにおいを検知して窓を開けたときの知らせも、ここから配る。
 */
class AndroidWindowRuntime(application: Application) : CabinNoticeSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val configuration = WindowConfiguration.Default
    private val ble = BleWindowGateway(application, configuration, scope)

    val gateway: WindowGateway get() = ble
    val link = ble.link
    val air = ble.air
    val log = ble.log
    override val notices = ble.notices

    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()

    /** BLE の許可が揃ってから呼ぶ。窓の画面を開くたびに呼んでよい。 */
    fun start() = ble.start()

    fun openWindow() = request { ble.move(WindowMove.OPEN) }

    fun closeWindow() = request { ble.move(WindowMove.CLOSE) }

    fun stopWindow() = request { ble.stop() }

    fun dismissFailure() { mutableFailure.value = null }

    private fun request(command: suspend () -> Unit) {
        scope.launch {
            try {
                command()
                mutableFailure.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: WindowControlException) {
                mutableFailure.value = failure.message
            }
        }
    }
}
