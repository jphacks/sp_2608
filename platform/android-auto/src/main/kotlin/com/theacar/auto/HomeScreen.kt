@file:Suppress("DEPRECATION")

package com.theacar.auto

import androidx.car.app.CarContext
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.ScreenManager
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.android.navigation.message
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class HomeScreen(carContext: CarContext, runtime: AndroidNavigationRuntime,
    private val controls: CarMapControls? = null) : CarScreen(carContext, runtime) {
    private val screens = carContext.getCarService(ScreenManager::class.java)

    private val limit by lazy { carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST) }

    /** 行が増えるほどカードが伸びて地図が隠れる。ホストの上限いっぱいは使わず、ここで抑える。 */
    private val rowLimit get() = limit.coerceAtMost(HOME_ROW_LIMIT)

    /** ルート確認へ渡している最中。渡した先が描くので、自分は描き直さない。 */
    private var handingOff = false
    override val refreshable get() = !handingOff

    init {
        observe(runtime.session.state.map { Triple(it.phase, it.route?.destination, it.failure) }.distinctUntilChanged())
        observe(runtime.recentDestinations)
    }

    /**
     * ルート確認へ渡す。`before()` で変わった状態で自分を描き直すと、渡す直前にテンプレートを1枚
     * 余計に使う。ホームはリスト系なので、それが5枚目になるとホストにアプリを閉じられる。
     */
    private fun openPreview(allowed: Set<NavigationPhase>, before: () -> Unit = {}) {
        if (runtime.session.state.value.phase !in allowed) return
        handingOff = true
        val opened = try {
            before()
            (runtime.session.state.value.phase in setOf(NavigationPhase.PREVIEWING, NavigationPhase.PREVIEW)).also {
                if (it) screens.push(RoutePreviewScreen(carContext, runtime, controls))
            }
        } finally {
            handingOff = false
        }
        if (!opened) invalidate()
    }


    private fun row(title: String, vararg texts: String?, onClick: (() -> Unit)? = null): Row = Row.Builder().setTitle(title).apply {
        texts.filterNotNull().take(CAR_ROW_TEXT_LIMIT).forEach { addText(it) }
        onClick?.let { setOnClickListener(it) }
    }.build()

    private fun sceneryFlow(state: NavigationState): Row? =
        carSceneryBreakdownLines(carSceneryShares(state), CAR_ROW_TEXT_LIMIT, SCENERY_PER_LINE)
            .takeIf { it.isNotEmpty() }
            ?.let { row(CAR_SCENERY_BREAKDOWN_TITLE, *it.toTypedArray()) }

    override fun onGetTemplate(): Template {
        val state = runtime.session.state.value
        val rows = mutableListOf<Row>()
        state.failure.relevant()?.let { rows += row(it.message()) }
        if (!runtime.configuration.configured) {
            rows += row("ルートサーバーが設定されていません", "スマートフォンのアプリで設定してください。")
        } else {
            when (state.phase) {
                NavigationPhase.GUIDING -> rows += row("案内に戻る", state.route?.destination?.title) {
                    screens.push(GuidanceScreen(carContext, runtime, controls))
                }
                NavigationPhase.ARRIVED -> rows += row("案内を終了") { runtime.session.stop() }
                NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING -> {
                    rows += row("ルートを確認", state.route?.destination?.title) {
                        openPreview(setOf(NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING))
                    }
                    sceneryFlow(state)?.let { rows += it }
                }
                else -> Unit
            }
            if (state.phase in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING)) {
                val recent = runtime.recentDestinations.value
                if (recent.isEmpty()) {
                    rows += row("最近の目的地はまだありません", "停車中にスマートフォンで目的地を選ぶと、ここに表示されます。")
                } else {
                    recent.take((rowLimit - rows.size).coerceAtLeast(0)).forEach { destination ->
                        rows += row(destination.title) {
                            openPreview(setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING)) {
                                runtime.session.preview(destination)
                            }
                        }
                    }
                }
            }
        }
        val emptyText = if (state.phase == NavigationPhase.STARTING || state.phase == NavigationPhase.STOPPING) "案内の準備中です。" else "表示できる項目がありません。"
        val list = ItemList.Builder().setNoItemsMessage(emptyText).apply { rows.take(rowLimit).forEach(::addItem) }.build()
        val header = Header.Builder().setTitle("旅れこ").setStartHeaderAction(Action.APP_ICON).build()
        val mapActions = carMapActionStrip(carContext, controls)
        return if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_7) {
            MapWithContentTemplate.Builder()
                .setMapController(MapController.Builder().setMapActionStrip(mapActions).build())
                .setContentTemplate(ListTemplate.Builder().setHeader(header).setSingleList(list).build())
                .build()
        } else {
            PlaceListNavigationTemplate.Builder().apply {
                if (carContext.carAppApiLevel >= 5) setHeader(header) else setTitle("旅れこ").setHeaderAction(Action.APP_ICON)
            }.setItemList(list).setMapActionStrip(mapActions).build()
        }
    }
}

/** カードに置く行の数。ここを超えると地図より一覧のほうが大きくなる。 */
private const val HOME_ROW_LIMIT = 4

/** 行が持てる文字列の数。 */
internal const val CAR_ROW_TEXT_LIMIT = 2

/** 内訳を1行に何件まで並べるか。これ以上は横に入らず、端で切られる。 */
private const val SCENERY_PER_LINE = 2
