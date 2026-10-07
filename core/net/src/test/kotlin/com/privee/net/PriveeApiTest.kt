package com.privee.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PriveeApiTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var api: PriveeApi

    @BeforeEach
    fun start() {
        server.start()
        api = PriveeApi(server.url("/").toString(), client)
    }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `registers a quick session`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .body("""{"token":"t","session":{"id":7,"session_name":"blue-fox","is_quick":true}}""")
                .build(),
        )
        val result = api.register(null, null, quick = true)
        assertEquals(AuthResult("t", SessionInfo(7, "blue-fox", true)), result)

        val request = server.takeRequest()
        assertEquals("/api/app/sessions", request.url.encodedPath)
        assertEquals("""{"is_quick":true}""", request.body?.utf8())
    }

    @Test
    fun `reports validation errors and invalid tokens`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(422)
                .body("""{"error":"invalid","errors":{"session_name":["has already been taken"]}}""")
                .build(),
        )
        val invalid = assertThrows<ApiException> { runBlocking { api.register("taken", "phrase", quick = false) } }
        assertEquals(mapOf("session_name" to listOf("has already been taken")), invalid.errors)

        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        assertThrows<UnauthorizedException> { runBlocking { api.session("bad") } }
        server.takeRequest()
        assertEquals("Bearer bad", server.takeRequest().headers["Authorization"])
    }
    @Test
    fun `does not follow redirects that would re-send the recovery phrase`() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            elsewhere.enqueue(MockResponse.Builder().code(200).body("""{"token":"stolen"}""").build())
            server.enqueue(
                MockResponse.Builder().code(307).addHeader("Location", elsewhere.url("/steal").toString()).build(),
            )
            val redirected = assertThrows<ApiException> {
                runBlocking { api.logIn("blue-fox", "correct horse battery staple", quick = false) }
            }
            assertEquals(307, redirected.status)
            assertEquals(1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.close()
        }
    }
}

class PhoenixSocketTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() {
        scope.cancel()
        server.close()
    }

    /** Echoes joins and pushes as `ok` replies and pushes one event after a join. */
    private fun phoenixServer() = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Json.parseToJsonElement(text).jsonArray
            val topic = frame[2].jsonPrimitive.content
            val event = frame[3].jsonPrimitive.content
            val response = when (event) {
                "phx_join" -> buildJsonObject { put("peer_id", 2) }
                else -> buildJsonObject { put("echo", event) }
            }
            webSocket.send(
                buildJsonArray {
                    add(frame[0])
                    add(frame[1])
                    add(JsonPrimitive(topic))
                    add(JsonPrimitive("phx_reply"))
                    add(
                        buildJsonObject {
                            put("status", "ok")
                            put("response", response)
                        },
                    )
                }.toString(),
            )
            if (event == "phx_join") {
                webSocket.send(
                    JsonArray(
                        listOf(JsonNull, JsonNull, JsonPrimitive(topic), JsonPrimitive("new_message"), buildJsonObject { put("id", "m1") }),
                    ).toString(),
                )
            }
        }
    }

    @Test
    fun `joins, pushes and receives events`() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(phoenixServer()).build())
        server.start()

        val socket = PhoenixSocket(server.url("/").toString(), "secret", OkHttpClient(), scope)
        val channel = socket.channel("chat:peer")
        val firstEvent = scope.async(start = CoroutineStart.UNDISPATCHED) { channel.events.first() }
        socket.connect()

        withTimeout(10_000) {
            assertEquals(JsonPrimitive(2), channel.join()["peer_id"])
            assertEquals(JsonPrimitive("open_conversation"), channel.push("open_conversation")["echo"])
            assertEquals("new_message", firstEvent.await().event)
        }

        val request = server.takeRequest()
        assertEquals("/app/socket/websocket", request.url.encodedPath)
        assertEquals("2.0.0", request.url.queryParameter("vsn"))
        assertTrue(request.headers["Sec-WebSocket-Protocol"]!!.contains("base64url.bearer.phx.c2VjcmV0"))
        socket.disconnect()
    }

    @Test
    fun `does not follow a redirect of the upgrade, which would leak the token`() = runBlocking {
        val elsewhere = MockWebServer().apply { start() }
        try {
            elsewhere.enqueue(MockResponse.Builder().webSocketUpgrade(phoenixServer()).build())
            server.enqueue(
                MockResponse.Builder().code(302).addHeader("Location", elsewhere.url("/app/socket/websocket").toString()).build(),
            )
            server.start()

            val socket = PhoenixSocket(
                server.url("/").toString(), "secret", OkHttpClient(), scope, reconnectDelaysMs = listOf(60_000),
            )
            socket.connect()
            withTimeout(10_000) { server.takeRequest() }
            delay(500)

            assertEquals(false, socket.connected.value)
            assertEquals(0, elsewhere.requestCount)
            socket.disconnect()
        } finally {
            elsewhere.close()
        }
    }
}
