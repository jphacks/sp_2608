package com.theacar.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.theacar.android.navigation.AndroidNavigationRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal abstract class CarScreen(carContext: CarContext, protected val runtime: AndroidNavigationRuntime) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = carContext.getCarService(ScreenManager::class.java)

    /** 画面を渡す直前など、描き直しを見送りたい間だけ false にする。 */
    protected open val refreshable: Boolean get() = true

    /** スタックに載る前の `top` は例外になる。載っていなければ描き直す必要もない。 */
    private val onTop get() = screens.stackSize > 0 && screens.top === this

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
    }

    /**
     * ホストは1タスクにつきテンプレートを5枚しか受け取らず、内容が変わった描き直しも1枚と数える。
     * 使い切るとアプリごと閉じられるので、一番上にいない間と受け渡しの最中は描き直さない。
     */
    protected fun <T> observe(flow: Flow<T>, onChange: (T) -> Unit = {}) {
        scope.launch { flow.collect { onChange(it); if (refreshable && onTop) invalidate() } }
    }
}
