package com.theacar.location

import com.theacar.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocationUseTest {
    private fun state(accuracy: Float, time: Long, status: LocationStatus = LocationStatus.AVAILABLE) =
        LocationState(status, LocationFix(Coordinate(43.061, 141.354), accuracy, time))

    @Test fun previewAcceptsBoundaryAndRejectsExpiredOrInaccurateFix() {
        assertNotNull(state(500f, 1_000).usableFix(LocationUse.ROUTE_PREVIEW, 121_000))
        assertNull(state(501f, 1_000).usableFix(LocationUse.ROUTE_PREVIEW, 1_000))
        assertNull(state(500f, 1_000).usableFix(LocationUse.ROUTE_PREVIEW, 121_001))
    }

    @Test fun guidanceKeepsPreciseFreshRequirements() {
        assertNotNull(state(40f, 1_000).usableFix(LocationUse.GUIDANCE, 11_000))
        assertNull(state(41f, 1_000).usableFix(LocationUse.GUIDANCE, 1_000))
        assertNull(state(40f, 1_000).usableFix(LocationUse.GUIDANCE, 11_001))
    }

    @Test fun guidanceStartAcceptsApproximateRecentFix() {
        assertNotNull(state(100f, 1_000).usableFix(LocationUse.GUIDANCE_START, 31_000))
        assertNull(state(101f, 1_000).usableFix(LocationUse.GUIDANCE_START, 1_000))
        assertNull(state(100f, 1_000).usableFix(LocationUse.GUIDANCE_START, 31_001))
        assertNull(state(100f, 1_000).usableFix(LocationUse.GUIDANCE, 1_000))
    }

    @Test fun previewCanUseStaleFixButNotDisabledOrUnpermittedLocation() {
        assertNotNull(state(100f, 1_000, LocationStatus.STALE).usableFix(LocationUse.ROUTE_PREVIEW, 20_000))
        for (status in listOf(LocationStatus.DISABLED, LocationStatus.PERMISSION_REQUIRED, LocationStatus.WAITING)) {
            assertNull(state(5f, 1_000, status).usableFix(LocationUse.ROUTE_PREVIEW, 1_000))
        }
    }

    @Test fun rejectsMissingAndFutureFix() {
        assertNull(LocationState().usableFix(LocationUse.ROUTE_PREVIEW, 1_000))
        assertNull(state(5f, 2_000).usableFix(LocationUse.ROUTE_PREVIEW, 1_000))
    }
}
