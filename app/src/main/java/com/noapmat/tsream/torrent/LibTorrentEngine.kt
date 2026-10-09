package com.noapmat.tsream.torrent

import com.noapmat.tsream.AppSettings
import kotlinx.coroutines.CompletableDeferred
import android.util.Log
import com.noapmat.tsream.cache.HolePuncher
import kotlinx.coroutines.CoroutineExceptionHandler
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
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * libtorrent4j-backed engine. libtorrent already implements DHT, UDP/HTTP trackers, magnet metadata
 * exchange, SHA-1 verification, choking, rarest-first, IPv6 and web seeds, and runs on its own
 * native network threads (no thread-per-peer). We only add the streaming policy on top.
 */
class LibTorrentEngine(
    private val rootProvider: () -> File,
    private val settingsProvider: () -> AppSettings,
) : TorrentEngine {

    /** Dedicated folder (always a "Tstream-cache" / app-private folder) re-read from settings on every open(). */
    @Volatile private var root: File = rootProvider()

    private val settings: AppSettings get() = settingsProvider()

    private val sm = SessionManager()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e("TorrentEngine", "background job failed", e) }
    )
    private val _stats = MutableStateFlow(TorrentStats())
    override val stats: StateFlow<TorrentStats> = _stats.asStateFlow()
    override val isOpen: Boolean get() = handle != null

    @Volatile private var started = false
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var pending: CompletableDeferred<TorrentHandle>? = null
    @Volatile private var stream: LtFileStream? = null
    @Volatile private var cleanup: Job? = null
    @Volatile private var removal: CompletableDeferred<Unit>? = null

    private val listener = object : AlertListener {
        override fun types(): IntArray? = null // all alerts
        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val h = owned((alert as AddTorrentAlert).handle())
                    if (h != null) {
                        handle = h // always track it, even before metadata, so close/retry can remove it
                        if (h.torrentFile() != null) ready(h) // .torrent files: metadata already known
                    }
                }
                AlertType.TORRENT_REMOVED -> removal?.complete(Unit)
                AlertType.METADATA_RECEIVED -> owned((alert as MetadataReceivedAlert).handle())?.let { ready(it) }
                AlertType.PIECE_FINISHED -> stream?.let {
                    it.onPieceFinished((alert as PieceFinishedAlert).pieceIndex())
                    it.signal()
                }
                AlertType.TORRENT_CHECKED -> stream?.onChecked()
                else -> Unit
            }
        }
    }

    /**
     * A handle taken from an alert points into the alert's own memory, which libtorrent frees as soon
     * as the callback returns. Using it later segfaults. Looking the torrent up in the session gives
     * a handle we own. Must be called inside the alert callback, while the alert is still alive.
     */
    private fun owned(fromAlert: TorrentHandle): TorrentHandle? =
        if (fromAlert.isValid) sm.find(fromAlert.infoHash()) else null

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
        root.deleteRecursively() // leftovers from a previous run that was killed before it could clean up
        sm.addListener(listener)
        sm.start() // default pack: DHT bootstrap nodes, LSD, uTP, etc.
        scope.launch {
            while (isActive) {
                delay(1000) // UI stats at 1 Hz
                try {
                    val h = handle
                    if (h != null && h.isValid) {
                        val s = h.status()
                        _stats.value = TorrentStats(
                            s.downloadPayloadRate(), s.uploadPayloadRate(),
                            s.numPeers(), s.numSeeds(), s.progress(),
                        )
                    }
                } catch (e: Exception) {
                    Log.w("TorrentEngine", "stats failed", e)
                }
            }
        }
        started = true
    }

    /** Connection/rate limits and the seeding switch. Re-applied on every open() so setting changes take effect. */
    private fun applyNetworkSettings() {
        val s = settings
        val sp = SettingsPack()
        sp.connectionsLimit(s.maxConnections)
        sp.downloadRateLimit(s.downloadLimitBps)
        sp.uploadRateLimit(s.uploadLimitBps)
        // Not seeding: nobody is ever unchoked and no "allowed fast" pieces are offered, so we upload nothing.
        sp.setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), if (s.seedWhileWatching) 8 else 0)
        sp.setInteger(settings_pack.int_types.allowed_fast_set_size.swigValue(), if (s.seedWhileWatching) 5 else 0)
        sm.applySettings(sp)
    }

    override suspend fun open(source: String): TorrentMeta = withContext(Dispatchers.IO) {
        closeTorrent()
        cleanup?.join()
        root = rootProvider()
        start()
        applyNetworkSettings()
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
        h.prioritizeFiles(Array(ti.numFiles()) { if (it == fileIndex) Priority.TOP_PRIORITY else Priority.IGNORE })
        stream?.close()
        val s = settings
        if (!s.seedWhileWatching) h.setUploadLimit(1) // belt and braces on top of the unchoke-slots switch
        return LtFileStream(
            h, ti, fileIndex, root, s.aheadBytes, s.behindBytes,
            smart = s.smartCache, evict = s.evictEnabled,
        ).also { stream = it }
    }

    override fun closeTorrent() {
        stream?.close(); stream = null
        pending?.cancel(); pending = null
        val h = handle
        handle = null
        _stats.value = TorrentStats()
        val removed: CompletableDeferred<Unit>? =
            if (h != null && h.isValid) CompletableDeferred<Unit>().also { removal = it } else null
        if (removed != null) sm.remove(h!!, SessionHandle.DELETE_FILES)
        val previous = cleanup
        val dir = root
        // Removal is asynchronous and libtorrent memory-maps the files: only delete them (and let a
        // same-hash torrent be added again) once the session confirms the torrent is gone.
        cleanup = scope.launch {
            previous?.join()
            if (removed != null) {
                withTimeoutOrNull(5000) { removed.await() }
                delay(300)
            }
            dir.deleteRecursively()
        }
    }
}

