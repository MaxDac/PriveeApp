package com.privee.app.push

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A device-local Phoenix peer: no production server, account, or push infrastructure is used. */
internal class ListenerTestServer : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}"
    private val executor = Executors.newCachedThreadPool()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    val joined = AtomicInteger()

    init {
        executor.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: java.net.SocketException) {
                    if (server.isClosed) break else throw e
                }
                clients.add(client)
                executor.execute {
                    client.use {
                        try {
                            serve(it)
                        } catch (e: java.io.IOException) {
                            if (!it.isClosed) throw e
                        } finally {
                            clients.remove(it)
                            connections.remove(it)
                        }
                    }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) return
            header.append(next.toChar())
            check(header.length < 65_536)
        }
        val lines = header.toString().split("\r\n")
        val headers = lines.drop(1).filter { ':' in it }.associate {
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        }
        val output = socket.getOutputStream()
        if (headers["upgrade"]?.lowercase() != "websocket") {
            val bodyLength = headers["content-length"]?.toInt() ?: 0
            input.readFully(ByteArray(bodyLength))
            val path = lines.first().split(' ')[1]
            val body = when (path) {
                "/api/app/info" -> """{"service":"privee","api_version":1,"name":"Local listener test"}"""
                "/api/app/sessions" -> """{"token":"local-test","session":{"id":1,"session_name":"listener-test","is_quick":true}}"""
                else -> "{}"
            }.toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body)
            output.flush()
            return
        }
        val key = requireNotNull(headers["sec-websocket-key"])
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        val protocol = requireNotNull(headers["sec-websocket-protocol"]).substringBefore(',').trim()
        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: $protocol\r\n\r\n".toByteArray())
        output.flush()
        connections.add(socket)
        while (!socket.isClosed) {
            val first = input.read()
            if (first < 0) return
            val second = input.readUnsignedByte()
            val length = when (val short = second and 127) {
                126 -> input.readUnsignedShort()
                127 -> input.readLong().also { check(it in 0..65_536) }.toInt()
                else -> short
            }
            val mask = if (second and 128 != 0) ByteArray(4).also(input::readFully) else null
            val payload = ByteArray(length).also(input::readFully)
            if (mask != null) {
                payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            }
            if (first and 15 == 8) return
            if (first and 15 != 1) continue
            val frame = JSONArray(String(payload))
            val event = frame.getString(3)
            val response = JSONObject()
            if (event != "phx_join" && event != "heartbeat") response.put("error", "device_not_ready")
            write(output, JSONArray().put(frame.get(0)).put(frame.get(1)).put(frame.get(2))
                .put("phx_reply").put(JSONObject().put("status", "ok").put("response", response)))
            if (event == "phx_join" && frame.getString(2) == "session") joined.incrementAndGet()
        }
    }

    fun emit() {
        check(connections.isNotEmpty()) { "No app socket connected" }
        connections.forEach {
            write(it.getOutputStream(), JSONArray().put(JSONObject.NULL).put(JSONObject.NULL).put("session")
                .put("message_received").put(JSONObject().put("message_id", System.nanoTime()).put("from_session_name", "smoke-sender")))
        }
    }

    fun disconnect() {
        connections.toList().forEach(Socket::close)
    }

    private fun write(output: OutputStream, frame: JSONArray) {
        val bytes = frame.toString().toByteArray()
        synchronized(output) {
            output.write(0x81)
            if (bytes.size < 126) {
                output.write(bytes.size)
            } else {
                output.write(126)
                output.write(bytes.size shr 8)
                output.write(bytes.size and 255)
            }
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        server.close()
        clients.toList().forEach(Socket::close)
        executor.shutdownNow()
    }
}
