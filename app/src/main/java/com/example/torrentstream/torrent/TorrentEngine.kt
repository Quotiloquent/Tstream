package com.example.torrentstream.torrent

import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

data class TorrentFileMeta(val index: Int, val path: String, val size: Long, val playable: Boolean)
data class TorrentMeta(val name: String, val files: List<TorrentFileMeta>)
data class TorrentStats(
    val downBps: Int = 0,
    val upBps: Int = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val progress: Float = 0f,
)

/** Random-access view of one file inside a torrent. */
interface FileStream {
    val size: Long
    /** On-disk file (sparse). Only bytes returned by [awaitAvailable] may be read from it. */
    val file: File

    /**
     * Blocks until the byte at [position] is verified on disk, re-targets the scheduler at that
     * position (this is what makes seeking work), and returns how many contiguous bytes
     * (1..[length]) can be read from [file] starting at [position].
     */
    @Throws(IOException::class)
    fun awaitAvailable(position: Long, length: Int): Int

    fun close()
}

/** Our own abstraction so the underlying BitTorrent library can be swapped. */
interface TorrentEngine {
    val stats: StateFlow<TorrentStats>

    /** [source] is a magnet URI or an absolute path to a .torrent file. Suspends until metadata is known. */
    suspend fun open(source: String): TorrentMeta

    /** Select a file for streaming: all other files are set to "do not download". */
    fun openFile(fileIndex: Int): FileStream

    fun closeTorrent()
}
