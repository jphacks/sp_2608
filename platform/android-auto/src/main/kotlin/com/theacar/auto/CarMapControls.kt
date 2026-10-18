package com.theacar.auto

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/**
 * 地図のボタンから地図そのものを動かす口。
 * パンモード（[Action.PAN]）だけでは倍率を変えられない車載機があるため、拡大・縮小はボタンで置く。
 */
internal class CarMapControls(
    val zoomIn: () -> Unit,
    val zoomOut: () -> Unit,
    val recenter: () -> Unit,
)

internal const val CAR_ZOOM_STEP = 1.6f

/** 地図の隅のボタン帯。4つまでしか持てないので、これ以上は足せない。 */
internal fun carMapActionStrip(context: CarContext, controls: CarMapControls?): ActionStrip {
    val strip = ActionStrip.Builder()
    controls?.let {
        strip.addAction(iconAction(context, R.drawable.ic_zoom_in, it.zoomIn))
        strip.addAction(iconAction(context, R.drawable.ic_zoom_out, it.zoomOut))
        strip.addAction(iconAction(context, R.drawable.ic_recenter, it.recenter))
    }
    return strip.addAction(Action.PAN).build()
}

private fun iconAction(context: CarContext, resource: Int, onClick: () -> Unit): Action = Action.Builder()
    .setIcon(CarIcon.Builder(IconCompat.createWithResource(context, resource)).build())
    .setOnClickListener(onClick)
    .build()
