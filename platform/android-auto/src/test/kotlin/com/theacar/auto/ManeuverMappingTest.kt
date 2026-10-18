package com.theacar.auto

import androidx.car.app.navigation.model.Maneuver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ManeuverMappingTest {
    @Test fun mapsTurns() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, ManeuverMapping.carType(15))
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, ManeuverMapping.carType(10))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_LEFT, ManeuverMapping.carType(16))
        assertEquals(Maneuver.TYPE_TURN_SHARP_RIGHT, ManeuverMapping.carType(11))
        assertEquals(Maneuver.TYPE_U_TURN_LEFT, ManeuverMapping.carType(13))
        assertEquals(Maneuver.TYPE_U_TURN_RIGHT, ManeuverMapping.carType(12))
    }

    @Test fun mapsStartDestinationAndStraight() {
        assertEquals(Maneuver.TYPE_DEPART, ManeuverMapping.carType(2))
        assertEquals(Maneuver.TYPE_DESTINATION_LEFT, ManeuverMapping.carType(6))
        assertEquals(Maneuver.TYPE_STRAIGHT, ManeuverMapping.carType(8))
        assertEquals(Maneuver.TYPE_STRAIGHT, ManeuverMapping.carType(22))
    }

    @Test fun mapsRampsRoundaboutsAndMerges() {
        assertEquals(Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT, ManeuverMapping.carType(20))
        assertEquals(Maneuver.TYPE_KEEP_LEFT, ManeuverMapping.carType(24))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_CW, ManeuverMapping.carType(26))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_EXIT_CW, ManeuverMapping.carType(27))
        assertEquals(Maneuver.TYPE_MERGE_RIGHT, ManeuverMapping.carType(37))
        assertEquals(Maneuver.TYPE_MERGE_LEFT, ManeuverMapping.carType(38))
    }

    @Test fun unknownTypesFallBack() {
        assertEquals(Maneuver.TYPE_UNKNOWN, ManeuverMapping.carType(0))
        assertEquals(Maneuver.TYPE_UNKNOWN, ManeuverMapping.carType(30))
        assertEquals(Maneuver.TYPE_UNKNOWN, ManeuverMapping.carType(-1))
    }

    @Test fun leftAndRightUseDifferentIcons() {
        assertNotEquals(ManeuverMapping.iconRes(Maneuver.TYPE_TURN_NORMAL_LEFT), ManeuverMapping.iconRes(Maneuver.TYPE_TURN_NORMAL_RIGHT))
        assertNotEquals(ManeuverMapping.iconRes(Maneuver.TYPE_U_TURN_LEFT), ManeuverMapping.iconRes(Maneuver.TYPE_U_TURN_RIGHT))
        assertEquals(R.drawable.ic_maneuver_straight, ManeuverMapping.iconRes(Maneuver.TYPE_UNKNOWN))
    }
}
