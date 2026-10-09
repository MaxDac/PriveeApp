package com.privee.app.push

/**
 * The UnifiedPush endpoint of this installation, sent to the server for the
 * signed-in session. A new sign-in sends it again: the server forgets the
 * endpoint with the session (sign-out, or deletion after inactivity).
 */
class PushEndpointSync {
    @Volatile
    private var endpoint: String? = null

    /** A new endpoint from the distributor; [register] sends it for the signed-in session, if any. */
    suspend fun update(endpoint: String, register: (suspend (String) -> Unit)?) {
        this.endpoint = endpoint
        register?.let { send(endpoint, it) }
    }

    /** A new sign-in: sends the known endpoint, if any, for the new session. */
    suspend fun signedIn(register: suspend (String) -> Unit) {
        endpoint?.let { send(it, register) }
    }

    /** The endpoint is no longer valid (unregistered from the distributor). */
    fun forget() {
        endpoint = null
    }

    private suspend fun send(endpoint: String, register: suspend (String) -> Unit) {
        // A failure is retried by the next endpoint or sign-in; push is optional.
        runCatching { register(endpoint) }
    }
}
