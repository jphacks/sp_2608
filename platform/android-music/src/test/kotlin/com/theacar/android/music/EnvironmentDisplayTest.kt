package com.theacar.android.music

import com.theacar.location.*
import com.theacar.model.Coordinate
import java.time.ZoneId
import kotlin.test.*

class EnvironmentDisplayTest {
    private val fix = LocationFix(Coordinate(43.061234, 141.354321), 25f, 10_000)

    @Test fun formatsCoordinatesWithoutStringTruncation() {
        assertEquals("43.06123, 141.35432", fix.coordinate.display())
        assertEquals("43.06, 141.35", fix.coordinate.display(2))
        assertEquals("-0.00012, -179.99999", Coordinate(-0.000123, -179.999987).display())
    }

    @Test fun timestampShowsItsTimezone() {
        assertEquals("01/01 09:00:00 JST", displayTime(0, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun oldFixIsNotPresentedAsCurrentEvenIfTheStatusWasAvailable() {
        val state = LocationState(LocationStatus.AVAILABLE, fix)
        assertEquals("位置情報を取得済み", state.displayStatus(20_000))
        assertEquals("古い位置情報・選曲には使用できません", state.displayStatus(131_000))
        assertEquals("古い位置情報・選曲には使用できません", state.displayStatus(9_000))
    }

    @Test fun missingPermissionsAndInsufficientAccuracyAreExplicit() {
        assertEquals("位置情報の許可が必要", LocationState(LocationStatus.PERMISSION_REQUIRED, fix).displayStatus(20_000))
        assertEquals("端末の位置情報がオフ", LocationState(LocationStatus.DISABLED, fix).displayStatus(20_000))
        assertEquals("精度が不足・選曲には使用できません", LocationState(LocationStatus.AVAILABLE, fix.copy(accuracyMeters = 600f)).displayStatus(20_000))
        assertEquals("現在地は未取得", LocationState().displayStatus(20_000))
    }

    @Test fun ageUsesElapsedTimeAndRejectsFutureFixes() {
        assertEquals(12L, locationAgeSeconds(22_999, 10_000))
        assertNull(locationAgeSeconds(9_999, 10_000))
    }
}
