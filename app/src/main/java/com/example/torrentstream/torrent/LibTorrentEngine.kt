package com.example.torrentstream.torrent

import com.example.torrentstream.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * libtorrent4j-backed engine. libtorrent already implements DHT, UDP/HTTP trackers, magnet metadata
 * exchange, SHA-1 verification, choking, rarest-first, IPv6 and web seeds, and runs on its own
 * native network threads (no thread-per-peer). We only add the streaming policy on top.
 */
class LibTorrentEngine(
    private val root: File,
    private val settings: AppSettings,
) : TorrentEngine {

    private val sm = SessionManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stats = MutableStateFlow(TorrentStats())
    override val stats: StateFlow<TorrentStats> = _stats.asStateFlow()

    @Volatile private var started = false
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var pending: CompletableDeferred<TorrentHandle>? = null
    @Volatile private var stream: LtFileStream? = null
    @Volatile private var cleanup: Job? = null

    private val listener = object : AlertListener {
        override fun types(): IntArray? = null // all alerts
        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val h = (alert as AddTorrentAlert).handle()
                    if (h.isValid && h.torrentFile() != null) ready(h) // .torrent files: metadata already known
                }
                AlertType.METADATA_RECEIVED -> ready((alert as MetadataReceivedAlert).handle())
                AlertType.PIECE_FINISHED -> stream?.signal()
                else -> Unit
            }
        }
    }

    private fun ready(h: TorrentHandle) {
        val p = pending ?: return
        if (p.isCompleted) return
        val ti = h.torrentFile() ?: return
        // Nothing downloads until the user picks a file.
        h.prioritizeFiles(Array(ti.numFiles()) { Priority.IGNORE })
        handle = h
        p.complete(h)
    }

    @Synchronized
    private fun start() {
        if (started) return
        sm.addListener(listener)
        sm.start() // default pack: DHT bootstrap nodes, LSD, uTP, etc.
        val sp = SettingsPack()
        sp.connectionsLimit(settings.maxConnections)
        sp.downloadRateLimit(settings.downloadLimitBps)
        sp.uploadRateLimit(settings.uploadLimitBps)
        sm.applySettings(sp)
        scope.launch {
            while (isActive) {
                delay(1000) // UI stats at 1 Hz
                val h = handle
                if (h != null && h.isValid) {
                    val s = h.status()
                    _stats.value = TorrentStats(
                        s.downloadPayloadRate(), s.uploadPayloadRate(),
                        s.numPeers(), s.numSeeds(), s.progress(),
                    )
                }
            }
        }
        started = true
    }

    override suspend fun open(source: String): TorrentMeta = withContext(Dispatchers.IO) {
        closeTorrent()
        cleanup?.join()
        start()
        root.mkdirs()
        val p = CompletableDeferred<TorrentHandle>()
        pending = p
        try {
            if (source.startsWith("magnet:")) {
                require(Magnet.isValid(source)) { "Invalid magnet link" }
                sm.download(source, root, TorrentFlags.AUTO_MANAGED)
            } else {
                sm.download(TorrentInfo(File(source)), root)
            }
            val h = withTimeoutOrNull(settings.metadataTimeoutMs) { p.await() }
                ?: throw IOException(
                    "Could not fetch torrent metadata. No peers available - " +
                        "the torrent may currently have no seeders."
                )
            val ti = h.torrentFile() ?: throw IOException("Missing metadata")
            val fs = ti.files()
            val files = (0 until ti.numFiles()).mapNotNull { i ->
                val path = fs.filePath(i)
                if (path.contains("/.pad/")) null
                else TorrentFileMeta(i, path, fs.fileSize(i), MediaTypes.looksPlayable(path))
            }
            TorrentMeta(ti.name(), files)
        } catch (t: Throwable) {
            closeTorrent()
            throw t
        }
    }

    override fun openFile(fileIndex: Int): FileStream {
        val h = handle ?: throw IllegalStateException("No torrent loaded")
        val ti = h.torrentFile() ?: throw IllegalStateException("No metadata")
        require(fileIndex in 0 until ti.numFiles()) { "Bad file index" }
        h.prioritizeFiles(Array(ti.numFiles()) { if (it == fileIndex) Priority.SEVEN else Priority.IGNORE })
        stream?.close()
        return LtFileStream(h, ti, fileIndex, root, settings.aheadBytes).also { stream = it }
    }

    override fun closeTorrent() {
        stream?.close(); stream = null
        pending?.cancel(); pending = null
        handle?.let { if (it.isValid) sm.remove(it) }
        handle = null
        _stats.value = TorrentStats()
        cleanup = scope.launch { root.deleteRecursively() } // minimal "cache" policy: wiped per torrent
    }
}

/** Maps file byte positions to torrent pieces, steers libtorrent with piece deadlines, blocks readers. */
internal class LtFileStream(
    private val handle: TorrentHandle,
    ti: TorrentInfo,
    fileIndex: Int,
    root: File,
    aheadBytes: Long,
) : FileStream {
    private val fs = ti.files()
    private val pieceLen = ti.pieceLength()
    private val base = fs.fileOffset(fileIndex)
    override val size: Long = fs.fileSize(fileIndex)
    override val file: File = PieceMath.safeResolve(root, fs.filePath(fileIndex))

    private val firstPiece = PieceMath.pieceOf(base, pieceLen)
    private val lastPiece = PieceMath.pieceOf(base + maxOf(size, 1) - 1, pieceLen)
    private val ahead = (aheadBytes / pieceLen).toInt().coerceIn(4, 256)

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    @Volatile private var closed = false
    private var lastFirst = -1

    init { refresh(firstPiece) }

    /**
     * Zone 1/2: pieces [first, first+ahead) get increasing deadlines, so libtorrent fetches them
     * first (and may request the same block from several peers when a deadline is tight).
     * A jump (seek) clears every old deadline so we stop chasing the old region.
     * The tail pieces are also requested early because mp4 'moov' / mkv 'cues' often live there.
     * Everything else: libtorrent's normal rarest-first at default priority.
     */
    @Synchronized
    private fun refresh(first: Int) {
        if (first == lastFirst) return
        val jump = lastFirst < 0 || first < lastFirst || first > lastFirst + ahead
        if (jump) handle.clearPieceDeadlines()
        lastFirst = first
        for (p in first..minOf(first + ahead, lastPiece)) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, 300 + (p - first) * 250)
        }
        for (p in maxOf(lastPiece - 1, firstPiece)..lastPiece) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, 3000)
        }
    }

    fun signal() = lock.withLock { cond.signalAll() }

    override fun awaitAvailable(position: Long, length: Int): Int {
        val global = base + position
        val first = PieceMath.pieceOf(global, pieceLen)
        refresh(first)
        try {
            lock.withLock {
                while (!handle.havePiece(first)) {
                    if (closed) throw IOException("Stream closed")
                    cond.await(500, TimeUnit.MILLISECONDS) // woken by PIECE_FINISHED; timeout is a safety net
                }
            }
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        }
        val wanted = global + length
        var end = (first + 1).toLong() * pieceLen
        var p = first + 1
        while (end < wanted && p <= lastPiece && handle.havePiece(p)) { end += pieceLen; p++ }
        return minOf(length.toLong(), end - global).toInt()
    }

    override fun close() {
        closed = true
        signal()
    }
}
