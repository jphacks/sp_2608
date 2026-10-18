package com.theacar.memory.data

import com.theacar.memory.TripMemory
import com.theacar.model.Coordinate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TripMemoryApiTest {
    private val memory = TripMemory("trip", 0, 1_000, 100, 1, "函館", Coordinate(43.0, 141.0))

    @Test fun uploadsQuotedMultipartAndUsesTheSameBaseForQr() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/memory/api/trips", request.url.encodedPath)
            assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
            assertEquals("device", request.headers["X-Device-Id"])
            val body = (request.body as ByteArrayContent).bytes().decodeToString()
            assertTrue(body.contains("name=\"trip\"\r\n"))
            assertTrue(body.contains("name=\"photo\"; filename=\"photo.jpg\""))
            assertTrue(body.contains("\"destinationTitle\":\"函館\""))
            respond("{}", HttpStatusCode.Created)
        })
        try {
            val api = TripMemoryApi("https://example.com/memory/", "secret", "device", client)
            assertEquals("https://example.com/memory/t/trip", api.pageUrl("trip"))
            assertTrue(api.send(memory, byteArrayOf(1, 2, 3)))
        } finally { client.close() }
    }

    @Test fun rejectsUnsafeOrUnconfiguredEndpointsWithoutSending() = runTest {
        val client = HttpClient(MockEngine { error("must not send") })
        try {
            for (url in listOf("", "/relative", "not a url", "http://example.com", "https://user:pass@example.com", "https://example.com?key=x", "https://example.com#x")) {
                val api = TripMemoryApi(url, "secret", "device", client)
                assertNull(api.pageUrl("trip"), url)
                assertFalse(api.send(memory, null), url)
            }
            assertFalse(TripMemoryApi("https://example.com", "bad\ntoken", "device", client).send(memory, null))
        } finally { client.close() }
    }

    @Test fun rejectsInvalidIdsAndReportsServerRejection() = runTest {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized) })
        try {
            val api = TripMemoryApi("https://example.com", "secret", "device", client)
            assertFailsWith<IllegalArgumentException> { api.pageUrl("../other") }
            assertFalse(api.send(memory, null))
        } finally { client.close() }
    }
}
