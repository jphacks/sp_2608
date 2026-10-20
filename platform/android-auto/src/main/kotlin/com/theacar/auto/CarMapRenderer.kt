package com.theacar.auto

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.CarContext
import androidx.car.app.SurfaceContainer
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.android.navigation.NavigationMapController
import kotlinx.coroutines.CoroutineScope

internal class CarMapRenderer(
    private val context: CarContext,
    private val runtime: AndroidNavigationRuntime,
    private val scope: CoroutineScope,
) {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var controller: NavigationMapController? = null
    private var container: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var sceneryRequest: CarScenery? = null
    private var attribution: android.widget.TextView? = null

    fun attach(surface: SurfaceContainer) {
        if (container === surface && display != null) return
        val target = surface.surface ?: return
        if (!target.isValid || surface.width <= 0 || surface.height <= 0 || surface.dpi <= 0) return
        close()
        val manager = context.getSystemService(DisplayManager::class.java)
        val virtualDisplay = manager.createVirtualDisplay("TheaCar OSM Map", surface.width, surface.height,
            surface.dpi, target, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) ?: return
        display = virtualDisplay
        container = surface
        val window = Presentation(context, virtualDisplay.display)
        presentation = window
        val map = NavigationMapController(window.context, runtime, scope)
        map.setDarkMode(context.isDarkMode)
        controller = map
        val layout = android.widget.LinearLayout(window.context).apply { orientation = android.widget.LinearLayout.VERTICAL }
        layout.addView(map.view, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val credit = android.widget.TextView(window.context).apply { text = "© OpenStreetMap contributors · MapLibre / Valhalla" }
        layout.addView(credit)
        attribution = credit
        paintAttribution(context.isDarkMode)
        map.view.onStart()
        map.view.onResume()
        window.setContentView(layout)
        window.show()
        showScenery(sceneryRequest)
    }

    /** ルート線を景観で塗り分ける。地図を作り直したときも同じ状態に戻せるよう覚えておく。 */
    fun showScenery(model: CarScenery?) {
        sceneryRequest = model
        controller?.paintScenery(model?.blocks.orEmpty().map { it.range to carSceneryColor(it.category) })
    }

    /** 車が昼夜の表示を切り替えたとき（MR-1）。 */
    fun setDarkMode(enabled: Boolean) {
        controller?.setDarkMode(enabled)
        paintAttribution(enabled)
    }

    private fun paintAttribution(dark: Boolean) {
        attribution?.setBackgroundColor(android.graphics.Color.parseColor(if (dark) "#1B2420" else "#F5F3EC"))
        attribution?.setTextColor(android.graphics.Color.parseColor(if (dark) "#A9B5AE" else "#5C6B63"))
    }

    /** 1 より大きいと寄る。 */
    fun zoom(factor: Float) { controller?.scale(factor) }

    fun recenter() { controller?.recenter() }

    fun scroll(x: Float, y: Float) { controller?.scroll(x, y) }
    fun setVisibleArea(area: Rect) {
        visibleArea = Rect(area)
        showScenery(sceneryRequest)
        val surface = container ?: return
        controller?.setPadding(area.left.coerceAtLeast(0), area.top.coerceAtLeast(0),
            (surface.width - area.right).coerceAtLeast(0), (surface.height - area.bottom).coerceAtLeast(0))
    }
    fun scale(x: Float, y: Float, factor: Float) { controller?.scale(factor) }

    fun close() {
        controller?.view?.onPause()
        controller?.view?.onStop()
        controller?.close()
        presentation?.dismiss()
        display?.release()
        controller = null
        attribution = null
        presentation = null
        display = null
        container = null
    }
}
