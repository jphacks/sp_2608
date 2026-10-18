package com.theacar.cabin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CabinNoticeTest {
    @Test
    fun windowNoticeKeepsTheEventId() {
        assertEquals(42L, CabinNotices.windowOpened(42L).id)
    }

    @Test
    fun noticeDescribesVentilationWithoutNamingTheCause() {
        val notice = CabinNotices.windowOpened(1L)
        assertEquals("空気を入れ替えますね", notice.spoken)
        assertEquals("窓を少し開けました", notice.detail)
        for (word in listOf("におい", "おなら", "ガス")) {
            assertFalse(word in notice.spoken || word in notice.detail)
        }
    }
}
