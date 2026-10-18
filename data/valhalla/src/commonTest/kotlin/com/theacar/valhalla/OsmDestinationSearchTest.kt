package com.theacar.valhalla

import com.theacar.route.RoutingException
import com.theacar.route.RoutingFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class OsmDestinationSearchTest {
    @Test fun connectionFailureIsReportedAsNetworkFailure() = runTest {
        val client = HttpClient(MockEngine { throw IllegalStateException("Simulated connection failure") })
        try {
            assertEquals(RoutingFailure.NETWORK, assertFailsWith<RoutingException> {
                OsmDestinationSearch("https://example.com", client).search("函館")
            }.failure)
        } finally {
            client.close()
        }
    }

    @Test fun cancellationIsNotConvertedToNetworkFailure() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("Cancelled") })
        try {
            assertFailsWith<CancellationException> {
                OsmDestinationSearch("https://example.com", client).search("函館")
            }
        } finally {
            client.close()
        }
    }

    @Test fun missingEndpointIsReportedAsConfigurationFailure() = runTest {
        assertEquals(RoutingFailure.NOT_CONFIGURED, assertFailsWith<RoutingException> {
            OsmDestinationSearch().search("函館")
        }.failure)
    }

    @Test fun authenticatedNameSearchParsesResults() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("小樽運河", it.url.parameters["q"])
            assertEquals("Bearer secret", it.headers[HttpHeaders.Authorization])
            respond("""[{"place_id":"way:1","display_name":"小樽運河","lat":"43.199","lon":"141.003"}]""")
        })
        val result = OsmDestinationSearch("https://routing.example.com", client) { "secret" }.search("小樽運河").single()
        assertEquals("小樽運河", result.title)
        assertEquals(43.199, result.coordinate.latitude)
        client.close()
    }

    @Test fun refusesTokenOnRemoteHttp() = runTest {
        val client = HttpClient(MockEngine { error("Must not send credentials") })
        assertEquals(RoutingFailure.AUTHORIZATION, assertFailsWith<RoutingException> {
            OsmDestinationSearch("http://example.com", client) { "secret" }.search("札幌駅")
        }.failure)
        client.close()
    }

    @Test fun reportsAuthorizationAndRateLimit() = runTest {
        for ((status, failure) in listOf(HttpStatusCode.Unauthorized to RoutingFailure.AUTHORIZATION,
            HttpStatusCode.TooManyRequests to RoutingFailure.LIMIT_REACHED)) {
            val client = HttpClient(MockEngine { respond("{}", status) })
            assertEquals(failure, assertFailsWith<RoutingException> {
                OsmDestinationSearch("https://example.com", client).search("札幌駅")
            }.failure)
            client.close()
        }
    }
}
