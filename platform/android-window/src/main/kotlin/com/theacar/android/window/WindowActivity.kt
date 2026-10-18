package com.theacar.android.window

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.theacar.ui.TheaCarTheme
import com.theacar.ui.openTab
import kotlinx.coroutines.flow.MutableStateFlow

class WindowActivity : ComponentActivity() {
    private val runtime get() = (application as AndroidWindowOwner).windowRuntime
    private val permitted = MutableStateFlow(false)

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val link by runtime.link.collectAsState()
            val air by runtime.air.collectAsState()
            val log by runtime.log.collectAsState()
            val failure by runtime.failure.collectAsState()
            val allowed by permitted.collectAsState()
            TheaCarTheme {
                WindowScreen(
                    deviceName = runtime.configuration.deviceName,
                    permitted = allowed,
                    link = link,
                    air = air,
                    log = log,
                    failure = failure,
                    onRequestPermission = {
                        request.launch(WindowPermissions.required().toTypedArray())
                    },
                    onOpen = runtime::openWindow,
                    onClose = runtime::closeWindow,
                    onStop = runtime::stopWindow,
                    onDismissFailure = runtime::dismissFailure,
                    onBack = ::finish,
                    onSelectTab = ::openTab,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 許可の状態を見直し、揃っていれば接続を始める。接続は Application が持ち続ける。 */
    private fun refresh() {
        val granted = WindowPermissions.granted(this)
        permitted.value = granted
        if (granted) runtime.start()
    }
}
