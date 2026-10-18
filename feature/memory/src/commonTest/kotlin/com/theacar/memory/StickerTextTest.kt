package com.theacar.memory

import com.theacar.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StickerTextTest {
    // 2026-10-17 12:00 JST
    private val arrived = 1_792_206_000_000L

    @Test fun dateUsesLocalZoneAndLowercaseWeekday() {
        assertEquals("2026" to "10.17 sat.", dateLines(arrived, "Asia/Tokyo"))
        // 同じ瞬間でも日付線の西側では前日になる
        assertEquals("2026" to "10.16 fri.", dateLines(arrived, "Pacific/Honolulu"))
        assertEquals("2026" to "01.05 mon.", dateLines(1_767_582_000_000L, "Asia/Tokyo"))
    }

    @Test fun durationSplitsIntoHoursAndMinutes() {
        assertEquals(4 to 30, durationParts(4 * 3600 + 30 * 60))
        assertEquals(0 to 1, durationParts(45))
        assertEquals(1 to 0, durationParts(3599))
    }

    @Test fun distanceIsRoundedToKilometres() {
        assertEquals(358, distanceKm(357_600))
        assertEquals(0, distanceKm(400))
        assertEquals(1, distanceKm(500))
    }

    @Test fun membersAreTruncatedWithCount() {
        val names = listOf("Honoka", "Kokoro", "Masa", "tuu", "taisei", "harumi")
        assertEquals(names, memberLines(names))
        assertEquals(names + "+2", memberLines(names + listOf("ren", "sora")))
        assertEquals(listOf("A", "B"), addMember(addMember(emptyList(), " A "), "B"))
        assertEquals(listOf("A"), addMember(listOf("A"), "  "))
    }

    @Test fun addMemberKeepsWebLimits() {
        val twenty = (1..20).map { "m$it" }
        assertEquals(twenty, addMember(twenty, "m21"))
        assertEquals(listOf("a".repeat(40)), addMember(emptyList(), "a".repeat(41)))
    }

    @Test fun placeFallsBackToDestinationNameAndInputOverridesIt() {
        assertEquals("函館山", destinationShortName("函館山 · 北海道函館市函館山 · 山"))
        assertEquals("地図で選択した場所", destinationShortName("地図で選択した場所"))
        assertEquals("八雲町", placeNameInput("  八雲町 "))
        assertNull(placeNameInput("   "))
        assertEquals("a".repeat(40), placeNameInput("a".repeat(41)))
        val memory = TripMemory("id", arrived - 1000, arrived, 357_600, 16_200, "八雲パーキング", Coordinate(42.25, 140.26), timeZoneId = "Asia/Tokyo")
        assertEquals("八雲パーキング", memory.stickerText().place)
        assertEquals("北海道 八雲町", memory.copy(placeName = "北海道 八雲町").stickerText().place)
        assertEquals(StickerText("2026", "10.17 sat.", "八雲パーキング", emptyList(), "358", "4", "30"), memory.stickerText())
    }
}
