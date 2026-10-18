package com.theacar.navigation

import com.theacar.route.RoutingException
import com.theacar.route.RoutingFailure

internal fun RoutingException.navigationFailure(): NavigationFailure = when (failure) {
    RoutingFailure.SEARCH -> NavigationFailure.SEARCH
    RoutingFailure.NETWORK -> NavigationFailure.NETWORK
    RoutingFailure.NO_ROUTE -> NavigationFailure.NO_ROUTE
    RoutingFailure.LIMIT_REACHED -> NavigationFailure.LIMIT_REACHED
    RoutingFailure.NOT_CONFIGURED -> NavigationFailure.NOT_READY
    RoutingFailure.AUTHORIZATION -> NavigationFailure.AUTHORIZATION
    RoutingFailure.SCENIC_UNAVAILABLE -> NavigationFailure.SCENIC_UNAVAILABLE
}
