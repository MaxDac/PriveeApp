package com.privee.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class SessionInfo(
    val id: Long,
    @SerialName("session_name") val sessionName: String,
    @SerialName("is_quick") val isQuick: Boolean,
)

@Serializable
data class AuthResult(val token: String, val session: SessionInfo)

/** A failed API call; [errors] holds per-field validation messages. */
open class ApiException(
    val status: Int,
    val error: String?,
    val errors: Map<String, List<String>> = emptyMap(),
) : IOException("HTTP $status${error?.let { ": $it" } ?: ""}")

class UnauthorizedException(error: String?) : ApiException(401, error)

/** Client of the Privee native app REST API (`/api/app`). */
class PriveeApi(baseUrl: String, client: OkHttpClient) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val client = client.withoutRedirects()

    suspend fun register(sessionName: String?, recoveryPhrase: String?, quick: Boolean): AuthResult {
        val body = buildJsonObject {
            sessionName?.takeIf { it.isNotBlank() }?.let { put("session_name", it.trim()) }
            if (quick) put("is_quick", true) else put("recovery_phrase", recoveryPhrase.orEmpty())
        }
        return decode(execute(request("sessions").post(body.toBody())))
    }

    suspend fun logIn(sessionName: String, recoveryPhrase: String?, quick: Boolean): AuthResult {
        val body = buildJsonObject {
            put("session_name", sessionName.trim())
            if (quick) put("is_quick", true) else put("recovery_phrase", recoveryPhrase.orEmpty())
        }
        return decode(execute(request("sessions/log_in").post(body.toBody())))
    }

    suspend fun session(token: String): SessionInfo {
        val reply = Json.parseToJsonElement(execute(request("session", token).get())).jsonObject
        return json.decodeFromJsonElement(SessionInfo.serializer(), reply.getValue("session"))
    }

    suspend fun logOut(token: String) {
        execute(request("session", token).delete())
    }

    suspend fun registerPush(token: String, endpoint: String) {
        val body = buildJsonObject { put("endpoint", endpoint) }
        execute(request("push", token).put(body.toBody()))
    }

    suspend fun unregisterPush(token: String) {
        execute(request("push", token).delete())
    }

    private fun request(path: String, token: String? = null): Request.Builder {
        val url = base.newBuilder().addPathSegments("api/app/$path").build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun JsonObject.toBody() = toString().toRequestBody(JSON)

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    private suspend fun execute(builder: Request.Builder): String {
        val response = client.newCall(builder.build()).await()
        return response.use {
            val body = withContext(Dispatchers.IO) { it.body.string() }
            if (it.isSuccessful) return@use body
            val reply = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            val error = reply?.get("error")?.jsonPrimitive?.content
            if (it.code == 401) throw UnauthorizedException(error)
            val errors = reply?.get("errors")?.jsonObject?.mapValues { (_, value) ->
                value.jsonArray.map { message -> message.jsonPrimitive.content }
            }.orEmpty()
            throw ApiException(it.code, error, errors)
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}
