package com.privee.app.data

import com.privee.net.ServerUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

private val SESSION_NAME = Regex("^[a-zA-Z0-9-]{1,72}$")

/** The website link of [sessionName] on the server at [serverUrl]. */
fun shareLink(serverUrl: String, sessionName: String) = "$serverUrl/share/$sessionName"

/**
 * The session name typed by the user: a bare name, or a share link of the
 * server at [serverUrl]. Links of other servers are refused (`null`), since
 * the same name there may belong to someone else.
 */
fun sessionNameFromInput(input: String, serverUrl: String): String? {
    val trimmed = input.trim()
    if (!trimmed.contains("://")) return trimmed.trim('/').takeIf(SESSION_NAME::matches)
    if (trimmed.startsWith("privee:", ignoreCase = true)) {
        return parseAppLink(trimmed)?.takeIf { it.server == serverUrl }?.name
    }

    val link = trimmed.toHttpUrlOrNull() ?: return null
    val server = serverUrl.toHttpUrlOrNull() ?: return null
    if (link.scheme != server.scheme || link.host != server.host || link.port != server.port) return null
    val prefix = server.pathSegments.filter(String::isNotEmpty) + "share"
    val segments = link.pathSegments.filter(String::isNotEmpty)
    if (segments.size != prefix.size + 1 || segments.take(prefix.size) != prefix) return null
    return segments.last().takeIf(SESSION_NAME::matches)
}

/** Whether [name] is a valid session name. */
fun isSessionName(name: String) = SESSION_NAME.matches(name)

/**
 * A conversation requested by an app link. [server] is the normalized URL of
 * the server [name] lives on, or `null` for legacy links that don't say.
 */
data class Invite(val name: String, val server: String?) {
    /** Whether the invite can open on the selected server without asking the user. */
    fun isFor(serverUrl: String) = server == serverUrl
}

/** The app link opening a conversation with [sessionName] on the server at [serverUrl]. */
fun appLink(serverUrl: String, sessionName: String) =
    "privee://share/$sessionName?server=${URLEncoder.encode(serverUrl, "UTF-8")}"

/**
 * Parses `privee://share/<name>?server=<url>` (or a legacy link without
 * `server`). Returns `null` for anything else, including an invalid server.
 */
fun parseAppLink(link: String): Invite? {
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    if (!"privee".equals(uri.scheme, ignoreCase = true) || uri.host != "share") return null
    val name = uri.path?.trim('/')?.takeIf(SESSION_NAME::matches) ?: return null
    val params = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { it.substringBefore('=') to it.substringAfter('=', "") }
    val servers = params.filter { it.first == "server" }.map { it.second }
    if (servers.size > 1) return null
    val raw = servers.singleOrNull() ?: return Invite(name, null)
    val server = runCatching {
        ServerUrl.normalize(URLDecoder.decode(raw, "UTF-8"), allowCleartext = true)
    }.getOrNull() ?: return null
    return Invite(name, server)
}

/**
 * How a server is shown to the user: its normalized address without `https://`
 * (`chat.example.org`, `example.org/privee`, `http://10.0.2.2:4000`). Unlike the
 * name the server reports about itself, it can't be spoofed by the server.
 */
fun serverLabel(serverUrl: String) = serverUrl.removePrefix("https://")
