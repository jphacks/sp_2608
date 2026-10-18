package com.theacar.android.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowConfigurationTest {
    @Test
    fun theDeviceIsFoundByItsOwnName() {
        assertTrue(WindowConfiguration.Default.matches("stepwgn-window"))
        assertTrue(WindowConfiguration.Default.matches(" stepwgn-window "))
    }

    @Test
    fun unknownNamesAreNotRejected() {
        // 名前は走査応答に載るので、広告だけでは分からないことがある。
        // サービス UUID で既に絞り込んでいるため、名前が無いことを理由に捨てない。
        assertTrue(WindowConfiguration.Default.matches(null))
        assertTrue(WindowConfiguration.Default.matches(""))
        assertTrue(WindowConfiguration.Default.matches("   "))
    }

    @Test
    fun otherDevicesAreRejected() {
        listOf("stepwgn", "stepwgn-window-2", "freenove", "StepWgn-Window").forEach {
            assertFalse(WindowConfiguration.Default.matches(it), "この機器として扱ってはいけない: $it")
        }
    }

    @Test
    fun theUuidsMatchTheFirmware() {
        // car_v3_ble.ino の SERVICE_UUID / CMD_UUID / LOG_UUID と同じでなければつながらない。
        assertEquals("6e6b1f00-5f3a-4b7c-9d21-8c4f1a2b3c01", WindowConfiguration.SERVICE.toString())
        assertEquals("6e6b1f01-5f3a-4b7c-9d21-8c4f1a2b3c01", WindowConfiguration.COMMAND.toString())
        assertEquals("6e6b1f02-5f3a-4b7c-9d21-8c4f1a2b3c01", WindowConfiguration.LOG.toString())
    }

    @Test
    fun theThreeUuidsAreDistinct() {
        val uuids = setOf(WindowConfiguration.SERVICE, WindowConfiguration.COMMAND, WindowConfiguration.LOG)
        assertEquals(3, uuids.size)
    }
}
