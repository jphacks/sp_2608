package com.theacar.auto

import com.theacar.android.navigation.AndroidNavigationOwner
import com.theacar.android.navigation.NavigationCompanionOwner
import com.theacar.cabin.CabinNoticeOwner
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class TheaCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) {
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
        HostValidator.Builder(applicationContext).build()
    }

    // 知らせの配信元や同乗者リクエストが無くても車載画面は動く。あるときだけ受け取る。
    override fun onCreateSession(): Session = TheaCarSession(
        (application as AndroidNavigationOwner).navigationRuntime,
        (application as? CabinNoticeOwner)?.cabinNotices,
        (application as? NavigationCompanionOwner)?.navigationCompanion,
    )
}
