package com.noapmat.tstream.ui

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noapmat.tstream.App
import com.noapmat.tstream.AppSettings
import com.noapmat.tstream.R
import com.noapmat.tstream.cache.PositionStore
import com.noapmat.tstream.databinding.ActivityPlayerBinding
import com.noapmat.tstream.player.MpvConfig
import com.noapmat.tstream.player.MpvFlags
import com.noapmat.tstream.player.MpvFonts
import com.noapmat.tstream.player.MpvRuntime
import com.noapmat.tstream.streaming.StreamServer
import com.noapmat.tstream.torrent.FileStream
import com.noapmat.tstream.torrent.Fmt
import com.noapmat.tstream.torrent.OrientationPicker
import com.noapmat.tstream.torrent.TorrentFileMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Plays the torrent through libmpv: hardware decoding through MediaCodec with a software fallback (10-bit HEVC...),
 * libass for real ASS/SSA rendering, and the user's own mpv options on top (Settings -> Advanced mpv options).
 * mpv reads the file from a local HTTP server ([StreamServer]) that waits for torrent pieces.
 *
 * All libmpv calls go through [MpvRuntime]. The player state is read by polling a handful of mpv properties four
 * times a second (off the UI thread) instead of subscribing to events.
 */
class PlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var b: ActivityPlayerBinding
    private lateinit var audio: AudioManager
    private val ui = Handler(Looper.getMainLooper())

    private var server: StreamServer? = null
    private var fileStream: FileStream? = null
    private var job: Job? = null

    private var error: String? = null
    private var mpvNotes: List<String> = emptyList() // options mpv refused / lines of the custom text that were skipped
    private var customFlagsOn = false
    private var bufferPct = 0f
    private var aheadMs = 0L // how much video beyond the playhead is already downloaded
    private var playing = false
    private var ended = false
    @Volatile private var released = false
    private var mpvFailed = false       // mpv could not open the file or stopped by itself: this player cannot be revived
    private var engineClosed = false
    private var orientationChosen = false
    private var controlsVisible = true
    private var locked = false
    private var userSeeking = false
    private var fastForwarding = false
    private var speedBeforeHold = 1.0
    private var curSpeed = 1.0
    private var volumeAccum = 0f
    private var fileIndex = -1
    private var title = ""
    private var rememberPosition = true

    // mpv + surface (main thread)
    private var mpvStarted = false      // start() was called: a core may exist and must be closed
    private var surfaceReady = false
    private var pendingUrl: String? = null // waiting for a surface to load into
    private var voInUse = "gpu"
    private var started = false         // mpv reported a position: the file is open
    private var lastTimeMs = 0L
    private var lastDurMs = 0L
    private var idleStreak = 0
    private var loadedAt = 0L

    private val hideHud = Runnable { b.hud.visibility = View.GONE }
    private val hideLeft = Runnable { b.seekLeft.visibility = View.GONE }
    private val hideRight = Runnable { b.seekRight.visibility = View.GONE }
    private val hideUnlock = Runnable { b.btnUnlock.visibility = View.GONE }
    private val autoHide = Runnable { if (playing && !userSeeking) setControlsVisible(false) }

    private val gestures = object : PlayerGestures.Callbacks {
        override fun onSingleTap() {
            if (locked) { showUnlockButton(); return }
            setControlsVisible(!controlsVisible)
        }

        override fun onDoubleTap(zone: PlayerGestures.Zone) {
            if (locked) return
            when (zone) {
                PlayerGestures.Zone.LEFT -> { seekBy(-10_000); flash(b.seekLeft, hideLeft, "\u221210 seconds") }
                PlayerGestures.Zone.RIGHT -> { seekBy(10_000); flash(b.seekRight, hideRight, "+10 seconds") }
                PlayerGestures.Zone.CENTER -> togglePlay()
            }
        }

        override fun onVolumeScroll(fraction: Float) {
            if (!locked) adjustVolume(fraction)
        }

        override fun onHoldStart() {
            if (locked || !started || released) return
            fastForwarding = true
            speedBeforeHold = curSpeed
            MpvRuntime.setDouble("speed", 2.0)
            // MpvRuntime.setBool("mute", true) // sped-up audio is just noise, and muting skips the time-stretching work
            showHud("2x 》", persistent = true)
        }

        override fun onHoldEnd() {
            if (!fastForwarding) return
            fastForwarding = false
            MpvRuntime.setDouble("speed", speedBeforeHold)
            // MpvRuntime.setBool("mute", false)
            ui.removeCallbacks(hideHud)
            b.hud.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        enterImmersive()
        Fonts.apply(b.root)
        b.info.typeface = Fonts.mono(this)
        audio = getSystemService(AudioManager::class.java)
        volumeControlStream = AudioManager.STREAM_MUSIC
        b.videoSurface.holder.addCallback(this)

        val app = application as App
        val s = app.settings
        val seeding = s.seeding
        rememberPosition = s.rememberPosition
        fileIndex = intent.getIntExtra("index", -1)
        val source = intent.getStringExtra("source")
        // The torrent is closed whenever the player is left, so it may not be loaded now: take the file's
        // name and size from the intent (they are refreshed from the torrent once it is open).
        title = intent.getStringExtra("path") ?: app.engine.files.firstOrNull { it.index == fileIndex }?.path ?: ""
        var fileSize = intent.getLongExtra("size", -1L)

        b.touchLayer.setOnTouchListener(PlayerGestures(b.touchLayer, gestures))
        b.btnPlay.setOnClickListener { togglePlay(); scheduleAutoHide() }
        b.btnSubtitles.setOnClickListener { showSubtitlePicker() }
        b.btnAudio.setOnClickListener { showAudioPicker() }
        b.btnScreenshot.setOnClickListener { takeScreenshot() }
        b.btnLock.setOnClickListener { setLocked(true) }
        b.btnUnlock.setOnClickListener { setLocked(false) }
        b.btnSpeed.setOnClickListener { showSpeedPicker() }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvPos.text = clock(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true; ui.removeCallbacks(autoHide) }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seekTo(sb.progress.toLong())
                userSeeking = false
                scheduleAutoHide()
            }
        })

        job = lifecycleScope.launch {
            // Leaving the player closed the torrent (no background download, cache wiped): open it again now.
            if (!app.engine.isOpen) {
                if (source == null) { b.info.text = "Error: The torrent is not loaded. Go back and open it again"; return@launch }
                b.info.visibility = View.VISIBLE
                b.info.text = "Initializing..."
                try {
                    app.engine.open(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    b.info.text = "Error: ${e.message ?: e.javaClass.simpleName}"; return@launch
                }
            }
            app.engine.files.firstOrNull { it.index == fileIndex }?.let { title = it.path; fileSize = it.size }

            // Where you stopped last time. The size must match, otherwise it is a different file with the same name.
            val saved = if (rememberPosition && title.isNotBlank()) PositionStore.getFull(this@PlayerActivity, title) else null
            val startBytes = if (saved != null && saved.bytes >= 0 && saved.size == fileSize) saved.bytes else -1L
            val stream = try {
                withContext(Dispatchers.IO) { app.engine.openFile(fileIndex, startBytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.info.text = "Error: ${e.message}"; return@launch
            }
            fileStream = stream

            val srv = StreamServer(stream, mimeFor(title)).also { it.start() }
            server = srv

            val resume = saved?.positionMs
            if (resume != null) toast("Resuming from ${clock(resume)}")
            if (!startMpv(srv.url, resume?.let { it / 1000.0 }, s)) return@launch

            var tick = 0
            while (isActive) {
                val st = withContext(Dispatchers.IO) { readState() } // property reads can block: never on the UI thread
                applyState(st)
                val t = lastTimeMs
                val len = lastDurMs
                if (!userSeeking && len > 0) {
                    b.seek.max = len.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    b.seek.progress = t.coerceIn(0L, len).toInt()
                    b.tvPos.text = clock(t)
                    b.tvDur.text = clock(len)
                    if (stream.size > 0) {
                        // bytes -> time with the file's average bitrate (approximate for VBR video)
                        val fromBytes = (t.coerceIn(0L, len).toDouble() / len * stream.size).toLong()
                        val endMs = (stream.bufferedUntil(fromBytes).toDouble() / stream.size * len).toLong()
                        b.seek.secondaryProgress = endMs.coerceIn(t.coerceAtLeast(0L), len).toInt()
                        aheadMs = (endMs - t).coerceAtLeast(0L)
                    }
                }
                if (tick % 4 == 0) {
                    stream.updatePlayback(t, t + MpvConfig.READAHEAD_SECS * 1000L, if (len > 0) len else -1L)
                    val tst = app.engine.stats.value
                    val buffering = bufferPct < 100f && !ended
                    b.info.visibility = if (buffering || error != null || (controlsVisible && !locked)) View.VISIBLE else View.GONE
                    b.info.text = buildString {
                        error?.let { appendLine(it) }
                        mpvNotes.take(4).forEach { appendLine(it) }
                        if (mpvNotes.size > 4) appendLine("... and ${mpvNotes.size - 4} more")
                        if (buffering) {
                            appendLine("${bufferPct.toInt()}% \u2022 waiting for torrent pieces...")
                            stream.waitInfo?.let { appendLine(it) }
                            if (tst.peers == 0) appendLine("No peers available yet.")
                        }
                        if (aheadMs > 0) appendLine("Buffered ${clock(aheadMs)} ahead")
                        append("\u2193 ${Fmt.rate(tst.downBps)}  ")
                        if (seeding) append("\u2191 ${Fmt.rate(tst.upBps)}  ") // upload only shown while seeding is on
                        append("[P:${tst.peers} | S:${tst.seeds}]")
                    }
                }
                if (++tick % 40 == 0) savePosition()
                delay(250)
            }
        }
    }

    // ---- libmpv -------------------------------------------------------------------------------------------

    /**
     * Starts mpv with the app's own options followed by the user's custom ones (so theirs win), then loads [url]
     * as soon as a surface exists. If mpv refuses to start with the custom options, it is started again without
     * them, so a typo in Settings can never lock the player out. Returns false when mpv cannot start at all.
     */
    private suspend fun startMpv(url: String, resumeSecs: Double?, s: AppSettings): Boolean {
        val user = if (s.mpvFlagsEnabled) MpvFlags.parse(s.mpvFlags) else MpvFlags.Parsed(emptyList(), emptyList())
        val base = MpvConfig.defaults(s, MpvFonts.ensure(this), resumeSecs)
        val notes = ArrayList<String>()
        user.problems.forEach { notes += "mpv options, line ${it.line}: ${it.reason}" }

        var withUser = user.options.isNotEmpty()
        var rejected: List<String>? = null
        mpvStarted = true // from here on a core may exist: stopPlayback() must close it
        try {
            rejected = runStart(base, user.options.map { it.name to it.value })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (withUser) {
                withUser = false
                try {
                    rejected = runStart(base, emptyList())
                    notes += "mpv would not start with your custom options, so they were ignored"
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Throwable) {
                    b.info.text = "Error: ${e2.message ?: e2.javaClass.simpleName}"
                    return false
                }
            } else {
                b.info.text = "Error: ${e.message ?: e.javaClass.simpleName}"
                return false
            }
        }
        if (rejected == null || released) return false // closed while starting

        voInUse = if (withUser) MpvConfig.effectiveVo(user.options) else "gpu"
        customFlagsOn = withUser
        rejected.forEach { notes += "mpv ignored: $it" }
        mpvNotes = notes

        if (surfaceReady) attachAndLoad(url) else pendingUrl = url
        return true
    }

    private suspend fun runStart(base: List<Pair<String, String>>, user: List<Pair<String, String>>): List<String>? =
        withContext(Dispatchers.IO) { MpvRuntime.start(this@PlayerActivity, base + user) { released } }

    private fun attachAndLoad(url: String) {
        val holder = b.videoSurface.holder
        MpvRuntime.attachSurface(holder.surface)
        val r = holder.surfaceFrame
        if (r.width() > 0 && r.height() > 0) MpvRuntime.setString("android-surface-size", "${r.width()}x${r.height()}")
        // forces mpv to draw subtitles / the last frame into the surface even when it would otherwise not
        MpvRuntime.setOption("force-window", "yes")
        MpvRuntime.command("loadfile", url)
        loadedAt = SystemClock.elapsedRealtime()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (!mpvStarted || released) return
        val url = pendingUrl
        if (url != null) {
            pendingUrl = null
            attachAndLoad(url)
        } else {
            // the surface came back after Home / another app: give mpv its video output again
            MpvRuntime.attachSurface(holder.surface)
            MpvRuntime.setOption("force-window", "yes")
            MpvRuntime.setString("vo", voInUse)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (mpvStarted && !released) MpvRuntime.setString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (!mpvStarted || released) return
        // Android destroys the surface while the screen is stopped: stop drawing into it and let go of it
        MpvRuntime.setString("vo", "null")
        MpvRuntime.setOption("force-window", "no")
        MpvRuntime.detachSurface()
    }

    private class MpvState(
        val timeMs: Long?, val durMs: Long?, val paused: Boolean?, val stalled: Boolean?, val cachePct: Int?,
        val eof: Boolean?, val idle: Boolean?, val width: Int?, val height: Int?, val speed: Double?,
    )

    /** One snapshot of everything the UI needs; a property mpv can't give yet is simply null. Blocking: IO thread. */
    private fun readState() = MpvState(
        timeMs = MpvRuntime.getDouble("time-pos")?.let { (it * 1000).toLong() },
        durMs = MpvRuntime.getDouble("duration")?.let { (it * 1000).toLong() },
        paused = MpvRuntime.getBool("pause"),
        stalled = MpvRuntime.getBool("paused-for-cache"),
        cachePct = MpvRuntime.getInt("cache-buffering-state"),
        eof = MpvRuntime.getBool("eof-reached"),
        idle = MpvRuntime.getBool("idle-active"),
        width = MpvRuntime.getInt("dwidth"),   // video size after aspect ratio and rotation: what is really shown
        height = MpvRuntime.getInt("dheight"),
        speed = MpvRuntime.getDouble("speed"),
    )

    private fun applyState(st: MpvState) {
        if (released) return
        st.timeMs?.let { lastTimeMs = it.coerceAtLeast(0L) }
        st.durMs?.let { lastDurMs = it.coerceAtLeast(0L) }
        st.speed?.let { curSpeed = it }
        if (st.timeMs != null && !started) { started = true; scheduleAutoHide() }

        bufferPct = when {
            !started -> 0f
            st.stalled == true -> (st.cachePct ?: 0).toFloat()
            else -> 100f
        }
        val nowPlaying = started && st.paused == false && !ended
        if (nowPlaying != playing) { playing = nowPlaying; updatePlayIcon(); if (playing) scheduleAutoHide() }

        // End of file: mpv keeps the last frame up (keep-open) and flags it; if the user switched keep-open off,
        // it goes idle right at the end instead.
        val nearEnd = lastDurMs > 0 && lastTimeMs >= lastDurMs - 2500
        val atEnd = started && (st.eof == true || (st.idle == true && nearEnd))
        if (atEnd && !ended) {
            ended = true
            playing = false
            updatePlayIcon()
            if (title.isNotBlank()) PositionStore.remove(this, title)
            setControlsVisible(true)
        } else if (ended && st.eof == false && st.idle != true) {
            ended = false // sought back or restarted
        }

        // mpv went idle without a file after loading one, and not at the end: it failed (or lost the stream)
        val idleNow = st.idle == true && !atEnd && loadedAt > 0 && SystemClock.elapsedRealtime() - loadedAt > 2000
        idleStreak = if (idleNow) idleStreak + 1 else 0
        if (idleStreak >= 3 && !mpvFailed) {
            mpvFailed = true
            error = "mpv could not play this file." +
                if (customFlagsOn) " Your custom mpv options may be the cause (Settings > Advanced mpv options)." else ""
        }

        val w = st.width
        val h = st.height
        if (!orientationChosen && w != null && h != null && w > 0 && h > 0) chooseOrientation(w.toFloat() / h)
    }

    private fun togglePlay() {
        if (!started || released) return
        if (ended) {
            ended = false
            MpvRuntime.command("seek", "0", "absolute")
            MpvRuntime.setBool("pause", false)
            return
        }
        if (playing) {
            MpvRuntime.setBool("pause", true); playing = false; updatePlayIcon(); showHud("Paused")
        } else {
            MpvRuntime.setBool("pause", false); playing = true; updatePlayIcon(); showHud("Playing")
        }
    }

    private fun updatePlayIcon() {
        b.btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---- subtitles / audio / speed -----------------------------------------------------------------------

    private class Track(val id: Int, val label: String, val selected: Boolean)

    private class PickEntry(val label: String, val selected: Boolean, val action: () -> Unit)

    /** The video's tracks of one type ("sub" or "audio") from mpv's track list. Blocking: IO thread. */
    private fun readTracks(type: String): List<Track> {
        val n = MpvRuntime.getInt("track-list/count") ?: return emptyList()
        val out = ArrayList<Track>()
        for (i in 0 until n) {
            if (MpvRuntime.getString("track-list/$i/type") != type) continue
            val id = MpvRuntime.getInt("track-list/$i/id") ?: continue
            val lang = MpvRuntime.getString("track-list/$i/lang")
            val name = MpvRuntime.getString("track-list/$i/title")
            val codec = MpvRuntime.getString("track-list/$i/codec")
            val external = MpvRuntime.getBool("track-list/$i/external") == true
            val label = listOfNotNull(
                lang?.uppercase(Locale.ROOT), name, codec?.let { "($it)" }, if (external) "[external]" else null,
            ).joinToString(" ").ifBlank { "Track $id" }
            out += Track(id, label, MpvRuntime.getBool("track-list/$i/selected") == true)
        }
        return out
    }

    /** Subtitle files of this torrent, those next to the video first. */
    private fun subtitleFiles(): List<TorrentFileMeta> {
        val dir = title.substringBeforeLast('/', "")
        return (application as App).engine.files.filter { it.subtitle }
            .sortedWith(compareByDescending<TorrentFileMeta> { it.path.substringBeforeLast('/', "") == dir }.thenBy { it.path })
    }

    private fun showSubtitlePicker() {
        if (!started || released) return
        lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) { readTracks("sub") }
            // embedded tracks (SRT, ASS ... inside the video) are kept; files from the torrent are added to them
            val entries = ArrayList<PickEntry>()
            entries += PickEntry("Off", tracks.none { it.selected }) { MpvRuntime.setString("sid", "no") }
            for (t in tracks) entries += PickEntry(t.label, t.selected) { MpvRuntime.setString("sid", t.id.toString()) }
            for (f in subtitleFiles()) entries += PickEntry(f.path.substringAfterLast('/') + " (file)", false) { useExternalSubtitle(f) }
            if (entries.size <= 1) { toast("No subtitles found in this video or torrent"); return@launch }
            MaterialAlertDialogBuilder(this@PlayerActivity)
                .setTitle("Subtitles")
                .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), entries.indexOfFirst { it.selected }.coerceAtLeast(0)) { d, which ->
                    entries[which].action()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Downloads the subtitle file from the torrent, then hands it to mpv (embedded tracks stay available). */
    private fun useExternalSubtitle(f: TorrentFileMeta) {
        toast("Downloading subtitle\u2026")
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { (application as App).engine.fetchFile(f.index) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't download the subtitle: ${e.message}"); return@launch
            }
            MpvRuntime.command("sub-add", file.absolutePath, "select")
        }
    }

    private fun showAudioPicker() {
        if (!started || released) return
        lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) { readTracks("audio") }
            if (tracks.isEmpty()) { toast("No audio tracks yet"); return@launch }
            MaterialAlertDialogBuilder(this@PlayerActivity)
                .setTitle("Audio")
                .setSingleChoiceItems(tracks.map { it.label }.toTypedArray(), tracks.indexOfFirst { it.selected }.coerceAtLeast(0)) { d, which ->
                    MpvRuntime.setString("aid", tracks[which].id.toString())
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showSpeedPicker() {
        if (!started || released) return
        val speeds = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
        val labels = speeds.map { (if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString()) + "\u00D7" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == curSpeed }.coerceAtLeast(2)) { d, which ->
                curSpeed = speeds[which]
                MpvRuntime.setDouble("speed", curSpeed)
                b.btnSpeed.text = labels[which]
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- gestures / HUD ------------------------------------------------------------------------------------

    private fun secs(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)

    private fun seekTo(ms: Long) {
        if (!started || released) return
        MpvRuntime.command("seek", secs(ms.coerceAtLeast(0L)), "absolute")
    }

    private fun seekBy(deltaMs: Long) {
        if (!started || released) return
        MpvRuntime.command("seek", secs(deltaMs), "relative")
    }

    private fun adjustVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeAccum += fraction * max * 1.2f
        val steps = volumeAccum.toInt()
        if (steps != 0) {
            volumeAccum -= steps
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (now + steps).coerceIn(0, max), 0)
        }
        showHud("Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%")
    }

    private fun showHud(text: String, persistent: Boolean = false) {
        b.hud.text = text
        b.hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud)
        if (!persistent) ui.postDelayed(hideHud, 900)
    }

    private fun flash(v: TextView, hide: Runnable, text: String) {
        v.text = text
        v.visibility = View.VISIBLE
        ui.removeCallbacks(hide)
        ui.postDelayed(hide, 600)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible && !locked
        val v = if (controlsVisible) View.VISIBLE else View.GONE
        b.controlsRow.visibility = v
        b.bottomBar.visibility = v
        if (controlsVisible) scheduleAutoHide() else ui.removeCallbacks(autoHide)
    }

    private fun scheduleAutoHide() {
        ui.removeCallbacks(autoHide)
        if (controlsVisible && !locked) ui.postDelayed(autoHide, 4000)
    }

    private fun setLocked(on: Boolean) {
        locked = on
        setControlsVisible(!on)
        if (on) showUnlockButton() else { ui.removeCallbacks(hideUnlock); b.btnUnlock.visibility = View.GONE }
    }

    private fun showUnlockButton() {
        b.btnUnlock.visibility = View.VISIBLE
        ui.removeCallbacks(hideUnlock)
        ui.postDelayed(hideUnlock, 3000)
    }

    // ---- screenshot ----------------------------------------------------------------------------------------

    /**
     * Asks mpv to write the current frame as a PNG at the video's own resolution: just the picture (no letterbox
     * bars), with the visible subtitle drawn in only when "Subtitles in screenshots" is on in Settings. The file is
     * then copied to Pictures/Tstream.
     */
    private fun takeScreenshot() {
        if (!started || released) { toast("Nothing to capture yet"); return }
        val pos = lastTimeMs
        val mode = if ((application as App).settings.screenshotSubtitles) "subtitles" else "video"
        val tmp = File(cacheDir, "shot-${SystemClock.elapsedRealtime()}.png")
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) {
                try {
                    MpvRuntime.command("screenshot-to-file", tmp.absolutePath, mode)
                    if (tmp.length() > 0) Screenshots.save(this@PlayerActivity, tmp, title.ifBlank { "video" }, pos) else null
                } finally {
                    tmp.delete()
                }
            }
            toast(if (where != null) "Saved to $where" else "Couldn't capture this frame")
        }
    }

    // ---- orientation / immersive ---------------------------------------------------------------------------

    /**
     * Picks landscape or portrait from the video's real aspect ratio (as mpv shows it: pixel aspect and rotation
     * included): whichever orientation makes the picture larger on this screen. Done once per playback.
     */
    private fun chooseOrientation(ratio: Float) {
        if (orientationChosen) return
        val dm = resources.displayMetrics
        val choice = OrientationPicker.pick(
            ratio,
            maxOf(dm.widthPixels, dm.heightPixels).toFloat(),
            minOf(dm.widthPixels, dm.heightPixels).toFloat(),
        )
        requestedOrientation = when (choice) {
            OrientationPicker.Choice.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationPicker.Choice.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        orientationChosen = true
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------

    private fun savePosition() {
        if (released || ended || !started || !rememberPosition || title.isBlank()) return
        PositionStore.save(
            this, title, lastTimeMs, lastDurMs,
            bytes = fileStream?.estimatedPlayheadBytes() ?: -1L,
            size = fileStream?.size ?: -1L,
        )
    }

    /**
     * Back from Home / another app. The video surface is gone while the screen is stopped; surfaceDestroyed /
     * surfaceCreated hand it back to mpv by themselves. If mpv died while we were away (its connection to the
     * local stream server broke, ...), the player can't be revived: restart it the way opening the file again
     * does, from the saved position.
     */
    override fun onStart() {
        super.onStart()
        if (released) return
        if (mpvFailed && !ended) recreate() // onStop saved the position; onCreate reopens the torrent there
    }

    override fun onStop() {
        super.onStop()
        if (released || !started) return
        MpvRuntime.setBool("pause", true)
        playing = false
        updatePlayIcon()
        savePosition()
    }

    /**
     * Leaving the player (back button) stops the stream and deletes the downloaded data right away.
     * Done in onPause: MainActivity resumes before onDestroy runs, and it needs to see the torrent closed.
     */
    override fun onPause() {
        super.onPause()
        if (isFinishing) stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    private fun stopPlayback() {
        job?.cancel()
        ui.removeCallbacksAndMessages(null)
        savePosition()
        // 1. The torrent first. Nothing below may delay or prevent stopping the download (a hung player teardown
        //    used to leave the torrent running in the background, filling the storage).
        if (!engineClosed) {
            engineClosed = true
            (application as App).engine.closeTorrent()
        }
        server?.stop()
        server = null
        // 2. mpv. Destroying the core can block while it waits on a stalled read, so MpvRuntime does it on its own
        //    thread; every call made after this point is a no-op.
        if (!released) {
            released = true
            b.videoSurface.holder.removeCallback(this)
            if (mpvStarted) MpvRuntime.close()
        }
    }

    private fun mimeFor(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
