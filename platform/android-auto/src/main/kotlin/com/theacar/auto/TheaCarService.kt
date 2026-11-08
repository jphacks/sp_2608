package com.theacar.auto

import com.theacar.android.navigation.AndroidNavigationOwner
import com.theacar.cabin.CabinNoticeOwner
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class TheaCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) {
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
        // Google の Android Auto と Android Automotive OS の公式ホストだけを許す。一覧はライブラリが持ち、版を上げると更新される
        HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    // 知らせの配信元が無くても車載画面は動く。あるときだけ受け取る。
    override fun onCreateSession(): Session = TheaCarSession(
        (application as AndroidNavigationOwner).navigationRuntime,
        (application as? CabinNoticeOwner)?.cabinNotices,
    )
}
