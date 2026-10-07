package com.privee.net

import okhttp3.OkHttpClient

/**
 * A client sharing [this] one's pool and settings that never follows redirects: a following request
 * would re-send the bearer token (also in `Sec-WebSocket-Protocol`) or, on a 307/308, a body holding
 * the recovery phrase, possibly to another host. A 3xx surfaces as an error instead.
 */
fun OkHttpClient.withoutRedirects(): OkHttpClient =
    if (!followRedirects && !followSslRedirects) this
    else newBuilder().followRedirects(false).followSslRedirects(false).build()
