package com.privee.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
