package com.netspeedandroid.app

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class SpeedTestClient {
    fun measurePingMs(samples: Int = PING_SAMPLES): Double {
        val timings = ArrayList<Double>(samples)
        repeat(samples) {
            checkNotInterrupted()
            val connection = open("/__down?bytes=1&ping=$it-${System.nanoTime()}")
            try {
                val started = System.nanoTime()
                requireSuccess(connection)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(32)
                    while (input.read(buffer) != -1) checkNotInterrupted()
                }
                timings += (System.nanoTime() - started) / 1_000_000.0
            } finally {
                connection.disconnect()
            }
        }
        return median(timings)
    }

    fun measureDownloadMbps(bytes: Long = DOWNLOAD_BYTES): Double {
        val connection = open("/__down?bytes=$bytes&download=${System.nanoTime()}")
        try {
            val started = System.nanoTime()
            requireSuccess(connection)
            var received = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkNotInterrupted()
                    val count = input.read(buffer)
                    if (count == -1) break
                    received += count
                }
            }
            val elapsed = System.nanoTime() - started
            if (received == 0L) throw IOException("Download returned no data")
            return megabitsPerSecond(received, elapsed)
        } finally {
            connection.disconnect()
        }
    }

    fun measureUploadMbps(bytes: Long = UPLOAD_BYTES): Double {
        val connection = open("/__up", "POST").apply {
            doOutput = true
            setFixedLengthStreamingMode(bytes)
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            val buffer = ByteArray(BUFFER_SIZE) { index -> (index % 251).toByte() }
            var sent = 0L
            val started = System.nanoTime()
            connection.outputStream.use { output ->
                while (sent < bytes) {
                    checkNotInterrupted()
                    val count = minOf(buffer.size.toLong(), bytes - sent).toInt()
                    output.write(buffer, 0, count)
                    sent += count
                }
                output.flush()
            }
            val elapsed = System.nanoTime() - started
            requireSuccess(connection)
            connection.inputStream.use { input ->
                val responseBuffer = ByteArray(BUFFER_SIZE)
                while (input.read(responseBuffer) != -1) checkNotInterrupted()
            }
            return megabitsPerSecond(sent, elapsed)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String = "GET"): HttpsURLConnection {
        return (URL(BASE_URL + path).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            defaultUseCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
        }
    }

    private fun requireSuccess(connection: HttpsURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.errorStream?.use { it.copyTo(DiscardingOutputStream) }
            throw IOException("Server returned HTTP $code")
        }
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private object DiscardingOutputStream : java.io.OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    companion object {
        const val DOWNLOAD_BYTES = 50_000_000L
        const val UPLOAD_BYTES = 10_000_000L
        private const val PING_SAMPLES = 7
        private const val BASE_URL = "https://speed.cloudflare.com"
        private const val USER_AGENT = "NetSpeedAndroid/1.0"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}
