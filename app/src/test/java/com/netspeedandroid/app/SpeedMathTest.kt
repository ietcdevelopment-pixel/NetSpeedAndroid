package com.netspeedandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMathTest {
    @Test
    fun medianIgnoresOrderingAndSelectsMiddleValue() {
        assertEquals(20.0, median(listOf(1000.0, 10.0, 20.0, 15.0, 25.0)), 0.0)
    }

    @Test
    fun throughputUsesDecimalMegabits() {
        assertEquals(80.0, megabitsPerSecond(10_000_000, 1_000_000_000), 0.0001)
        assertEquals(13.3333, megabitsPerSecond(4_000_000, 2_400_000_000), 0.0001)
    }

    @Test
    fun cloudflareServerTimingUsesRequestDuration() {
        assertEquals(
            2.5,
            cloudflareServerTimeMs("cache;desc=MISS, cfRequestDuration;dur=2.5"),
            0.0,
        )
    }

    @Test
    fun cloudflareServerTimingSumsSpeedPhases() {
        assertEquals(
            2.0,
            cloudflareServerTimeMs(
                "cfL4;desc=\"proto=TCP\", cfSpeedPrepare;dur=1.2, cfSpeedFinish;dur=0.8",
            ),
            0.0,
        )
    }

    @Test
    fun correctedDurationSubtractsServerTimeWithSafeFallback() {
        assertEquals(7_500_000L, correctedDurationNanos(10_000_000L, 2.5))
        assertEquals(10_000_000L, correctedDurationNanos(10_000_000L, 20.0))
    }

    @Test
    fun stagedTransfersKeepTrafficCaps() {
        assertEquals(WebViewSpeedTestClient.DOWNLOAD_BYTES, WebViewSpeedTestClient.DOWNLOAD_STAGES.sum())
        assertEquals(WebViewSpeedTestClient.UPLOAD_BYTES, WebViewSpeedTestClient.UPLOAD_STAGES.sum())
        assertEquals(1, WebViewSpeedTestClient.DOWNLOAD_STAGES.drop(1).distinct().size)
        assertEquals(1, WebViewSpeedTestClient.UPLOAD_STAGES.drop(1).distinct().size)
    }

    @Test
    fun uploadUsesFullRequestTimingAndSupportsCancellation() {
        val durationFunction = WebViewSpeedTestClient.PAGE
            .substringAfter("function uploadDurationMs")
            .substringBefore("function requireValidDuration")
        val uploadFunction = WebViewSpeedTestClient.PAGE.substringAfter("async function runUpload")

        assertFalse(durationFunction.contains("serverTimeMs"))
        assertTrue(durationFunction.contains("responseStart - timing.requestStart"))
        assertTrue(uploadFunction.contains("'/__up?bytes=' + bytes"))
        assertTrue(uploadFunction.contains("signal: controller.signal"))
    }
}