/**
 * Streams one file of the torrent with a bounded on-disk footprint:
 *  - only pieces in [read position, read position + ahead] are downloaded (everything else has priority 0),
 *  - finished pieces further than [behind] behind the read position are deleted from disk (hole punching),
 *  - the first/last two pieces (container headers/index) are always kept.
 * libtorrent still believes it "has" a deleted piece, so reading one again triggers a force-recheck, after
 * which libtorrent forgets the deleted pieces and downloads them again.
 */
internal class LtFileStream(
    private val handle: TorrentHandle,
    ti: TorrentInfo,
    fileIndex: Int,
    root: File,
    aheadBytes: Long,
    behindBytes: Long,
    private val smart: Boolean,
    evict: Boolean,
) : FileStream {
    private companion object {
        const val TAG = "LtFileStream"
        const val MAX_DEADLINE_PIECES = 128
        const val RECHECK_TIMEOUT_MS = 120_000L
    }

    private val fs = ti.files()
    private val pieceLen = ti.pieceLength()
    private val numPieces = ti.numPieces()
    private val base = fs.fileOffset(fileIndex)
    override val size: Long = fs.fileSize(fileIndex)
    override val file: File = PieceMath.safeResolve(root, fs.filePath(fileIndex))

    private val firstPiece = PieceMath.pieceOf(base, pieceLen)
    private val lastPiece = PieceMath.pieceOf(base + maxOf(size, 1) - 1, pieceLen)
    /** Smart cache off = no window and no deletion: libtorrent just downloads the whole file into the folder. */
    private val ahead = if (smart) CachePolicy.piecesFor(aheadBytes, pieceLen, 2) else numPieces
    private val behind = CachePolicy.piecesFor(behindBytes, pieceLen, 1)
    private val evictOn = smart && evict
    private val jumpGap = minOf(ahead, MAX_DEADLINE_PIECES)

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    @Volatile private var closed = false

    // Everything below is guarded by `this`.
    private var lastFirst = -1
    private var winLo = -1
    private var winHi = -2
    private val present = BitSet(numPieces)  // pieces we know are complete on disk (and not deleted)
    private val evicted = BitSet(numPieces)  // pieces deleted from disk that libtorrent still thinks it has
    private var punchBroken = false
    @Volatile private var checking = false
    @Volatile private var needsReinit = false
    private var checkDeadline = 0L

    init {
        initPriorities()
        refresh(firstPiece)
    }

    // ---- scheduling ----------------------------------------------------------------------------

    /** Nothing downloads except what we explicitly enable. libtorrent forgets these after a recheck. */
    @Synchronized
    private fun initPriorities() {
        if (!smart) { winLo = -1; winHi = -2; lastFirst = -1; return }
        handle.prioritizePieces(Array(numPieces) { Priority.IGNORE })
        for (p in firstPiece..minOf(firstPiece + 1, lastPiece)) handle.piecePriority(p, Priority.DEFAULT)
        for (p in maxOf(lastPiece - 1, firstPiece)..lastPiece) handle.piecePriority(p, Priority.DEFAULT)
        winLo = -1; winHi = -2; lastFirst = -1
    }

    /** Enable downloading for [lo, hi] only; pieces that left the window go back to "ignore". */
    private fun setWindow(lo: Int, hi: Int) {
        if (winHi >= winLo) {
            for (p in winLo..winHi) {
                if ((p < lo || p > hi) && !CachePolicy.isProtected(p, firstPiece, lastPiece)) {
                    handle.piecePriority(p, Priority.IGNORE)
                }
            }
        }
        for (p in lo..hi) if (p < winLo || p > winHi) handle.piecePriority(p, Priority.DEFAULT)
        winLo = lo; winHi = hi
    }

    /** Called whenever the reader moves to a new piece. */
    @Synchronized
    private fun refresh(first: Int) {
        if (first == lastFirst) return
        val jump = lastFirst < 0 || first < lastFirst || first > lastFirst + jumpGap
        lastFirst = first
        val hi = minOf(lastPiece, first + ahead)
        if (jump) handle.clearPieceDeadlines() // seek: stop chasing the old region
        if (smart) setWindow(first, hi)
        for (p in first..minOf(hi, first + MAX_DEADLINE_PIECES - 1)) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, 300 + (p - first) * 250)
        }
        for (p in maxOf(lastPiece - 1, firstPiece)..lastPiece) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, 3000) // mp4 moov / mkv cues
        }
        if (evictOn) evictBehind(first)
    }

    // ---- eviction ------------------------------------------------------------------------------

    private fun evictBehind(first: Int) {
        if (!evictOn || !HolePuncher.available || punchBroken) return
        var p = present.nextSetBit(0)
        while (p >= 0) {
            val next = present.nextSetBit(p + 1)
            if (CachePolicy.shouldEvict(p, first, behind, ahead, firstPiece, lastPiece)) {
                if (punch(p)) {
                    evicted.set(p)
                    present.clear(p)
                } else if (punchBroken) return
            }
            p = next
        }
    }

    private fun punch(p: Int): Boolean {
        val gs = p.toLong() * pieceLen
        val from = maxOf(gs, base) - base
        val to = minOf(gs + pieceLen, base + size) - base
        if (to <= from) return true
        val r = HolePuncher.punch(file.absolutePath, from, to - from)
        if (r != 0) {
            Log.w(TAG, "hole punch failed ($r); disabling deletion behind the playhead")
            punchBroken = true
            return false
        }
        return true
    }

    // ---- events from the alert thread ----------------------------------------------------------

    @Synchronized
    fun onPieceFinished(index: Int) {
        if (!closed && index in firstPiece..lastPiece) present.set(index)
    }

    fun onChecked() {
        synchronized(this) {
            if (checking) { checking = false; needsReinit = true }
        }
        signal()
    }

    fun signal() = lock.withLock { cond.signalAll() }

    // ---- recheck after reading a deleted piece -------------------------------------------------

    @Synchronized
    private fun isEvicted(p: Int) = evicted.get(p)

    @Synchronized
    private fun startRecheck() {
        if (checking) return
        Log.i(TAG, "reading a deleted piece: rechecking so libtorrent re-downloads it")
        checking = true
        needsReinit = false
        checkDeadline = System.currentTimeMillis() + RECHECK_TIMEOUT_MS
        evicted.clear()
        present.clear()
        handle.forceRecheck()
    }

    @Synchronized
    private fun finishCheck() {
        checking = false
        needsReinit = true
    }

    /** The recheck rebuilt libtorrent's piece picker (custom priorities are lost): set everything up again. */
    @Synchronized
    private fun reinit(first: Int) {
        needsReinit = false
        initPriorities()
        val keep = CachePolicy.keepRange(first, behind, ahead, firstPiece, lastPiece)
        for (p in keep) if (handle.havePiece(p)) present.set(p) // survivors of the recheck
        refresh(first)
    }

    // ---- FileStream ----------------------------------------------------------------------------

    override fun awaitAvailable(position: Long, length: Int): Int =
        try {
            doAwait(position, length)
        } catch (e: RuntimeException) {
            throw IOException("Torrent engine error: ${e.message}", e)
        }

    private fun doAwait(position: Long, length: Int): Int {
        val global = base + position
        val first = PieceMath.pieceOf(global, pieceLen).coerceIn(firstPiece, lastPiece)
        try {
            if (!isEvicted(first)) refresh(first)
            while (true) {
                if (closed) throw IOException("Stream closed")
                if (checking) {
                    if (System.currentTimeMillis() > checkDeadline) finishCheck()
                } else if (needsReinit) {
                    reinit(first); continue
                } else if (isEvicted(first)) {
                    startRecheck(); continue
                } else if (handle.havePiece(first)) {
                    break
                }
                lock.withLock { cond.await(500, TimeUnit.MILLISECONDS) } // woken by piece/check alerts
            }
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        }
        val wanted = global + length
        var end = (first + 1).toLong() * pieceLen
        var p = first + 1
        while (end < wanted && p <= lastPiece && !isEvicted(p) && handle.havePiece(p)) { end += pieceLen; p++ }
        return minOf(length.toLong(), end - global).toInt()
    }

    override fun close() {
        closed = true
        signal()
    }
}
