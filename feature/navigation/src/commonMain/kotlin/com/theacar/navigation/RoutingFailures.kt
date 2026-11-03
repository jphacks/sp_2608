package com.theacar.navigation

import com.theacar.route.RoutingException
import com.theacar.route.RoutingFailure

internal fun RoutingException.navigationFailure(): NavigationFailure = when (failure) {
    RoutingFailure.SEARCH -> NavigationFailure.SEARCH
    RoutingFailure.NETWORK -> NavigationFailure.NETWORK
    RoutingFailure.NO_ROUTE -> NavigationFailure.NO_ROUTE
    // サーバーが 429 で断るのは混雑のとき。端末ごとの回数の上限（RoutingBudget）とは分けて知らせる
    RoutingFailure.LIMIT_REACHED -> NavigationFailure.BUSY
    RoutingFailure.NOT_CONFIGURED -> NavigationFailure.NOT_READY
    RoutingFailure.AUTHORIZATION -> NavigationFailure.AUTHORIZATION
    RoutingFailure.SCENIC_UNAVAILABLE -> NavigationFailure.SCENIC_UNAVAILABLE
}
