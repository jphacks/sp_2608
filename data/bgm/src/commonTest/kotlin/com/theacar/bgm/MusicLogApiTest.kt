package com.theacar.bgm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MusicLogApiTest {
    @Test fun postsTheRecordWithTheWebToken() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("https://web.example/api/music-logs", it.url.toString())
            assertEquals("Bearer web", it.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.Created)
        })
        assertTrue(MusicLogApi("https://web.example/", "web", client).send("{}"))
        client.close()
    }

    @Test fun reportsRejection() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        assertFalse(MusicLogApi("https://web.example", "", client).send("{}"))
        client.close()
    }

    @Test fun refusesToSendTheTokenOverPlainHttp() = runTest {
        val client = HttpClient(MockEngine { fail("must not be sent") })
        assertFailsWith<IllegalStateException> { MusicLogApi("http://web.example", "web", client).send("{}") }
        assertTrue(MusicLogApi("http://10.0.2.2:3000", "web", HttpClient(MockEngine { respond("") })).send("{}"))
        assertFalse(MusicLogApi(" ", "web", client).configured)
        client.close()
    }
}
