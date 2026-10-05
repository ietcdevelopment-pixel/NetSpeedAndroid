package com.netspeedandroid.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class SpeedTestClient(private val userAgent: String) {
    fun measurePingMs(samples: Int = PING_SAMPLES): Double {
        val timings = ArrayList<Double>(samples)
        repeat(samples) {
            checkNotInterrupted()
            val connection = open("/__down?bytes=0")
            try {
                val started = System.nanoTime()
                val code = connection.responseCode
                val headersReceived = System.nanoTime()
                if (code !in 200..299) throw IOException("Server returned HTTP $code")
                connection.inputStream.use { input ->
                    val buffer = ByteArray(32)
                    while (input.read(buffer) != -1) checkNotInterrupted()
                }
                val elapsed = headersReceived - started
                val serverTime = cloudflareServerTimeMs(serverTimingHeader(connection))
                timings += correctedDurationNanos(elapsed, serverTime) / 1_000_000.0
            } finally {
                connection.disconnect()
            }
        }
        return median(timings)
    }

    private fun open(path: String): HttpURLConnection {
        return (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            defaultUseCaches = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun serverTimingHeader(connection: HttpURLConnection): String {
        return connection.headerFields
            .filterKeys { it?.equals("Server-Timing", ignoreCase = true) == true }
            .values
            .flatten()
            .joinToString(",")
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    companion object {
        private const val PING_SAMPLES = 7
        private const val BASE_URL = "https://speed.cloudflare.com"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
