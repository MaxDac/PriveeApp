package com.privee.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerUrlTest {
    private fun problem(input: String, allowCleartext: Boolean = false) =
        assertThrows<ServerCheckException> { ServerUrl.normalize(input, allowCleartext) }.problem

    @Test
    fun `normalizes server addresses`() {
        assertEquals("https://chat.example.org", ServerUrl.normalize("chat.example.org", false))
        assertEquals("https://chat.example.org", ServerUrl.normalize("  HTTPS://Chat.Example.org/ ", false))
        assertEquals("https://chat.example.org", ServerUrl.normalize("https://chat.example.org:443", false))
        assertEquals("https://chat.example.org:8443", ServerUrl.normalize("chat.example.org:8443", false))
        assertEquals("https://example.org/privee", ServerUrl.normalize("https://example.org/privee/", false))
        assertEquals("http://10.0.2.2:4000", ServerUrl.normalize("http://10.0.2.2:4000/", true))
    }

    @Test
    fun `rejects invalid addresses`() {
        assertEquals(ServerProblem.InvalidUrl, problem(""))
        assertEquals(ServerProblem.InvalidUrl, problem("   "))
        assertEquals(ServerProblem.InvalidUrl, problem("not a url"))
        assertEquals(ServerProblem.InvalidUrl, problem("ftp://example.org"))
        assertEquals(ServerProblem.InvalidUrl, problem("ws://example.org"))
        assertEquals(ServerProblem.InvalidUrl, problem("https://user:pass@example.org"))
        assertEquals(ServerProblem.InvalidUrl, problem("https://example.org/?a=b"))
        assertEquals(ServerProblem.InvalidUrl, problem("https://example.org/#top"))
        assertEquals(ServerProblem.InvalidUrl, problem("https://"))
    }

    @Test
    fun `allows cleartext only when permitted`() {
        assertEquals(ServerProblem.CleartextNotAllowed, problem("http://example.org"))
        assertEquals(ServerProblem.CleartextNotAllowed, problem("HTTP://10.0.2.2:4000"))
        assertEquals("http://example.org", ServerUrl.normalize("http://example.org", allowCleartext = true))
    }
}

class ServerInfoTest {
    private fun problem(body: String) = assertThrows<ServerCheckException> { ServerInfo.parse(body) }

    @Test
    fun `parses the info of a Privee server`() {
        val info = ServerInfo.parse(
            """{"service":"privee","api_version":1,"version":"0.1.0","name":"My Privee","source_url":"https://github.com/MaxDac/Privee","extra":true}""",
        )
        assertEquals(ServerInfo("privee", 1, "0.1.0", "My Privee", "https://github.com/MaxDac/Privee"), info)

        val unnamed = ServerInfo.parse("""{"service":"privee","api_version":1,"version":"0.1.0","name":null}""")
        assertNull(unnamed.name)
        assertNull(unnamed.sourceUrl)
        assertNull(ServerInfo.parse("""{"service":"privee","api_version":1,"name":"  "}""").name)
    }

    @Test
    fun `rejects other services and unsupported versions`() {
        assertEquals(ServerProblem.NotPrivee, problem("""{"service":"other","api_version":1}""").problem)
        assertEquals(ServerProblem.NotPrivee, problem("""{"api_version":1}""").problem)
        assertEquals(ServerProblem.NotPrivee, problem("""{"service":"privee"}""").problem)
        assertEquals(ServerProblem.NotPrivee, problem("""{"service":"privee","api_version":"1"}""").problem)
        assertEquals(ServerProblem.NotPrivee, problem("<html>hello</html>").problem)
        assertEquals(ServerProblem.NotPrivee, problem("[]").problem)

        val unsupported = problem("""{"service":"privee","api_version":2}""")
        assertEquals(ServerProblem.UnsupportedVersion, unsupported.problem)
        assertEquals(2, unsupported.apiVersion)
    }
}

class FetchServerInfoTest {
    private val client = OkHttpClient()

    private fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        return try {
            runBlocking { block(server) }
        } finally {
            server.close()
        }
    }

    @Test
    fun `fetches the server info`() = withServer { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Cache-Control", "no-store")
                .body("""{"service":"privee","api_version":1,"version":"0.1.0","name":null,"source_url":"https://github.com/MaxDac/Privee"}""")
                .build(),
        )
        val base = ServerUrl.normalize(server.url("/sub/").toString(), allowCleartext = true)
        val info = fetchServerInfo(base, client)
        assertEquals(1, info.apiVersion)
        assertNull(info.name)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/sub/api/app/info", request.url.encodedPath)
    }

    @Test
    fun `reports hosts that are not Privee servers`() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())
        val base = server.url("/").toString()
        val notFound = assertThrows<ServerCheckException> { runBlocking { fetchServerInfo(base, client) } }
        assertEquals(ServerProblem.NotPrivee, notFound.problem)

        server.enqueue(MockResponse.Builder().code(200).body("""{"service":"privee","api_version":99}""").build())
        val newer = assertThrows<ServerCheckException> { runBlocking { fetchServerInfo(base, client) } }
        assertEquals(ServerProblem.UnsupportedVersion, newer.problem)
    }

    @Test
    fun `reports unreachable hosts`() {
        val server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        server.close()
        val unreachable = assertThrows<ServerCheckException> { runBlocking { fetchServerInfo(base, client) } }
        assertEquals(ServerProblem.Unreachable, unreachable.problem)
    }
}
