package com.theacar.auto

import androidx.car.app.model.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class StopActionTest {
    @Test fun staysVisibleOnHostsThatSupportPersistentActions() {
        val action = stopAction(5) {}
        assertEquals("終了", action.title.toString())
        assertEquals(Action.FLAG_IS_PERSISTENT, action.flags and Action.FLAG_IS_PERSISTENT)
    }

    @Test fun omitsPersistentFlagOnOlderHosts() {
        assertEquals(0, stopAction(4) {}.flags and Action.FLAG_IS_PERSISTENT)
    }
}
