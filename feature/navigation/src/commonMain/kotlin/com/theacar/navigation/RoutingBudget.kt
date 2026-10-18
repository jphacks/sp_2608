package com.theacar.navigation

class RoutingBudget(
    private val nowMillis: () -> Long,
    private val maximumRequests: Int = 20,
    private val windowMillis: Long = 600_000,
) {
    private val requests = ArrayDeque<Long>()
    init { require(maximumRequests > 0 && windowMillis > 0) }

    fun consume() {
        val now = nowMillis()
        while (requests.isNotEmpty() && now - requests.first() >= windowMillis) requests.removeFirst()
        if (requests.size >= maximumRequests) throw NavigationException(NavigationFailure.LIMIT_REACHED)
        requests.addLast(now)
    }
}
