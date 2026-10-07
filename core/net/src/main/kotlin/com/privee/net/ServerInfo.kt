package com.privee.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownServiceException

/** The reply of `GET /api/app/info`, which identifies a Privee server. */
data class ServerInfo(
    val service: String,
    val apiVersion: Int,
    val version: String?,
    val name: String?,
    val sourceUrl: String?,
) {
    companion object {
        const val SERVICE = "privee"

        /** The `api_version` values this client can talk to. */
        val SUPPORTED_API_VERSIONS = setOf(1)

        /**
         * Parses and checks an info reply: it must come from a Privee server
         * with a supported API version.
         */
        fun parse(body: String): ServerInfo {
            val json = runCatching { Json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw ServerCheckException(ServerProblem.NotPrivee) }
            val service = json.string("service")
            if (service != SERVICE) throw ServerCheckException(ServerProblem.NotPrivee)
            val apiVersion = (json["api_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw ServerCheckException(ServerProblem.NotPrivee)
            if (apiVersion !in SUPPORTED_API_VERSIONS) {
                throw ServerCheckException(ServerProblem.UnsupportedVersion, apiVersion)
            }
            return ServerInfo(
                service = service,
                apiVersion = apiVersion,
                version = json.string("version"),
                name = json.string("name")?.trim()?.takeIf { it.isNotEmpty() },
                sourceUrl = json.string("source_url"),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

/** Why a server address cannot be used. */
enum class ServerProblem {
    /** Not an `http(s)` address with a host. */
    InvalidUrl,

    /** An `http://` address where only HTTPS is allowed. */
    CleartextNotAllowed,

    /** The host could not be reached. */
    Unreachable,

    /** The host answered, but not as a Privee server. */
    NotPrivee,

    /** A Privee server with an API version this app does not support. */
    UnsupportedVersion,
}

class ServerCheckException(val problem: ServerProblem, val apiVersion: Int? = null, cause: Throwable? = null) :
    IOException("Server check failed: $problem", cause)

/** Normalisation of the server address typed by the user. */
object ServerUrl {
    /**
     * Returns the canonical base URL of [input], like `https://chat.example.org`
     * or `https://example.org/privee`: lowercase host, no default port, no
     * trailing slash, query or fragment. A missing scheme means `https://`;
     * `http://` is accepted only with [allowCleartext].
     */
    fun normalize(input: String, allowCleartext: Boolean): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) throw ServerCheckException(ServerProblem.InvalidUrl)
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") throw ServerCheckException(ServerProblem.InvalidUrl)
        if (scheme == "http" && !allowCleartext) throw ServerCheckException(ServerProblem.CleartextNotAllowed)

        val url = withScheme.toHttpUrlOrNull() ?: throw ServerCheckException(ServerProblem.InvalidUrl)
        if (url.host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) {
            throw ServerCheckException(ServerProblem.InvalidUrl)
        }
        val path = url.encodedPath.trimEnd('/')
        return url.newBuilder().encodedPath("/").build().toString().trimEnd('/') + path
    }
}

/**
 * Checks that [baseUrl] (already [normalized][ServerUrl.normalize]) is a
 * Privee server this app supports, by calling `GET /api/app/info`.
 */
suspend fun fetchServerInfo(baseUrl: String, client: OkHttpClient): ServerInfo {
    val base = baseUrl.toHttpUrlOrNull() ?: throw ServerCheckException(ServerProblem.InvalidUrl)
    val url = base.newBuilder().addPathSegments("api/app/info").build()
    val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
    // A redirect could hand the check to another host: the address must answer itself.
    val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    val body = try {
        withContext(Dispatchers.IO) {
            noRedirects.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ServerCheckException(ServerProblem.NotPrivee)
                response.body.string()
            }
        }
    } catch (e: ServerCheckException) {
        throw e
    } catch (e: UnknownServiceException) {
        // Android refuses cleartext traffic (e.g. an HTTPS server redirecting to HTTP).
        throw ServerCheckException(ServerProblem.CleartextNotAllowed, cause = e)
    } catch (e: IOException) {
        throw ServerCheckException(ServerProblem.Unreachable, cause = e)
    }
    return ServerInfo.parse(body)
}
