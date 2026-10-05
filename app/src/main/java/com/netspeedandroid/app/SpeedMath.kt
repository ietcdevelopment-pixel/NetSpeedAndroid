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

internal fun cloudflareServerTimeMs(header: String?): Double {
    if (header.isNullOrBlank()) return 0.0
    val requestDuration = Regex(
        """(?:^|,\s*)cfReq(?:uest)?Dur(?:ation)?;\s*dur=([0-9.]+)""",
        RegexOption.IGNORE_CASE,
    ).find(header)?.groupValues?.get(1)?.toDoubleOrNull()
    if (requestDuration != null) return requestDuration

    return Regex(
        """(?:^|,\s*)cfSpeed[a-zA-Z]*;\s*dur=([0-9.]+)""",
        RegexOption.IGNORE_CASE,
    ).findAll(header).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
}

internal fun correctedDurationNanos(elapsedNanos: Long, serverTimeMs: Double): Long {
    require(elapsedNanos > 0)
    val corrected = elapsedNanos - (serverTimeMs * 1_000_000.0).toLong()
    return if (corrected >= 1_000_000L) corrected else elapsedNanos
}
