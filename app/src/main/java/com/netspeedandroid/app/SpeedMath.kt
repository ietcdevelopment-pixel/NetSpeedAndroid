package com.netspeedandroid.app

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

internal fun megabitsPerSecond(bytes: Long, durationNanos: Long): Double {
    require(bytes >= 0 && durationNanos > 0)
    return bytes * 8.0 / (durationNanos / 1_000_000_000.0) / 1_000_000.0
}
