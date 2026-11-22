package com.wisetv.mobile.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class Http {
    /** For API calls, playlists and guide downloads. */
    val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** For recordings: a stalled stream should fail fast so the recorder reconnects. */
    val stream: OkHttpClient = api.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** For the player. */
    val player: OkHttpClient = api.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun ua(custom: String?): String = custom?.takeIf { it.isNotBlank() } ?: DEFAULT_UA

    fun request(url: String, userAgent: String?): Request =
        Request.Builder().url(url).header("User-Agent", ua(userAgent)).build()

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 9; AFT) AppleWebKit/537.36 WiseTV/1.0"

        /** Wraps the stream in a gunzip decoder when it starts with the gzip magic bytes. */
        fun maybeGunzip(input: InputStream): InputStream {
            val b = BufferedInputStream(input, 64 * 1024)
            b.mark(4)
            val b1 = b.read()
            val b2 = b.read()
            b.reset()
            return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(b, 64 * 1024) else b
        }
    }
}
