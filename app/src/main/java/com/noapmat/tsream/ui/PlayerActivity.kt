package com.noapmat.tsream.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
import com.noapmat.tsream.App
import com.noapmat.tsream.AppSettings
import com.noapmat.tsream.R
import com.noapmat.tsream.cache.PositionStore
import com.noapmat.tsream.databinding.ActivityPlayerBinding
import com.noapmat.tsream.streaming.StreamServer
import com.noapmat.tsream.torrent.FileStream
import com.noapmat.tsream.torrent.Fmt
import com.noapmat.tsream.torrent.OrientationPicker
import com.noapmat.tsream.torrent.TorrentFileMeta
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
                PlayerGestures.Zone.LEFT -> { seekBy(-10_000); flash(b.seekLeft, hideLeft, "\u221210 s") }
                PlayerGestures.Zone.RIGHT -> { seekBy(10_000); flash(b.seekRight, hideRight, "+10 s") }
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
            m.setRate(2f)
            showHud("2\u00D7 speed", persistent = true)
        }

        override fun onHoldEnd() {
            if (!fastForwarding) return
            fastForwarding = false
            mp?.setRate(speedBeforeHold)
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
        title = app.engine.files.firstOrNull { it.index == fileIndex }?.path ?: ""

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
            val stream = try {
                withContext(Dispatchers.IO) { app.engine.openFile(fileIndex) }
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
            m.attachViews(b.videoLayout, null, true, false)
            m.setEventListener(object : MediaPlayer.EventListener {
                override fun onEvent(event: MediaPlayer.Event) {
                    val type = event.type
                    val buffering = event.buffering
                    ui.post { onVlcEvent(type, buffering) }
                }
            })

            val media = Media(vlc, Uri.parse(srv.url))
            media.setHWDecoderEnabled(false, false) // hardware first, software when the chip can't do it
            media.addOption(":network-caching=2500")
            media.setAudioDigitalOutputEnabled(true)
            val resume = if (rememberPosition && title.isNotBlank()) PositionStore.get(this@PlayerActivity, title) else null
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
                }
                if (tick % 2 == 0) {
                    stream.updatePlayback(t, t + 12_000, if (len > 0) len else -1L)
                    val st = app.engine.stats.value
                    val buffering = bufferPct < 100f && !ended
                    b.info.visibility = if (buffering || error != null || (controlsVisible && !locked)) View.VISIBLE else View.GONE
                    b.info.text = buildString {
                        error?.let { appendLine(it) }
                        if (buffering) {
                            appendLine("BUFFERING ${bufferPct.toInt()}% - waiting for torrent pieces...")
                            stream.waitInfo?.let { appendLine(it) }
                            if (st.peers == 0) appendLine("No peers available yet.")
                        }
                        append("\u2193 ${Fmt.rate(st.downBps)}  ")
                        if (seeding) append("\u2191 ${Fmt.rate(st.upBps)}  ") // upload only shown while seeding is on
                        append("Peers ${st.peers} (seeds ${st.seeds})")
                    }
                }
                if (++tick % 20 == 0) savePosition()
                delay(500)
            }
        }
    }

    // ---- libVLC -------------------------------------------------------------------------------------------

    private fun createLibVlc(s: AppSettings): LibVLC {
        val opts = arrayListOf(
            "--freetype-rel-fontsize=${s.subtitleRelSize}", // plain subtitles (ASS keeps the script's own sizes)
            "--freetype-background-opacity=0",              // no black box behind text
            "--freetype-outline-thickness=4",
            "--freetype-outline-color=0",
            "--freetype-outline-opacity=255",
            "--freetype-shadow-opacity=128",
            "--audio-track-format=s16b",
            "--audio-filter=none",
            "--no-audio-time-stretch"
        )
        if (s.fastDecode) { opts += "--avcodec-skiploopfilter=4"; opts += "--avcodec-fast" }
        return try {
            LibVLC(this, opts)
        } catch (e: Exception) {
            LibVLC(this, arrayListOf("--audio-time-stretch")) // never fail just because of a cosmetic option
        }
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

    private fun findVideoSurface(v: View): SurfaceView? {
        if (v is SurfaceView) {
            // VLCVideoLayout has two surfaces: the video and the subtitles overlay
            val name = if (v.id != View.NO_ID) try { resources.getResourceEntryName(v.id) } catch (e: Exception) { "" } else ""
            if (name == "surface_video") return v
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) findVideoSurface(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun firstSurface(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstSurface(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun takeScreenshot() {
        val m = mp ?: return
        val pos = m.getTime()
        val sv = findVideoSurface(b.videoLayout) ?: firstSurface(b.videoLayout)
        if (sv == null || sv.width <= 0 || sv.height <= 0) { toast("Nothing to capture yet"); return }
        val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            sv, bmp,
            PixelCopy.OnPixelCopyFinishedListener { result ->
                if (result == PixelCopy.SUCCESS) saveShot(bmp, pos) else toast("Couldn't capture this frame")
            },
            Handler(Looper.getMainLooper()),
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
        PositionStore.save(this, title, m.getTime(), m.getLength())
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
        if (!released) {
            savePosition()
            released = true
            val m = mp
            mp = null
            try {
                m?.setEventListener(null)
                m?.stop()
                m?.detachViews()
                m?.release()
                libVlc?.release()
            } catch (e: Exception) {
                // already torn down
            }
            libVlc = null
            server?.stop()
            server = null
        }
        if (!engineClosed) {
            engineClosed = true
            (application as App).engine.closeTorrent()
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
