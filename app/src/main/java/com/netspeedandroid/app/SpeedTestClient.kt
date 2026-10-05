package com.netspeedandroid.app

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class SpeedTestClient {
    fun measurePingMs(samples: Int = PING_SAMPLES): Double {
        val timings = ArrayList<Double>(samples)
        repeat(samples) { sample ->
            checkNotInterrupted()
            val (connection, started) = openSuccessfulGet(
                "/__down?bytes=0&ping=$sample-${System.nanoTime()}",
            )
            try {
                val headersReceived = System.nanoTime()
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

    fun measureDownloadMbps(
        onProgress: ((bytesDone: Long, totalBytes: Long, currentMbps: Double) -> Unit)? = null,
    ): Double {
        val results = ArrayList<Double>(DOWNLOAD_STAGES.size - 1)
        var totalReceived = 0L
        var lastProgressAt = 0L

        DOWNLOAD_STAGES.forEachIndexed { index, bytes ->
            val (connection, started) = openSuccessfulGet(
                "/__down?bytes=$bytes&download=$index-${System.nanoTime()}",
            )
            try {
                val serverTime = cloudflareServerTimeMs(serverTimingHeader(connection))
                var received = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkNotInterrupted()
                        val count = input.read(buffer)
                        if (count == -1) break
                        received += count
                        totalReceived += count
                        val now = System.nanoTime()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
                            onProgress?.invoke(
                                totalReceived,
                                DOWNLOAD_BYTES,
                                megabitsPerSecond(
                                    received,
                                    correctedDurationNanos(now - started, serverTime),
                                ),
                            )
                            lastProgressAt = now
                        }
                    }
                }
                val elapsed = correctedDurationNanos(System.nanoTime() - started, serverTime)
                if (received == 0L) throw IOException("Download returned no data")
                val speed = megabitsPerSecond(received, elapsed)
                if (index > 0) results += speed
                onProgress?.invoke(totalReceived, DOWNLOAD_BYTES, speed)
            } finally {
                connection.disconnect()
            }
        }
        return median(results)
    }

    fun measureUploadMbps(
        onProgress: ((bytesDone: Long, totalBytes: Long, currentMbps: Double) -> Unit)? = null,
    ): Double {
        val results = ArrayList<Double>(UPLOAD_STAGES.size - 1)
        val buffer = ByteArray(BUFFER_SIZE) { index -> (index % 251).toByte() }
        var totalSent = 0L
        var lastProgressAt = 0L

        UPLOAD_STAGES.forEachIndexed { index, bytes ->
            val connection = open("/__up", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(bytes)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            try {
                var stageSent = 0L
                val started = System.nanoTime()
                connection.outputStream.use { output ->
                    while (stageSent < bytes) {
                        checkNotInterrupted()
                        val count = minOf(buffer.size.toLong(), bytes - stageSent).toInt()
                        output.write(buffer, 0, count)
                        stageSent += count
                        totalSent += count
                        val now = System.nanoTime()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
                            onProgress?.invoke(
                                totalSent,
                                UPLOAD_BYTES,
                                megabitsPerSecond(stageSent, now - started),
                            )
                            lastProgressAt = now
                        }
                    }
                    output.flush()
                }
                val code = connection.responseCode
                val headersReceived = System.nanoTime()
                requireSuccess(connection, code)
                val serverTime = cloudflareServerTimeMs(serverTimingHeader(connection))
                val elapsed = correctedDurationNanos(headersReceived - started, serverTime)
                connection.inputStream.use { input ->
                    val responseBuffer = ByteArray(BUFFER_SIZE)
                    while (input.read(responseBuffer) != -1) checkNotInterrupted()
                }
                val speed = megabitsPerSecond(stageSent, elapsed)
                if (index > 0) results += speed
                onProgress?.invoke(totalSent, UPLOAD_BYTES, speed)
            } finally {
                connection.disconnect()
            }
        }
        return median(results)
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

    private fun openSuccessfulGet(path: String): Pair<HttpsURLConnection, Long> {
        repeat(MAX_ATTEMPTS) { attempt ->
            checkNotInterrupted()
            val connection = open(path)
            val started = System.nanoTime()
            try {
                val code = connection.responseCode
                if (code in RETRYABLE_CODES && attempt < MAX_ATTEMPTS - 1) {
                    drainError(connection)
                    val delayMs = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1_000L)
                        ?.coerceIn(0L, MAX_RETRY_DELAY_MS)
                        ?: DEFAULT_RETRY_DELAY_MS
                    connection.disconnect()
                    Thread.sleep(delayMs)
                } else {
                    requireSuccess(connection, code)
                    return connection to started
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Request failed after retries")
    }

    private fun requireSuccess(connection: HttpsURLConnection, code: Int) {
        if (code !in 200..299) {
            drainError(connection)
            throw IOException("Server returned HTTP $code")
        }
    }

    private fun drainError(connection: HttpsURLConnection) {
        connection.errorStream?.use { it.copyTo(DiscardingOutputStream) }
    }

    private fun serverTimingHeader(connection: HttpsURLConnection): String {
        return connection.headerFields
            .filterKeys { it?.equals("Server-Timing", ignoreCase = true) == true }
            .values
            .flatten()
            .joinToString(",")
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
        internal val DOWNLOAD_STAGES = longArrayOf(1_000_000L, 9_000_000L, 15_000_000L, 25_000_000L)
        internal val UPLOAD_STAGES = longArrayOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L)
        private const val PING_SAMPLES = 7
        private const val BASE_URL = "https://speed.cloudflare.com"
        private const val USER_AGENT = "NetSpeedAndroid/1.0"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private const val MAX_ATTEMPTS = 3
        private const val DEFAULT_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 5_000L
        private val RETRYABLE_CODES = setOf(429, 502, 503, 504)
    }
}
