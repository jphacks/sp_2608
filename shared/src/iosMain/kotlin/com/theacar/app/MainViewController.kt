package com.theacar.app

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

fun MainViewController() = ComposeUIViewController {
    val graph = remember { AppGraph.demo() }
    DisposableEffect(graph) {
        onDispose { graph.close() }
    }
    TheaCarApp(graph)
}
