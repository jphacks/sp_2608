package com.theacar.android.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowCameraTest {
    @Test fun zoomsInWhenStoppedAndOutWhenFast() {
        assertEquals(FollowCamera.NEAR, FollowCamera.zoom(0f))
        assertEquals(FollowCamera.FAR, FollowCamera.zoom(FollowCamera.FAST_METERS_PER_SECOND))
        // 速いほど引く
        assertTrue(FollowCamera.zoom(20f) < FollowCamera.zoom(10f))
        // 巡航より速くても引きすぎない
        assertEquals(FollowCamera.FAR, FollowCamera.zoom(60f))
    }

    @Test fun keepsTheOldViewWhenTheSpeedIsUnknown() {
        assertEquals(FollowCamera.DEFAULT, FollowCamera.zoom(null))
        assertEquals(FollowCamera.DEFAULT, FollowCamera.zoom(Float.NaN))
        assertEquals(0.0, FollowCamera.tilt(null))
    }

    @Test fun tiltsOnlyOnceTheCarIsMoving() {
        assertEquals(0.0, FollowCamera.tilt(0f))
        assertEquals(0.0, FollowCamera.tilt(FollowCamera.TILT_METERS_PER_SECOND - 1f))
        assertEquals(FollowCamera.TILT, FollowCamera.tilt(FollowCamera.TILT_METERS_PER_SECOND))
    }
}
