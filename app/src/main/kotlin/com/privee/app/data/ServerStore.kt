package com.privee.app.data

import com.privee.signal.StateStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** The Privee server chosen by the user; [url] is a normalized base URL. */
data class ServerConfig(val url: String, val name: String?) {
    /** The server name, or its host when it has none. */
    val displayName: String get() = name ?: url.toHttpUrlOrNull()?.host ?: url

    /**
     * The directory, under the app storage, of everything tied to this server
     * (account, Signal keys and history), so servers never share state.
     */
    val directoryName: String get() = directoryName(url)

    companion object {
        fun directoryName(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Persists the selected [ServerConfig]. */
class ServerStore(private val storage: StateStorage) {
    fun load(): ServerConfig? = runCatching {
        val json = Json.parseToJsonElement(storage.load() ?: return null).jsonObject
        val url = (json["url"] as? JsonPrimitive)?.contentOrNull ?: return null
        ServerConfig(url, (json["name"] as? JsonPrimitive)?.contentOrNull)
    }.getOrNull()

    fun save(server: ServerConfig) {
        val json = buildJsonObject {
            put("url", server.url)
            put("name", server.name)
        }
        storage.save(json.toString())
    }

    fun clear() = storage.clear()
}
