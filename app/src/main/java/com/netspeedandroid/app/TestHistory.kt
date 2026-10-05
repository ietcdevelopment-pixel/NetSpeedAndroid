package com.netspeedandroid.app

data class TestHistoryEntry(
    val timestampMillis: Long,
    val network: String,
    val pingMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
)

object TestHistory {
    const val MAX_ENTRIES = 10

    fun add(entries: List<TestHistoryEntry>, entry: TestHistoryEntry): List<TestHistoryEntry> =
        (listOf(entry) + entries).take(MAX_ENTRIES)

    fun encode(entries: List<TestHistoryEntry>): String = entries.joinToString("\n") {
        listOf(it.timestampMillis, it.network, it.pingMs, it.downloadMbps, it.uploadMbps)
            .joinToString("\t")
    }

    fun decode(value: String): List<TestHistoryEntry> = value.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 5) return@mapNotNull null
        val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
        val ping = parts[2].toDoubleOrNull() ?: return@mapNotNull null
        val download = parts[3].toDoubleOrNull() ?: return@mapNotNull null
        val upload = parts[4].toDoubleOrNull() ?: return@mapNotNull null
        TestHistoryEntry(timestamp, parts[1], ping, download, upload)
    }.take(MAX_ENTRIES).toList()
}
