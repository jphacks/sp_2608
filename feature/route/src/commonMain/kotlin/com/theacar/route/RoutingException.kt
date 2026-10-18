package com.theacar.route

enum class RoutingFailure { SEARCH, NETWORK, NO_ROUTE, LIMIT_REACHED, NOT_CONFIGURED, AUTHORIZATION, SCENIC_UNAVAILABLE }

class RoutingException(val failure: RoutingFailure) : Exception(failure.name)
