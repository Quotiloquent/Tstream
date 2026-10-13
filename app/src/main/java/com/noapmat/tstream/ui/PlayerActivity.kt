package com.noapmat.tstream.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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
import com.noapmat.tstream.streaming.StreamServer
import com.noapmat.tstream.torrent.FileStream
import com.noapmat.tstream.torrent.Fmt
import com.noapmat.tstream.torrent.OrientationPicker
import com.noapmat.tstream.torrent.PictureBox
import com.noapmat.tstream.torrent.TorrentFileMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import kotlin.math.roundToInt

/** Longest side of a saved screenshot, so a 4K+ video can't run the phone out of memory. */
private const val MAX_SHOT_EDGE = 4096f

/**
 * Plays the torrent through libVLC: software decoders when the phone's chip can't decode a format
 * (10-bit HEVC...), libass for real ASS/SSA rendering. VLC reads the file from a local HTTP server
 * ([StreamServer]) that waits for torrent pieces.
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private lateinit var audio: AudioManager
    private val ui = Handler(Looper.getMainLooper())

    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var server: StreamServer? = null
    private var fileStream: FileStream? = null
    private var job: Job? = null

    private var error: String? = null
    private var bufferPct = 0f
    private var aheadMs = 0L // how much video beyond the playhead is already downloaded
    private var playing = false
    private var ended = false
    private var released = false
    private var engineClosed = false
    private var orientationChosen = false
    private var controlsVisible = true
    private var locked = false
    private var userSeeking = false
    private var fastForwarding = false
    private var speedBeforeHold = 1f
    private var volumeBeforeHold = 100
    private var volumeAccum = 0f
    private var fileIndex = -1
    private var title = ""
    private var rememberPosition = true

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
            val m = mp ?: return
            if (locked) return
            fastForwarding = true
            speedBeforeHold = m.getRate()
            volumeBeforeHold = m.getVolume()
            m.setRate(2f)
            m.setVolume(0) // no time-stretching at 2x: saves CPU, and sped-up audio is just noise anyway
            showHud("2x 》", persistent = true)
        }

        override fun onHoldEnd() {
            if (!fastForwarding) return
            fastForwarding = false
            mp?.setRate(speedBeforeHold)
            mp?.setVolume(volumeBeforeHold)
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
                mp?.setTime(sb.progress.toLong())
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

            val vlc = createLibVlc(s)
            libVlc = vlc
            val m = MediaPlayer(vlc)
            mp = m
            when (s.audioOutput) {
                1 -> m.setAudioOutput("audiotrack")
                2 -> m.setAudioOutput("opensles")
            }
            m.attachViews(b.videoLayout, null, true, false)
            m.setEventListener(object : MediaPlayer.EventListener {
                override fun onEvent(event: MediaPlayer.Event) {
                    val type = event.type
                    val buffering = event.buffering
                    ui.post { onVlcEvent(type, buffering) }
                }
            })

            val media = Media(vlc, Uri.parse(srv.url))
            // hardware first, software when the chip can't do it (or software only when switched off in Settings)
            media.setHWDecoderEnabled(s.hardwareDecoding, false)
            media.addOption(":network-caching=2500")
            val resume = saved?.positionMs
            if (resume != null) {
                media.addOption(":start-time=${resume / 1000}")
                toast("Resuming from ${clock(resume)}")
            }
            m.setMedia(media)
            media.release()
            m.play()

            var tick = 0
            while (isActive) {
                val t = m.getTime()
                val len = m.getLength()
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
                if (tick % 2 == 0) {
                    stream.updatePlayback(t, t + 12_000, if (len > 0) len else -1L)
                    val st = app.engine.stats.value
                    val buffering = bufferPct < 100f && !ended
                    b.info.visibility = if (buffering || error != null || (controlsVisible && !locked)) View.VISIBLE else View.GONE
                    b.info.text = buildString {
                        error?.let { appendLine(it) }
                        if (buffering) {
                            appendLine("${bufferPct.toInt()}% • waiting for torrent pieces...")
                            stream.waitInfo?.let { appendLine(it) }
                            if (st.peers == 0) appendLine("No peers available yet.")
                        }
                        if (aheadMs > 0) appendLine("Buffered ${clock(aheadMs)} ahead")
                        append("\u2193 ${Fmt.rate(st.downBps)}  ")
                        if (seeding) append("\u2191 ${Fmt.rate(st.upBps)}  ") // upload only shown while seeding is on
                        append("[P:${st.peers} | S:${st.seeds}]")
                    }
                }
                if (++tick % 20 == 0) savePosition()
                delay(500)
            }
        }
    }

    // ---- libVLC -------------------------------------------------------------------------------------------

    private fun createLibVlc(s: AppSettings): LibVLC {
        // These are the options the real VLC app uses, so they are known to be accepted.
        val core = arrayListOf(
            "--audio-time-stretch",
            // VLC's own app always uses soxr; the library default resampler sounds noticeably worse
            "--audio-resampler=soxr",
            // fast mode: skip the deblocking filter on non-key frames (what VLC picks for slow phones);
            // normal: only on non-reference frames, which is invisible
            "--avcodec-skiploopfilter=${if (s.fastDecode) 3 else 1}",
            "--freetype-rel-fontsize=${s.subtitleRelSize}", // plain subtitles (ASS keeps the script's own sizes)
        )
        val sessionId = audio.generateAudioSessionId()
        if (sessionId > 0) core += "--audiotrack-session-id=$sessionId"
        val style = arrayListOf(
            "--freetype-background-opacity=0", // no black box behind text
            "--freetype-outline-thickness=4",
            "--freetype-outline-color=0",
            "--freetype-outline-opacity=255",
            "--freetype-shadow-opacity=128",
        )
        // "hurry up" lets the decoder drop *parts* of frames when the CPU is late, which is what smeared the
        // picture at 2x. With it off, late frames are dropped whole instead.
        val picky = arrayListOf("--no-avcodec-hurry-up")

        // Never fail just because of a cosmetic or newer option: try the full set, then fewer options.
        val attempts = listOf(core + style + picky, core + style, core, arrayListOf("--audio-time-stretch"))
        var last: Exception? = null
        for (opts in attempts) {
            try {
                return LibVLC(this, ArrayList(opts))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("libVLC could not start")
    }

    private fun onVlcEvent(type: Int, buffering: Float) {
        when (type) {
            MediaPlayer.Event.Buffering -> bufferPct = buffering
            MediaPlayer.Event.Playing -> { playing = true; bufferPct = 100f; updatePlayIcon(); chooseOrientation(); scheduleAutoHide() }
            MediaPlayer.Event.Paused -> { playing = false; updatePlayIcon() }
            MediaPlayer.Event.Stopped -> { playing = false; updatePlayIcon() }
            MediaPlayer.Event.EndReached -> {
                ended = true
                playing = false
                updatePlayIcon()
                if (title.isNotBlank()) PositionStore.remove(this, title)
                setControlsVisible(true)
            }
            MediaPlayer.Event.EncounteredError -> error = "VLC could not play this file."
            MediaPlayer.Event.Vout, MediaPlayer.Event.ESAdded -> chooseOrientation()
        }
    }

    private fun togglePlay() {
        val m = mp ?: return
        if (ended) { ended = false; m.setTime(0); m.play(); return }
        if (m.isPlaying) { m.pause(); showHud("Paused") } else { m.play(); showHud("Playing") }
    }

    private fun updatePlayIcon() {
        b.btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---- subtitles / audio / speed -----------------------------------------------------------------------

    /** Subtitle files of this torrent, those next to the video first. */
    private fun subtitleFiles(): List<TorrentFileMeta> {
        val dir = title.substringBeforeLast('/', "")
        return (application as App).engine.files.filter { it.subtitle }
            .sortedWith(compareByDescending<TorrentFileMeta> { it.path.substringBeforeLast('/', "") == dir }.thenBy { it.path })
    }

    private fun showSubtitlePicker() {
        val m = mp ?: return
        class Entry(val label: String, val selected: Boolean, val action: () -> Unit)

        val entries = ArrayList<Entry>()
        val current = m.getSpuTrack()
        // embedded tracks (SRT, ASS ... inside the video) are kept; VLC lists "Disable" as id -1
        m.getSpuTracks()?.forEach { t ->
            entries += Entry(if (t.id == -1) "Off" else t.name, t.id == current) { m.setSpuTrack(t.id) }
        }
        if (entries.none { it.label == "Off" }) entries.add(0, Entry("Off", current == -1) { m.setSpuTrack(-1) })
        for (f in subtitleFiles()) entries += Entry(f.path.substringAfterLast('/') + " (file)", false) { useExternalSubtitle(f) }
        if (entries.size <= 1) { toast("No subtitles found in this video or torrent"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), entries.indexOfFirst { it.selected }.coerceAtLeast(0)) { d, which ->
                entries[which].action()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Downloads the subtitle file from the torrent, then hands it to VLC (embedded tracks stay available). */
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
            mp?.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
        }
    }

    private fun showAudioPicker() {
        val m = mp ?: return
        val tracks = m.getAudioTracks()
        if (tracks == null || tracks.isEmpty()) { toast("No audio tracks yet"); return }
        val current = m.getAudioTrack()
        MaterialAlertDialogBuilder(this)
            .setTitle("Audio")
            .setSingleChoiceItems(tracks.map { if (it.id == -1) "Off" else it.name }.toTypedArray(), tracks.indexOfFirst { it.id == current }.coerceAtLeast(0)) { d, which ->
                m.setAudioTrack(tracks[which].id)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSpeedPicker() {
        val m = mp ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { (if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()) + "\u00D7" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == m.getRate() }.coerceAtLeast(2)) { d, which ->
                m.setRate(speeds[which])
                b.btnSpeed.text = labels[which]
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- gestures / HUD ------------------------------------------------------------------------------------

    private fun seekBy(deltaMs: Long) {
        val m = mp ?: return
        val len = m.getLength()
        val target = m.getTime() + deltaMs
        m.setTime(if (len > 0) target.coerceIn(0L, len) else target.coerceAtLeast(0L))
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

    private fun allSurfaces(v: View, out: MutableList<SurfaceView> = ArrayList()): List<SurfaceView> {
        if (v is SurfaceView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) allSurfaces(v.getChildAt(i), out)
        return out
    }

    private fun surfaceName(v: View): String =
        if (v.id != View.NO_ID) try { resources.getResourceEntryName(v.id) } catch (e: Exception) { "" } else ""

    // VLCVideoLayout has two surfaces: the video and the subtitles overlay (the video one comes first). The names
    // are tried first; the order is the fallback in case resource names were shortened by the build.
    private fun findVideoSurface(): SurfaceView? {
        val all = allSurfaces(b.videoLayout)
        return all.firstOrNull { surfaceName(it) == "surface_video" } ?: all.firstOrNull()
    }

    private fun findSubtitleSurface(video: SurfaceView): SurfaceView? {
        val others = allSurfaces(b.videoLayout).filter { it !== video }
        return others.firstOrNull { surfaceName(it) == "surface_subtitles" } ?: others.firstOrNull()
    }

    /** Size of the picture as the video really is (pixel aspect ratio and rotation applied), capped at [MAX_SHOT_EDGE]. */
    private fun nativeFrameSize(m: MediaPlayer, fallback: SurfaceView): Pair<Int, Int> {
        val t = m.getCurrentVideoTrack()
        if (t == null || t.width <= 0 || t.height <= 0) return fallback.width to fallback.height
        var w = t.width.toFloat() * (if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f)
        var h = t.height.toFloat()
        if (t.orientation >= 4) { val x = w; w = h; h = x } // libvlc orientations 4..7 are rotated by 90 degrees
        val k = minOf(1f, MAX_SHOT_EDGE / maxOf(w, h))
        return maxOf(1, (w * k).roundToInt()) to maxOf(1, (h * k).roundToInt())
    }

    /** Aspect ratio (w/h) the picture is shown with (pixel aspect and rotation applied), or 0 when not known yet. */
    private fun displayAspect(m: MediaPlayer): Float {
        val t = m.getCurrentVideoTrack() ?: return 0f
        if (t.width <= 0 || t.height <= 0) return 0f
        var r = t.width.toFloat() * (if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f) / t.height
        if (t.orientation >= 4) r = 1f / r
        return r
    }

    /**
     * Saves the current frame at the video's own resolution. PixelCopy reads the surface's buffer (not the screen)
     * and scales it to the bitmap it is given, so a bitmap of the video's size gives the video's resolution
     * instead of the smaller size the picture has on screen. The surface can be larger than the picture (libVLC
     * draws the black bars into it), so the copy is cut down to the picture's box first; otherwise the bars would
     * be squeezed into the frame together with the picture. With "Subtitles in screenshots" on, the subtitle
     * overlay is copied the same way and drawn on top.
     */
    private fun takeScreenshot() {
        val m = mp ?: return
        val pos = m.getTime()
        val video = findVideoSurface()
        if (video == null || video.width <= 0 || video.height <= 0) { toast("Nothing to capture yet"); return }
        val (w, h) = nativeFrameSize(m, video)
        val box = PictureBox.fit(video.width, video.height, displayAspect(m))
        val subs = if ((application as App).settings.screenshotSubtitles) findSubtitleSurface(video) else null
        val main = Handler(Looper.getMainLooper())
        val withSubtitles = { frame: Bitmap ->
            if (subs == null || subs.width <= 0 || subs.height <= 0) saveShot(frame, pos)
            else copySubtitles(video, subs, frame, box, main) { saveShot(frame, pos) }
        }
        val barsInSurface = box.width != video.width || box.height != video.height
        if (!barsInSurface) {
            val frame = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory for a full-size screenshot"); return
            }
            PixelCopy.request(
                video, frame,
                PixelCopy.OnPixelCopyFinishedListener { result ->
                    if (result == PixelCopy.SUCCESS) withSubtitles(frame) else toast("Couldn't capture this frame")
                },
                main,
            )
            return
        }
        // Bars inside the surface: copy the whole surface at a scale that gives the picture its native size
        // (capped a little above MAX_SHOT_EDGE, the bars add to the size), then keep only the picture.
        var k = w.toFloat() / box.width
        val cap = MAX_SHOT_EDGE * 1.25f
        val edge = maxOf(video.width, video.height) * k
        if (edge > cap) k *= cap / edge
        val full = try {
            Bitmap.createBitmap(
                (video.width * k).roundToInt().coerceAtLeast(1),
                (video.height * k).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        } catch (e: OutOfMemoryError) {
            toast("Not enough memory for a full-size screenshot"); return
        }
        PixelCopy.request(
            video, full,
            PixelCopy.OnPixelCopyFinishedListener { result ->
                if (result != PixelCopy.SUCCESS) {
                    full.recycle()
                    toast("Couldn't capture this frame")
                    return@OnPixelCopyFinishedListener
                }
                val x = (box.left * k).roundToInt().coerceIn(0, full.width - 1)
                val y = (box.top * k).roundToInt().coerceIn(0, full.height - 1)
                val cw = (box.width * k).roundToInt().coerceIn(1, full.width - x)
                val ch = (box.height * k).roundToInt().coerceIn(1, full.height - y)
                val frame = try {
                    Bitmap.createBitmap(full, x, y, cw, ch)
                } catch (e: OutOfMemoryError) {
                    null
                }
                full.recycle()
                if (frame == null) toast("Not enough memory for a full-size screenshot") else withSubtitles(frame)
            },
            main,
        )
    }

    /**
     * Draws the subtitle overlay onto [frame], mapped the same way as the picture (the part of the video surface
     * inside [box] is what [frame] shows), then calls [done]; a failure just skips the subtitles.
     */
    private fun copySubtitles(video: SurfaceView, subs: SurfaceView, frame: Bitmap, box: PictureBox.Box, main: Handler, done: () -> Unit) {
        val sx = frame.width.toFloat() / box.width
        val sy = frame.height.toFloat() / box.height
        val overlay = try {
            Bitmap.createBitmap(
                (subs.width * sx).roundToInt().coerceAtLeast(1),
                (subs.height * sy).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        } catch (e: OutOfMemoryError) {
            done(); return
        }
        PixelCopy.request(
            subs, overlay,
            PixelCopy.OnPixelCopyFinishedListener { result ->
                if (result == PixelCopy.SUCCESS) {
                    // the overlay normally covers exactly the video surface; the offset keeps it right if it doesn't
                    val v = IntArray(2)
                    val s = IntArray(2)
                    video.getLocationOnScreen(v)
                    subs.getLocationOnScreen(s)
                    Canvas(frame).drawBitmap(overlay, (s[0] - v[0] - box.left) * sx, (s[1] - v[1] - box.top) * sy, null)
                }
                overlay.recycle()
                done()
            },
            main,
        )
    }

    private fun saveShot(bmp: Bitmap, pos: Long) {
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { Screenshots.save(this@PlayerActivity, bmp, title.ifBlank { "video" }, pos) }
            toast(if (where != null) "Saved to $where" else "Couldn't save the screenshot")
        }
    }

    // ---- orientation / immersive ---------------------------------------------------------------------------

    /**
     * Picks landscape or portrait from the video's real aspect ratio (pixel aspect and rotation included):
     * whichever orientation makes the picture larger on this screen. Done once per playback.
     */
    private fun chooseOrientation() {
        if (orientationChosen) return
        val t = mp?.getCurrentVideoTrack() ?: return
        if (t.width <= 0 || t.height <= 0) return
        var ratio = t.width.toFloat() * (if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f) / t.height
        if (t.orientation >= 4) ratio = 1f / ratio // libvlc orientations 4..7 are rotated by 90 degrees
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
        val m = mp ?: return
        if (released || ended || !rememberPosition || title.isBlank()) return
        PositionStore.save(
            this, title, m.getTime(), m.getLength(),
            bytes = fileStream?.estimatedPlayheadBytes() ?: -1L,
            size = fileStream?.size ?: -1L,
        )
    }

    override fun onStop() {
        super.onStop()
        if (!released) { mp?.pause(); savePosition() }
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
        // 1. The torrent first. Nothing below may delay or prevent stopping the download (a hung VLC teardown
        //    used to leave the torrent running in the background, filling the storage).
        if (!engineClosed) {
            engineClosed = true
            (application as App).engine.closeTorrent()
        }
        server?.stop()
        server = null
        // 2. VLC. stop()/release() can block while VLC waits on a stalled read, so never on the UI thread.
        if (!released) {
            released = true
            val m = mp
            val v = libVlc
            mp = null
            libVlc = null
            try {
                m?.setEventListener(null)
                m?.detachViews() // touches views: UI thread
            } catch (e: Exception) {
                // already detached
            }
            Thread({
                try {
                    m?.stop()
                    m?.release()
                    v?.release()
                } catch (e: Exception) {
                    // already torn down
                }
            }, "vlc-release").start()
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
