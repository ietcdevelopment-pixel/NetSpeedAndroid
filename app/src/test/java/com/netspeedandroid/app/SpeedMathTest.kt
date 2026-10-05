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
}
