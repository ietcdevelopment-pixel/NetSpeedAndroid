package com.netspeedandroid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMathTest {
    @Test
    fun medianIgnoresOrderingAndSelectsMiddleValue() {
        assertEquals(20.0, median(listOf(1000.0, 10.0, 20.0, 15.0, 25.0)), 0.0)
    }

    @Test
    fun throughputUsesDecimalMegabits() {
        assertEquals(80.0, megabitsPerSecond(10_000_000, 1_000_000_000), 0.0001)
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
        assertEquals(SpeedTestClient.DOWNLOAD_BYTES, SpeedTestClient.DOWNLOAD_STAGES.sum())
        assertEquals(SpeedTestClient.UPLOAD_BYTES, SpeedTestClient.UPLOAD_STAGES.sum())
    }
}
