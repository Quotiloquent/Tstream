package com.noapmat.tstream.torrent

import com.noapmat.tstream.AppSettings
import kotlinx.coroutines.CompletableDeferred
import android.util.Log
import com.noapmat.tstream.cache.Cleanup
import com.noapmat.tstream.cache.HolePuncher
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
import kotlinx.coroutines.sync.Mutex
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
private const val RESUME_PAD_BYTES = 8L shl 20
private const val HEAD_WARM_BYTES = 16L shl 20      // start of the file prefetched while you browse (header, fonts)
private const val WARM_FAST_BPS = 6_000_000         // prefetch speed cap: it can never flood the disk
private const val WARM_TRICKLE_BPS = 8_000          // afterwards just enough to keep the torrent unfinished
private const val WARM_BUDGET_BYTES = 96L shl 20    // hard cap on what the prefetch may download in total

class LibTorrentEngine(
    private val rootProvider: () -> File,
    private val settingsProvider: () -> AppSettings,
    /** Completes when the start-up cleanup of stale caches is done; nothing is opened before that. */
    private val startupGate: suspend () -> Unit = {},
    /** Saved playhead byte offset for (file path, file size), or null. Used to prefetch near the resume point. */
    private val resumeHint: (path: String, size: Long) -> Long? = { _, _ -> null },
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
    override val isOpen: Boolean get() = handle?.isValid == true
    @Volatile private var metaFiles: List<TorrentFileMeta> = emptyList()
    override val files: List<TorrentFileMeta> get() = metaFiles

    @Volatile private var started = false
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var pending: CompletableDeferred<TorrentHandle>? = null
    @Volatile private var stream: LtFileStream? = null
    @Volatile private var cleanup: Job? = null
    @Volatile private var removal: CompletableDeferred<Unit>? = null
    /** open() calls never overlap: a cancelled one finishes its cleanup before the next one starts. */
    private val opMutex = Mutex()
    /** Info hash (hex) of the torrent being opened; alerts about any other torrent are ignored. */
    @Volatile private var expectedHash: String? = null

    private val listener = object : AlertListener {
        override fun types(): IntArray? = null // all alerts
        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val a = alert as AddTorrentAlert
                    if (a.error().isError) {
                        // e.g. duplicate_torrent: libtorrent hands back the OLD torrent, which is being removed.
                        // Adopting it left the app with a dead torrent (no peers, no connections).
                        pending?.completeExceptionally(IOException("Could not add the torrent: ${a.error().message}"))
                    } else {
                        val h = owned(a.handle())
                        if (h != null) {
                            if (isOrphan(h)) {
                                // an open() that was cancelled / closed before libtorrent finished adding it
                                sm.remove(h, SessionHandle.DELETE_FILES)
                            } else if (isExpected(h)) {
                                handle = h // always track it, even before metadata, so close/retry can remove it
                                if (h.torrentFile() != null) ready(h) // .torrent files: metadata already known
                            }
                        }
                    }
                }
                AlertType.TORRENT_REMOVED -> removal?.complete(Unit)
                AlertType.METADATA_RECEIVED ->
                    owned((alert as MetadataReceivedAlert).handle())?.let {
                        if (isOrphan(it)) sm.remove(it, SessionHandle.DELETE_FILES) else if (isExpected(it)) ready(it)
                    }
                AlertType.PIECE_FINISHED -> {
                    val idx = (alert as PieceFinishedAlert).pieceIndex()
                    onWarmPieceDone(idx)
                    stream?.let {
                        it.onPieceFinished(idx)
                        it.signal()
                    }
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

    /** A torrent that no open() is waiting for any more. It must never be left running. */
    private fun isOrphan(h: TorrentHandle): Boolean = pending == null || !isExpected(h)

    private fun isExpected(h: TorrentHandle): Boolean {
        val expected = expectedHash ?: return true
        return h.infoHash().toHex().equals(expected, ignoreCase = true)
    }

    private fun ready(h: TorrentHandle) {
        val p = pending ?: return
        if (p.isCompleted) return
        val ti = h.torrentFile() ?: return
        warmUp(h, ti)
        // Torrents are added in upload mode (metadata only). Priorities are set now, so data may download.
        h.unsetFlags(TorrentFlags.UPLOAD_MODE)
        handle = h
        p.complete(h)
    }

    private val warmPieces = HashSet<Int>()
    @Volatile private var warming = false
    private var warmBytes = 0L
    private var warmPieceLen = 0L
    private var trickling = false

    /**
     * Until the user picks a file the likely video (the biggest one) is prefetched gently and with hard limits:
     *  - first come the start of the file (container header, index and, in mkv, the embedded fonts that
     *    players always read first), the last two pieces and, when a resume point is saved, the pieces there;
     *  - the rest of the file stays wanted at the lowest priority, so the torrent is never "finished":
     *    a finished torrent makes seeds drop their connections to us and we then wait for reconnects;
     *  - speed is capped from the start, and once the first pieces are in (or [WARM_BUDGET_BYTES] were downloaded)
     *    the torrent is throttled to a trickle (see [onWarmPieceDone]).
     * Picking a file ends all of this (see [openFile]).
     */
    private fun warmUp(h: TorrentHandle, ti: TorrentInfo) {
        val fs = ti.files()
        val pl = ti.pieceLength()
        val n = ti.numFiles()
        val best = (0 until n).filter { MediaTypes.looksPlayable(fs.filePath(it)) }.maxByOrNull { fs.fileSize(it) }
        // file level (survives a recheck): the likely video stays wanted, everything else is ignored
        h.prioritizeFiles(Array(n) { if (it == best) Priority.LOW else Priority.IGNORE })
        if (best == null) return
        val off = fs.fileOffset(best)
        val size = fs.fileSize(best)
        val first = PieceMath.pieceOf(off, pl)
        val last = PieceMath.pieceOf(off + maxOf(size, 1) - 1, pl)
        val hint = resumeHint(fs.filePath(best), size)
        val start = if (hint != null && hint >= 0) resumePiece(off, hint, pl, first, last) else first
        val prios = Array(ti.numPieces()) { Priority.IGNORE }
        for (p in start..last) prios[p] = Priority.LOW // wanted from the resume point on
        val warm = LinkedHashSet<Int>()
        val headEnd = first + CachePolicy.piecesFor(HEAD_WARM_BYTES, pl, 2) - 1
        for (p in first..minOf(headEnd, last)) warm += p
        for (p in maxOf(last - 1, first)..last) warm += p
        for (p in start..minOf(start + 2, last)) warm += p
        for (p in warm) prios[p] = Priority.DEFAULT
        h.prioritizePieces(prios)
        h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        h.setDownloadLimit(WARM_FAST_BPS)
        synchronized(warmPieces) {
            warmPieces.clear(); warmPieces.addAll(warm)
            warmBytes = 0; warmPieceLen = pl.toLong(); trickling = false
        }
        warming = true
    }

    /** Piece to start the window at for a saved playhead [bytes] (padded: the saved offset errs on the early side). */
    private fun resumePiece(fileOffset: Long, bytes: Long, pieceLen: Int, first: Int, last: Int): Int =
        (PieceMath.pieceOf(fileOffset + bytes + RESUME_PAD_BYTES, pieceLen) - 1).coerceIn(first, last)

    private fun onWarmPieceDone(idx: Int) {
        if (!warming) return
        synchronized(warmPieces) {
            warmBytes += warmPieceLen
            val firstPiecesDone = warmPieces.remove(idx) && warmPieces.isEmpty()
            if (!trickling && (firstPiecesDone || warmBytes >= WARM_BUDGET_BYTES)) {
                trickling = true
                // everything needed to start quickly is on disk (or the budget is used up): slow down so the torrent
                // stays unfinished (and connected) while the user browses, without filling the disk
                handle?.setDownloadLimit(WARM_TRICKLE_BPS)
            }
        }
    }

    @Synchronized
    private fun start() {
        if (started) return
        Cleanup.deleteTree(root) // leftovers from a previous run that was killed before it could clean up
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
        // (Never use an upload *rate limit* for this: libtorrent charges request messages and TCP ACKs to it,
        // so a tiny limit starves the download too.)
        sp.setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), if (s.seeding) 8 else 0)
        sp.setInteger(settings_pack.int_types.allowed_fast_set_size.swigValue(), if (s.seeding) 5 else 0)
        // A peer we lost (e.g. a seed that dropped us) may be retried after 5 s instead of the default 60 s.
        sp.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 5)
        // When every wanted piece in the window is downloaded libtorrent considers us "finished" and would drop
        // its connections to seeds; reconnecting then costs up to a minute of buffering when the window slides.
        sp.setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
        sm.applySettings(sp)
    }

    override suspend fun open(source: String): TorrentMeta = withContext(Dispatchers.IO) {
        startupGate()
        opMutex.lock() // a previous (possibly cancelled) open() must finish its cleanup first
        try {
            openLocked(source)
        } finally {
            opMutex.unlock()
        }
    }

    private suspend fun openLocked(source: String): TorrentMeta {
        closeTorrent()
        cleanup?.join() // the old torrent is confirmed removed before the same one can be added again
        root = rootProvider()
        start()
        applyNetworkSettings()
        root.mkdirs()
        val p = CompletableDeferred<TorrentHandle>()
        try {
            if (source.startsWith("magnet:")) {
                require(Magnet.isValid(source)) { "Invalid magnet link" }
                synchronized(this) { expectedHash = Magnet.infoHashHex(source); pending = p }
                // UPLOAD_MODE: fetch the metadata but download no data until ready() has set the priorities
                sm.download(source, root, TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPLOAD_MODE))
            } else {
                val info = TorrentInfo(File(source))
                synchronized(this) { expectedHash = info.infoHash().toHex(); pending = p }
                sm.download(info, root, null, Array(info.numFiles()) { Priority.IGNORE }, null, TorrentFlags.UPLOAD_MODE)
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
                else TorrentFileMeta(
                    i, path, fs.fileSize(i),
                    playable = MediaTypes.looksPlayable(path),
                    subtitle = MediaTypes.isSubtitle(path),
                )
            }
            metaFiles = files
            return TorrentMeta(ti.name(), files)
        } catch (t: Throwable) {
            closeTorrent()
            throw t
        }
    }

    override fun openFile(fileIndex: Int, startBytes: Long): FileStream {
        val h = handle ?: throw IllegalStateException("No torrent loaded")
        if (!h.isValid) throw IllegalStateException("The torrent was closed - go back and open it again")
        val ti = h.torrentFile() ?: throw IllegalStateException("No metadata")
        require(fileIndex in 0 until ti.numFiles()) { "Bad file index" }
        // the gentle prefetch is over: full speed, no sequential mode, the smart-cache window takes over
        warming = false
        h.setDownloadLimit(0)
        h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        h.prioritizeFiles(Array(ti.numFiles()) { if (it == fileIndex) Priority.TOP_PRIORITY else Priority.IGNORE })
        stream?.close()
        val s = settings
        return LtFileStream(
            h, ti, fileIndex, root, s.aheadBytes, s.behindBytes,
            smart = s.smartCache, evict = s.smartCache, startBytes = startBytes,
        ).also { stream = it }
    }

    override suspend fun fetchFile(fileIndex: Int): File = withContext(Dispatchers.IO) {
        val h = handle ?: throw IllegalStateException("No torrent loaded")
        val ti = h.torrentFile() ?: throw IllegalStateException("No metadata")
        require(fileIndex in 0 until ti.numFiles()) { "Bad file index" }
        val fs = ti.files()
        val pl = ti.pieceLength()
        val first = PieceMath.pieceOf(fs.fileOffset(fileIndex), pl)
        val last = PieceMath.pieceOf(fs.fileOffset(fileIndex) + maxOf(fs.fileSize(fileIndex), 1) - 1, pl)
        for (p in first..last) {
            if (!h.havePiece(p)) {
                h.piecePriority(p, Priority.TOP_PRIORITY)
                h.setPieceDeadline(p, 200)
            }
        }
        val deadline = System.currentTimeMillis() + 90_000
        while ((first..last).any { !h.havePiece(it) }) {
            if (System.currentTimeMillis() > deadline) throw IOException("Timed out downloading the file (no peers?)")
            delay(150)
        }
        PieceMath.safeResolve(root, fs.filePath(fileIndex))
    }

    /** No activity visible: stop all network activity (and so downloading) until the app is back on screen. */
    override fun setBackground(background: Boolean) {
        if (!started) return
        try { if (background) sm.pause() else sm.resume() } catch (e: Exception) { /* session not ready */ }
    }

    @Synchronized
    override fun closeTorrent() {
        stream?.close(); stream = null
        pending?.cancel(); pending = null
        expectedHash = null
        metaFiles = emptyList()
        warming = false
        synchronized(warmPieces) { warmPieces.clear() }
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
            // Slow / busy storage (SD cards) may refuse the first attempts while libtorrent still has files open.
            var attempts = 0
            while (!Cleanup.deleteTree(dir) && attempts++ < 8) delay(500)
            if (dir.exists()) Log.w("TorrentEngine", "could not fully delete ${dir.path}; it is wiped on next start")
        }
    }
}

