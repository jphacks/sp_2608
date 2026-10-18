package com.theacar.auto

import kotlin.test.Test
import kotlin.test.assertEquals

class CarRequestQrTest {
    @Test fun theQrTakesAboutHalfOfTheShorterVisibleSide() {
        assertEquals(300, carQrSide(1200, 600, 2f))
    }

    @Test fun theQrStaysReadableOnSmallScreensAndModestOnLargeOnes() {
        // 小さい画面でも 120dp は確保し、大きい画面でも 260dp までにとどめる
        assertEquals(240, carQrSide(500, 300, 2f))
        assertEquals(520, carQrSide(3000, 2000, 2f))
        // 見えている範囲より大きくはしない
        assertEquals(200, carQrSide(400, 200, 2f))
    }
}
