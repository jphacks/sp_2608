package com.theacar.auto

import com.theacar.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigateRequestTest {
    @Test
    fun readsCoordinates() {
        assertEquals(NavigateRequest.At(Coordinate(41.7737, 140.7264), null), navigateRequest("geo:41.7737,140.7264"))
    }

    @Test
    fun readsSearchText() {
        assertEquals(NavigateRequest.Search("大沼公園 駅"), navigateRequest("geo:0,0?q=%E5%A4%A7%E6%B2%BC%E5%85%AC%E5%9C%92+%E9%A7%85"))
    }

    @Test
    fun readsCoordinatesWithLabelInQuery() {
        assertEquals(NavigateRequest.At(Coordinate(41.7737, 140.7264), "函館駅"),
            navigateRequest("geo:0,0?q=41.7737,140.7264(%E5%87%BD%E9%A4%A8%E9%A7%85)"))
    }

    @Test
    fun ignoresEmptyAndInvalid() {
        assertNull(navigateRequest("geo:0,0"))
        assertNull(navigateRequest("geo:0,0?q="))
        assertNull(navigateRequest("geo:91,140"))
        assertNull(navigateRequest("https://example.com"))
    }
}
