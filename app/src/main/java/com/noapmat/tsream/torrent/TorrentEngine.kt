package com.noapmat.tsream.torrent

import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

data class TorrentFileMeta(
    val index: Int,
    val path: String,
    val size: Long,
    val playable: Boolean,
    val subtitle: Boolean = false,
)
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

    /** Human-readable progress of the piece the player is currently waiting for (null when not waiting). */
    val waitInfo: String? get() = null

    /** Tells the stream where the playhead is so "keep behind" is measured from what you watch, not what is buffered. */
    fun updatePlayback(positionMs: Long, bufferedMs: Long, durationMs: Long) {}

    fun close()
}

/** Our own abstraction so the underlying BitTorrent library can be swapped. */
interface TorrentEngine {
    val stats: StateFlow<TorrentStats>

    /** True while a torrent is loaded in the session (false after [closeTorrent]). */
    val isOpen: Boolean

    /** Files of the loaded torrent (empty when nothing is loaded). */
    val files: List<TorrentFileMeta>

    /** [source] is a magnet URI or an absolute path to a .torrent file. Suspends until metadata is known. */
    suspend fun open(source: String): TorrentMeta

    /** Downloads one (small) file completely, e.g. a subtitle, and returns it. */
    suspend fun fetchFile(fileIndex: Int): File

    /** Select a file for streaming: all other files are set to "do not download". */
    fun openFile(fileIndex: Int): FileStream

    /** Stops the torrent and deletes everything it downloaded. Safe to call repeatedly. */
    fun closeTorrent()
}
