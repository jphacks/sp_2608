package com.theacar.auto

import android.content.Context
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.core.graphics.drawable.IconCompat

internal object ManeuverMapping {
    fun carType(valhallaType: Int): Int = when (valhallaType) {
        1, 2, 3 -> Maneuver.TYPE_DEPART
        4 -> Maneuver.TYPE_DESTINATION
        5 -> Maneuver.TYPE_DESTINATION_RIGHT
        6 -> Maneuver.TYPE_DESTINATION_LEFT
        7 -> Maneuver.TYPE_NAME_CHANGE
        8, 17, 22 -> Maneuver.TYPE_STRAIGHT
        9 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        10 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        11 -> Maneuver.TYPE_TURN_SHARP_RIGHT
        12 -> Maneuver.TYPE_U_TURN_RIGHT
        13 -> Maneuver.TYPE_U_TURN_LEFT
        14 -> Maneuver.TYPE_TURN_SHARP_LEFT
        15 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        16 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        18 -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        19 -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
        20 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        21 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        23 -> Maneuver.TYPE_KEEP_RIGHT
        24 -> Maneuver.TYPE_KEEP_LEFT
        25 -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        26 -> Maneuver.TYPE_ROUNDABOUT_ENTER_CW
        27 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CW
        28, 29 -> Maneuver.TYPE_FERRY_BOAT
        37 -> Maneuver.TYPE_MERGE_RIGHT
        38 -> Maneuver.TYPE_MERGE_LEFT
        else -> Maneuver.TYPE_UNKNOWN
    }

    fun iconRes(carType: Int): Int = when (carType) {
        Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_FORK_LEFT, Maneuver.TYPE_MERGE_LEFT,
        Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> R.drawable.ic_maneuver_slight_left
        Maneuver.TYPE_TURN_SLIGHT_RIGHT, Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_FORK_RIGHT, Maneuver.TYPE_MERGE_RIGHT,
        Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> R.drawable.ic_maneuver_slight_right
        Maneuver.TYPE_TURN_NORMAL_LEFT -> R.drawable.ic_maneuver_turn_left
        Maneuver.TYPE_TURN_NORMAL_RIGHT -> R.drawable.ic_maneuver_turn_right
        Maneuver.TYPE_TURN_SHARP_LEFT -> R.drawable.ic_maneuver_sharp_left
        Maneuver.TYPE_TURN_SHARP_RIGHT -> R.drawable.ic_maneuver_sharp_right
        Maneuver.TYPE_U_TURN_LEFT -> R.drawable.ic_maneuver_uturn_left
        Maneuver.TYPE_U_TURN_RIGHT -> R.drawable.ic_maneuver_uturn_right
        Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CW -> R.drawable.ic_maneuver_roundabout
        Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_LEFT, Maneuver.TYPE_DESTINATION_RIGHT -> R.drawable.ic_maneuver_destination
        else -> R.drawable.ic_maneuver_straight
    }

    fun step(context: Context, instruction: String, road: String, valhallaType: Int): Step {
        val type = carType(valhallaType)
        val icon = CarIcon.Builder(IconCompat.createWithResource(context, iconRes(type))).setTint(CarColor.DEFAULT).build()
        return Step.Builder().setCue(instruction).setRoad(road)
            .setManeuver(Maneuver.Builder(type).setIcon(icon).build()).build()
    }
}
