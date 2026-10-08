package com.example.torrentstream

/** Hard-coded defaults for now. Values of 0 for limits mean "unlimited". */
data class AppSettings(
    val aheadBytes: Long = 48L shl 20,      // size of the deadline-driven "play now" window
    val maxConnections: Int = 100,
    val downloadLimitBps: Int = 0,
    val uploadLimitBps: Int = 0,
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 60_000,
    val startBufferMs: Int = 2_500,
    val rebufferMs: Int = 5_000,
    val metadataTimeoutMs: Long = 90_000,
)
