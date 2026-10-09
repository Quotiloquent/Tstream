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
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noapmat.tsream.App
import com.noapmat.tsream.cache.PositionStore
import com.noapmat.tsream.databinding.ActivityPlayerBinding
import com.noapmat.tsream.streaming.TorrentDataSource
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
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private lateinit var audio: AudioManager
    private val ui = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var fileStream: FileStream? = null
    private var job: Job? = null
    private var error: String? = null
    private var engineClosed = false
    private var orientationChosen = false
    private var controllerVisible = true
    private var locked = false
    private var ended = false
    private var fastForwarding = false
    private var speedBeforeHold = 1f
    private var volumeAccum = 0f
    private var fileIndex = -1
    private var title = ""
    private var rememberPosition = true
    private var externalSub: ExternalSub? = null
    private var forceSubLabel: String? = null

    private class ExternalSub(val fileIndex: Int, val file: File, val mime: String, val label: String)

    private val hideHud = Runnable { b.hud.visibility = View.GONE }
    private val hideLeft = Runnable { b.seekLeft.visibility = View.GONE }
    private val hideRight = Runnable { b.seekRight.visibility = View.GONE }
    private val hideUnlock = Runnable { b.btnUnlock.visibility = View.GONE }

    private val gestures = object : PlayerGestures.Callbacks {
        override fun onSingleTap() {
            if (locked) { showUnlockButton(); return }
            if (b.player.isControllerFullyVisible) b.player.hideController() else b.player.showController()
        }

        override fun onDoubleTap(zone: PlayerGestures.Zone) {
            if (locked) return
            when (zone) {
                PlayerGestures.Zone.LEFT -> { seekBy(-10_000); flash(b.seekLeft, hideLeft, "\u221210 s") }
                PlayerGestures.Zone.RIGHT -> { seekBy(10_000); flash(b.seekRight, hideRight, "+10 s") }
                PlayerGestures.Zone.CENTER -> player?.let {
                    if (it.isPlaying) { it.pause(); showHud("Paused") } else { it.play(); showHud("Playing") }
                }
            }
        }

        override fun onVolumeScroll(fraction: Float) {
            if (!locked) adjustVolume(fraction)
        }

        override fun onHoldStart() {
            val p = player ?: return
            if (locked) return
            fastForwarding = true
            speedBeforeHold = p.playbackParameters.speed
            p.setPlaybackSpeed(2f)
            showHud("2\u00D7 speed", persistent = true)
        }

        override fun onHoldEnd() {
            if (!fastForwarding) return
            fastForwarding = false
            player?.setPlaybackSpeed(speedBeforeHold)
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

        b.player.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { vis ->
                controllerVisible = vis == View.VISIBLE
                updateControlsRow()
            }
        )
        b.player.setOnTouchListener(PlayerGestures(b.player, gestures))
        b.btnSubtitles.setOnClickListener { showSubtitlePicker() }
        b.btnScreenshot.setOnClickListener { takeScreenshot() }
        b.btnLock.setOnClickListener { setLocked(true) }
        b.btnUnlock.setOnClickListener { setLocked(false) }

        job = lifecycleScope.launch {
            val stream = try {
                withContext(Dispatchers.IO) { app.engine.openFile(fileIndex) }
            } catch (e: Exception) {
                b.info.text = "Error: ${e.message}"; return@launch
            }
            fileStream = stream
            val torrentSource = DataSource.Factory { TorrentDataSource(stream) }
            // DefaultDataSource: our torrent:// stream for the video, plain files for downloaded subtitles
            val dataSource = DefaultDataSource.Factory(this@PlayerActivity, torrentSource)
            val p = ExoPlayer.Builder(this@PlayerActivity)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, DefaultExtractorsFactory()))
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(s.minBufferMs, s.maxBufferMs, s.startBufferMs, s.rebufferMs)
                        .setTargetBufferBytes(s.playerRamBytes)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .setBackBuffer(10_000, true) // short rewinds are served from RAM
                        .build()
                )
                .build()
            p.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = if (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                    ) "Video format/codec is not supported by this device."
                    else "Playback error: ${e.errorCodeName}"
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) = chooseOrientation(videoSize)

                override fun onTracksChanged(tracks: Tracks) = applyForcedSubtitle(tracks)

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        ended = true
                        if (title.isNotBlank()) PositionStore.remove(this@PlayerActivity, title)
                    }
                }
            })

            val resume = if (rememberPosition && title.isNotBlank()) PositionStore.get(this@PlayerActivity, title) else null
            if (resume != null) toast("Resuming from ${clock(resume)}")
            p.setMediaItem(buildItem(), resume ?: C.TIME_UNSET)
            p.prepare()
            p.playWhenReady = true
            b.player.player = p
            player = p

            var tick = 0
            while (isActive) {
                val st = app.engine.stats.value
                val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: -1L
                stream.updatePlayback(p.currentPosition, p.bufferedPosition, duration)
                val buffering = p.playbackState == Player.STATE_BUFFERING
                b.info.visibility =
                    if (buffering || error != null || (controllerVisible && !locked)) View.VISIBLE else View.GONE
                b.info.text = buildString {
                    error?.let { appendLine(it) }
                    if (buffering) {
                        appendLine("BUFFERING - waiting for torrent pieces...")
                        if (st.peers == 0) appendLine("No peers available yet.")
                    }
                    append("Buffer ${p.totalBufferedDuration / 1000}s  ")
                    append("\u2193 ${Fmt.rate(st.downBps)}  ")
                    if (seeding) append("\u2191 ${Fmt.rate(st.upBps)}  ") // upload only shown while seeding is on
                    append("Peers ${st.peers} (seeds ${st.seeds})")
                }
                if (++tick % 10 == 0) savePosition()
                delay(1000)
            }
        }
    }

    // ---- media item / subtitles ------------------------------------------------------------------

    private fun buildItem(): MediaItem {
        val mb = MediaItem.Builder().setUri("torrent://stream/$fileIndex")
        externalSub?.let { sub ->
            mb.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub.file))
                        .setMimeType(sub.mime)
                        .setLanguage("und")
                        .setLabel(sub.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return mb.build()
    }

    /** Subtitle files of this torrent, those next to the video first. */
    private fun subtitleFiles(): List<TorrentFileMeta> {
        val dir = title.substringBeforeLast('/', "")
        return (application as App).engine.files.filter { it.subtitle }
            .sortedWith(compareByDescending<TorrentFileMeta> { it.path.substringBeforeLast('/', "") == dir }.thenBy { it.path })
    }

    private fun showSubtitlePicker() {
        val p = player ?: return
        class Entry(val label: String, val selected: Boolean, val action: () -> Unit)

        val entries = ArrayList<Entry>()
        val textOff = C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes
        val embedded = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        entries += Entry("Off", textOff || embedded.none { it.isSelected }) { setSubtitlesEnabled(false) }
        embedded.forEachIndexed { i, g ->
            val f = g.getTrackFormat(0)
            val name = f.label ?: f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage } ?: "Track ${i + 1}"
            entries += Entry("$name (in video)", g.isSelected && !textOff) { selectTextGroup(g) }
        }
        for (f in subtitleFiles()) {
            entries += Entry(f.path.substringAfterLast('/') + " (file)", externalSub?.fileIndex == f.index && !textOff) {
                useExternalSubtitle(f)
            }
        }
        if (entries.size == 1) { toast("No subtitles found in this video or torrent"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), entries.indexOfFirst { it.selected }.coerceAtLeast(0)) { d, which ->
                entries[which].action()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setSubtitlesEnabled(on: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build()
    }

    private fun selectTextGroup(g: Tracks.Group) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    /** Downloads the subtitle file from the torrent, then rebuilds the media item with it (embedded tracks stay). */
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
            val p = player ?: return@launch
            val mime = when (f.path.substringAfterLast('.').lowercase()) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.TEXT_SSA // .ass / .ssa
            }
            val label = f.path.substringAfterLast('/')
            externalSub = ExternalSub(f.index, file, mime, label)
            forceSubLabel = label
            val pos = p.currentPosition
            val play = p.playWhenReady
            setSubtitlesEnabled(true)
            p.setMediaItem(buildItem(), pos)
            p.prepare()
            p.playWhenReady = play
        }
    }

    /** After the media item was rebuilt, make sure the file we just added is the selected text track. */
    private fun applyForcedSubtitle(tracks: Tracks) {
        val label = forceSubLabel ?: return
        val g = tracks.groups.firstOrNull { grp ->
            grp.type == C.TRACK_TYPE_TEXT && (0 until grp.length).any { grp.getTrackFormat(it).label == label }
        } ?: return
        forceSubLabel = null
        selectTextGroup(g)
    }

    // ---- gestures / HUD ----------------------------------------------------------------------------

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        val target = p.currentPosition + deltaMs
        p.seekTo(if (dur != C.TIME_UNSET) target.coerceIn(0L, dur) else target.coerceAtLeast(0L))
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

    private fun setLocked(on: Boolean) {
        locked = on
        b.player.useController = !on
        if (on) b.player.hideController() else b.player.showController()
        updateControlsRow()
        if (on) showUnlockButton() else { ui.removeCallbacks(hideUnlock); b.btnUnlock.visibility = View.GONE }
    }

    private fun showUnlockButton() {
        b.btnUnlock.visibility = View.VISIBLE
        ui.removeCallbacks(hideUnlock)
        ui.postDelayed(hideUnlock, 3000)
    }

    private fun updateControlsRow() {
        b.controlsRow.visibility = if (controllerVisible && !locked) View.VISIBLE else View.GONE
    }

    // ---- screenshot ----------------------------------------------------------------------------------

    private fun takeScreenshot() {
        val p = player ?: return
        val pos = p.currentPosition
        when (val v = b.player.videoSurfaceView) {
            is SurfaceView -> {
                if (v.width <= 0 || v.height <= 0) { toast("Nothing to capture yet"); return }
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(
                    v, bmp,
                    PixelCopy.OnPixelCopyFinishedListener { result ->
                        if (result == PixelCopy.SUCCESS) saveShot(bmp, pos) else toast("Couldn't capture this frame")
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
            is TextureView -> v.bitmap?.let { saveShot(it, pos) } ?: toast("Couldn't capture this frame")
            else -> toast("Screenshots are not available for this video surface")
        }
    }

    private fun saveShot(bmp: Bitmap, pos: Long) {
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { Screenshots.save(this@PlayerActivity, bmp, title.ifBlank { "video" }, pos) }
            toast(if (where != null) "Saved to $where" else "Couldn't save the screenshot")
        }
    }

    // ---- orientation / immersive ---------------------------------------------------------------------

    /**
     * Picks landscape or portrait from the video's real aspect ratio (pixel aspect and rotation included):
     * whichever orientation makes the picture larger on this screen. Done once per playback.
     */
    private fun chooseOrientation(v: VideoSize) {
        if (orientationChosen || v.width <= 0 || v.height <= 0) return
        var ratio = v.width * v.pixelWidthHeightRatio / v.height
        if (v.unappliedRotationDegrees % 180 != 0) ratio = 1f / ratio
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

    // ---- lifecycle ---------------------------------------------------------------------------------------

    private fun savePosition() {
        val p = player ?: return
        if (ended || !rememberPosition || title.isBlank() || p.playbackState == Player.STATE_IDLE) return
        PositionStore.save(this, title, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: -1L)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
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
        player?.release()
        player = null
        if (!engineClosed) {
            engineClosed = true
            (application as App).engine.closeTorrent()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
