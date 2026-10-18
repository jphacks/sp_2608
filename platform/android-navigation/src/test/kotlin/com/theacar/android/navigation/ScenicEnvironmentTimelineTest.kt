package com.theacar.android.navigation

import java.time.ZoneId
import kotlin.test.*

class ScenicEnvironmentTimelineTest {
    @Test fun passageClockUsesTheSelectedTimeZone() {
        assertEquals("17:30", environmentClock("2026-10-17T08:30:00+00:00", ZoneId.of("Asia/Tokyo")))
        assertEquals("時刻不明", environmentClock("unknown", ZoneId.of("Asia/Tokyo")))
    }

    @Test fun missingForecastIsNotPresentedAsClearSkies() {
        assertEquals("予報なし", environmentSkyLabel("unknown"))
        assertEquals("降水予報", environmentSkyLabel("precipitation"))
        assertTrue(environmentLightLabel("dusk").contains("推定"))
        assertTrue(bestSpotReason("forecast_unavailable").contains("取得できていません"))
        assertEquals("降雪予報", environmentSkyLabel("snowfall"))
        assertEquals("雷雨予報", environmentSkyLabel("thunderstorm"))
        assertEquals("気温 -5.2℃", environmentWeatherDetail("temperature_2m", -5.2))
        assertTrue(environmentWeatherDetail("snow_depth", .3).startsWith("地上の積雪"))
    }
}
