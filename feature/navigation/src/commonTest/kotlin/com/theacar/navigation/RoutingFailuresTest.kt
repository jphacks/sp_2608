package com.theacar.navigation

import com.theacar.route.RoutingException
import com.theacar.route.RoutingFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingFailuresTest {
    @Test fun serverRejectionIsBusyNotTheDeviceLimit() {
        assertEquals(NavigationFailure.BUSY, RoutingException(RoutingFailure.LIMIT_REACHED).navigationFailure())
    }
}
