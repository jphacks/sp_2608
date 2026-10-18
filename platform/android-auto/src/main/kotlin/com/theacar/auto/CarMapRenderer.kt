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
    private var qrPanel: android.widget.LinearLayout? = null
    private var qrImage: android.widget.ImageView? = null
    private var qrCaption: android.widget.TextView? = null
    private var qrUrl: String? = null
    private var sceneryRequest: CarScenery? = null
    /** 最後に頼まれた QR の表示。地図を作り直したときにも同じ状態に戻す。 */
    private var qrRequest: Triple<String?, String, Boolean> = Triple(null, "", false)

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
        controller = map
        val layout = android.widget.LinearLayout(window.context).apply { orientation = android.widget.LinearLayout.VERTICAL }
        layout.addView(map.view, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(android.widget.TextView(window.context).apply { text = "© OpenStreetMap contributors · MapLibre / Valhalla" })
        // 同乗者のリクエストの QR コード。地図の上に重ね、見えている範囲の右下に置く
        val root = android.widget.FrameLayout(window.context)
        root.addView(layout, android.widget.FrameLayout.LayoutParams(-1, -1))
        val density = window.context.resources.displayMetrics.density
        val pad = (10 * density).toInt()
        val image = android.widget.ImageView(window.context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        val caption = android.widget.TextView(window.context).apply {
            setTextColor(android.graphics.Color.BLACK)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
        }
        val panel = android.widget.LinearLayout(window.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 12 * density
            }
            addView(image)
            addView(caption, android.widget.LinearLayout.LayoutParams(-1, -2))
            visibility = android.view.View.GONE
        }
        root.addView(panel, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END))
        qrPanel = panel
        qrImage = image
        qrCaption = caption
        qrUrl = null
        map.view.onStart()
        map.view.onResume()
        window.setContentView(root)
        window.show()
        showScenery(sceneryRequest)
        val (url, label, shown) = qrRequest
        showRequestQr(url, label, shown)
    }

    /** ルート線を景観で塗り分ける。地図を作り直したときも同じ状態に戻せるよう覚えておく。 */
    fun showScenery(model: CarScenery?) {
        sceneryRequest = model
        controller?.paintScenery(model?.blocks.orEmpty().map { it.range to carSceneryColor(it.category) })
    }

    /**
     * 同乗者のリクエストの QR コードを出す・隠す。[url] が null の間は「準備しています」とだけ出す。
     * テンプレートの部品（案内のカードやボタン）に重ならないよう、見えている範囲の右下に置く。
     */
    fun showRequestQr(url: String?, label: String, shown: Boolean) {
        qrRequest = Triple(url, label, shown)
        val panel = qrPanel ?: return
        val surface = container ?: return
        if (!shown) { panel.visibility = android.view.View.GONE; return }
        val area = visibleArea ?: Rect(0, 0, surface.width, surface.height)
        val density = panel.resources.displayMetrics.density
        val side = carQrSide(area.width(), area.height(), density)
        val margin = (12 * density).toInt()
        panel.layoutParams = (panel.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            rightMargin = (surface.width - area.right).coerceAtLeast(0) + margin
            bottomMargin = (surface.height - area.bottom).coerceAtLeast(0) + margin
        }
        qrImage?.layoutParams = android.widget.LinearLayout.LayoutParams(side, side)
        if (url != qrUrl) {
            qrUrl = url
            qrImage?.setImageBitmap(url?.let { carQrBitmap(it, side) })
        }
        qrCaption?.text = if (url == null) "準備しています…" else label
        panel.visibility = android.view.View.VISIBLE
        panel.requestLayout()
    }

    /** 1 より大きいと寄る。 */
    fun zoom(factor: Float) { controller?.scale(factor) }

    fun recenter() { controller?.recenter() }

    fun scroll(x: Float, y: Float) { controller?.scroll(x, y) }
    fun setVisibleArea(area: Rect) {
        visibleArea = Rect(area)
        showScenery(sceneryRequest)
        val (url, label, shown) = qrRequest
        if (shown) showRequestQr(url, label, true)
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
        qrPanel = null
        qrImage = null
        qrCaption = null

        presentation = null
        display = null
        container = null
    }
}