/**
 * Streams one file of the torrent with a bounded on-disk footprint:
 *  - only pieces in [read position, read position + ahead] are downloaded (everything else has priority 0),
 *  - finished pieces further than [behind] behind the *playhead* are deleted from disk (hole punching),
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
    startBytes: Long,
) : FileStream {
    private companion object {
        const val TAG = "LtFileStream"
        const val MAX_DEADLINE_PIECES = 128
        const val RECHECK_TIMEOUT_MS = 120_000L
        const val HEAD_REGION_BYTES = 64L shl 20 // the player may read this much of the start (header, fonts) before seeking
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
    // at least 4 pieces even when pieces are huge, so several peers can work in parallel
    private val ahead = if (smart) CachePolicy.piecesFor(aheadBytes, pieceLen, 4) else numPieces
    private val behind = CachePolicy.piecesFor(behindBytes, pieceLen, 1)
    private val evictOn = smart && evict

    /** Where the download window starts: at the resume point when there is one, else at the beginning. */
    private val startPiece: Int =
        if (startBytes >= 0) (PieceMath.pieceOf(base + startBytes + (8L shl 20), pieceLen) - 1).coerceIn(firstPiece, lastPiece)
        else firstPiece
    /** While true, reads at the start of the file do not drag the window away from the resume point. */
    private var hintHold = false
    private val headRegionEnd = firstPiece + CachePolicy.piecesFor(HEAD_REGION_BYTES, pieceLen, 2)
    private var extraLo = -1
    private var extraHi = -2
    private val jumpGap = minOf(ahead, MAX_DEADLINE_PIECES)

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    @Volatile private var closed = false

    /** How many pieces the playhead trails the loader's read position (the player's buffer). Unknown = never evict. */
    @Volatile private var lagPieces = numPieces

    // Everything below is guarded by `this`.
    private var lastFirst = -1
    private var winLo = -1
    private var winHi = -2
    private val present = BitSet(numPieces)  // pieces known to be complete on disk (and not deleted)
    private val evicted = BitSet(numPieces)  // pieces deleted from disk that libtorrent still thinks it has
    private var punchBroken = false
    @Volatile private var checking = false
    @Volatile private var needsReinit = false
    private var checkDeadline = 0L
    /** Piece the player is reading now (or last read): where the window is rebuilt after a recheck. */
    @Volatile private var readPiece = -1

    init {
        initPriorities(startPiece)
        refresh(startPiece)
        hintHold = startPiece > firstPiece // only now: the call above must not switch it off
    }

    // ---- availability: local bitmap first, native call only when unsure --------------------------

    @Synchronized
    private fun have(p: Int): Boolean {
        if (present.get(p)) return true
        if (evicted.get(p)) return false
        if (handle.havePiece(p)) { present.set(p); return true }
        return false
    }

    // ---- scheduling ----------------------------------------------------------------------------

    /** Nothing downloads except what we explicitly enable. libtorrent forgets these after a recheck. */
    @Synchronized
    private fun initPriorities(first: Int) {
        winLo = -1; winHi = -2; lastFirst = -1
        if (!smart) return
        val lo = first
        val hi = minOf(lastPiece, first + ahead)
        // One atomic call. Setting everything to "ignore" first would make libtorrent think the torrent is
        // finished for a moment (it then drops connections to seeds).
        handle.prioritizePieces(Array(numPieces) { p ->
            val inFile = p in firstPiece..lastPiece
            if (p in lo..hi || (inFile && CachePolicy.isProtected(p, firstPiece, lastPiece))) Priority.DEFAULT else Priority.IGNORE
        })
        winLo = lo; winHi = hi
    }

    /** Enable downloading for [lo, hi] only; pieces that left the window go back to "ignore". */
    private fun setWindow(lo: Int, hi: Int) {
        // enter first, leave second: there is always something wanted, so the torrent never looks finished
        for (p in lo..hi) if (p < winLo || p > winHi) handle.piecePriority(p, Priority.DEFAULT)
        if (winHi >= winLo) {
            for (p in winLo..winHi) {
                if ((p < lo || p > hi) && !CachePolicy.isProtected(p, firstPiece, lastPiece)) {
                    handle.piecePriority(p, Priority.IGNORE)
                }
            }
        }
        winLo = lo; winHi = hi
    }

    /** Called whenever the reader moves to a new piece. */
    private fun followExtra(first: Int) {
        if (first == extraLo) return
        val hi = minOf(lastPiece, first + maxOf(2, minOf(ahead, CachePolicy.piecesFor(16L shl 20, pieceLen, 2))))
        if (smart) {
            for (p in first..hi) {
                if (p !in winLo..winHi && (p < extraLo || p > extraHi)) handle.piecePriority(p, Priority.DEFAULT)
            }
            if (extraHi >= extraLo) {
                for (p in extraLo..extraHi) {
                    if ((p < first || p > hi) && p !in winLo..winHi && !CachePolicy.isProtected(p, firstPiece, lastPiece)) {
                        handle.piecePriority(p, Priority.IGNORE)
                    }
                }
            }
        }
        extraLo = first
        extraHi = hi
        if (!have(first)) handle.setPieceDeadline(first, 100)
        for (p in first + 1..minOf(hi, first + 31)) {
            if (!have(p)) handle.setPieceDeadline(p, 300 + (p - first) * 150)
        }
        structuralDeadlines()
    }

    private fun clearExtra() {
        if (smart && extraHi >= extraLo) {
            for (p in extraLo..extraHi) {
                if (p !in winLo..winHi && !CachePolicy.isProtected(p, firstPiece, lastPiece)) handle.piecePriority(p, Priority.IGNORE)
            }
        }
        extraLo = -1
        extraHi = -2
    }

    /** Deadlines for the pieces every player needs: the container header and the index at the end. */
    private fun structuralDeadlines() {
        if (!have(firstPiece)) handle.setPieceDeadline(firstPiece, 100)
        for (p in maxOf(lastPiece - 1, firstPiece)..lastPiece) {
            if (!have(p)) handle.setPieceDeadline(p, if (p == lastPiece) 150 else 700)
        }
    }

    @Synchronized
    private fun refresh(first: Int) {
        if (first == lastFirst) return
        if (hintHold) {
            val nearResume = first in (startPiece - 1)..(startPiece + ahead)
            if (!nearResume && first <= headRegionEnd) {
                // A resuming player reads the container header, the index and (mkv) the embedded fonts at the start
                // of the file before it seeks. Those are needed, so serve them from a small extra window, in
                // parallel with the resume window, which stays where it is.
                followExtra(first)
                return
            }
            hintHold = false // reached the resume area, or went somewhere else: normal behaviour from here on
            clearExtra()
        }
        val jump = lastFirst < 0 || first < lastFirst - 2 || first > lastFirst + jumpGap
        lastFirst = first
        val hi = minOf(lastPiece, first + ahead)
        if (jump) handle.clearPieceDeadlines() // seek: stop chasing the old region
        if (smart) setWindow(first, hi)
        // Deadlines (ms): the piece being read, then the container header/index (mp4 moov / mkv cues - the
        // player cannot start without them), then the rest of the window.
        if (!have(first)) handle.setPieceDeadline(first, 100)
        structuralDeadlines()
        for (p in first + 1..minOf(hi, first + MAX_DEADLINE_PIECES - 1)) {
            if (!have(p)) handle.setPieceDeadline(p, 300 + (p - first) * 250)
        }
        if (evictOn) evictBehind(first)
    }

    // ---- eviction ------------------------------------------------------------------------------

    private fun evictBehind(first: Int) {
        if (!evictOn || !HolePuncher.available || punchBroken) return
        val anchor = maxOf(firstPiece, first - lagPieces) // estimated playhead
        var p = present.nextSetBit(0)
        while (p >= 0) {
            val next = present.nextSetBit(p + 1)
            if (CachePolicy.shouldEvict(p, anchor, first, behind, ahead, firstPiece, lastPiece)) {
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

    @Volatile private var waitText: String? = null
    override val waitInfo: String? get() = waitText

    /** Which piece we are blocked on and how many of its 16 KB blocks have arrived (shown in the player). */
    private var lastNudge = 0L

    private fun describeWait(piece: Int, waitStart: Long) {
        val q = try { handle.getDownloadQueue() } catch (e: Exception) { null }
        val st = try { handle.status() } catch (e: Exception) { null }
        val pi = q?.firstOrNull { it.pieceIndex() == piece }
        val mb = pieceLen.toDouble() / (1 shl 20)
        val now = System.currentTimeMillis()
        // No peer for a while: make sure the torrent is running and ask the trackers for peers again.
        if (st != null && st.numPeers() == 0 && now - waitStart > 4000 && now - lastNudge > 15_000) {
            lastNudge = now
            try { handle.resume(); handle.forceReannounce() } catch (e: Exception) { /* best effort */ }
        }
        waitText = (if (pi != null) {
            "#$piece: ${pi.finished()}/${pi.blocksInPiece()}" +
                (if (pi.requested() > 0) " • ${pi.requested()}" else "") + " (%.1f MB/p)".format(mb)
        } else {
            "#$piece queued (%.1f MB/p)".format(mb)
        }) + (st?.let { "\n${it.state().name.lowercase()} • [PA:${it.numPeers()}/${it.listPeers()}|PC:${it.connectCandidates()}]" } ?: "")
    }

    /**
     * The player reads ahead of what you are watching (mpv's demuxer buffers up to MpvConfig.READAHEAD_SECS seconds, plus
     * its probe and decode buffers).
     * Playhead lag = buffered time converted to bytes with the file's average bitrate, padded by 50% (VBR),
     * plus 20 MB for the prefetch.
     */
    override fun updatePlayback(positionMs: Long, bufferedMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs < 0) return
        val lagMs = (bufferedMs - positionMs).coerceAtLeast(0)
        val lagBytes = (lagMs.toDouble() / durationMs * size * 1.5).toLong() + (20L shl 20)
        lagPieces = ((lagBytes + pieceLen - 1) / pieceLen).toInt().coerceIn(1, numPieces)
    }

    override fun estimatedPlayheadBytes(): Long {
        if (lagPieces >= numPieces || lastFirst < 0) return -1L
        return (maxOf(firstPiece, lastFirst - lagPieces) - firstPiece).toLong() * pieceLen
    }

    /** How far the file is downloaded without gaps from [fromBytes] on (drives the player's buffer indicator). */
    override fun bufferedUntil(fromBytes: Long): Long {
        if (closed || size <= 0) return fromBytes
        val from = fromBytes.coerceIn(0L, size - 1)
        var p = PieceMath.pieceOf(base + from, pieceLen).coerceIn(firstPiece, lastPiece)
        while (p <= lastPiece && have(p)) p++ // have() checks the local bitmap first, so this is cheap
        val end = minOf(size, p.toLong() * pieceLen - base)
        return maxOf(end, from)
    }

    // ---- events from the alert thread ----------------------------------------------------------

    @Synchronized
    fun onPieceFinished(index: Int) {
        if (!closed && index in firstPiece..lastPiece) present.set(index)
    }

    fun onChecked() {
        val rebuild = synchronized(this) {
            if (checking) { checking = false; needsReinit = true; true } else false
        }
        // The recheck made libtorrent forget the window (every piece of the file is wanted again). Put it back right
        // away: waiting for a read to do it left the whole file downloading whenever the player was not reading at
        // that moment (stalled, dead, or its request was replaced by a newer one).
        if (rebuild && !closed) {
            try {
                synchronized(this) { if (needsReinit) reinit(if (readPiece >= 0) readPiece else startPiece) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not rebuild the download window after the recheck: ${e.message}")
            }
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
        initPriorities(first)
        val lo = maxOf(firstPiece, first - lagPieces - behind)
        for (p in lo..minOf(lastPiece, first + ahead)) if (handle.havePiece(p)) present.set(p) // survivors
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
        readPiece = first
        val waitStart = System.currentTimeMillis()
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
                } else if (have(first)) {
                    break
                } else {
                    describeWait(first, waitStart)
                }
                lock.withLock { cond.await(500, TimeUnit.MILLISECONDS) } // woken by piece/check alerts
            }
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        }
        waitText = null
        val wanted = global + length
        var end = (first + 1).toLong() * pieceLen
        var p = first + 1
        while (end < wanted && p <= lastPiece && have(p)) { end += pieceLen; p++ }
        return minOf(length.toLong(), end - global).toInt()
    }

    override fun close() {
        closed = true
        signal()
    }
}
