package com.noapmat.tsream.ui

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.noapmat.tsream.App
import com.noapmat.tsream.databinding.ActivityPlayerBinding
import com.noapmat.tsream.streaming.TorrentDataSource
import com.noapmat.tsream.torrent.Fmt
import com.noapmat.tsream.torrent.OrientationPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var error: String? = null
    private var job: Job? = null
    private var engineClosed = false
    private var orientationChosen = false
    private var controllerVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        enterImmersive()
        Fonts.apply(b.root)
        b.info.typeface = Fonts.mono(this)
        b.player.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { controllerVisible = it == View.VISIBLE }
        )

        val app = application as App
        val s = app.settings
        val seeding = s.seeding
        val index = intent.getIntExtra("index", -1)

        job = lifecycleScope.launch {
            val stream = try {
                withContext(Dispatchers.IO) { app.engine.openFile(index) }
            } catch (e: Exception) {
                b.info.text = "Error: ${e.message}"; return@launch
            }
            val factory = DataSource.Factory { TorrentDataSource(stream) }
            val p = ExoPlayer.Builder(this@PlayerActivity)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(s.minBufferMs, s.maxBufferMs, s.startBufferMs, s.rebufferMs)
                        .setTargetBufferBytes(s.playerRamBytes)
                        .setPrioritizeTimeOverSizeThresholds(false)
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
            })
            p.setMediaSource(
                ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory())
                    .createMediaSource(MediaItem.fromUri("torrent://stream/$index"))
            )
            p.prepare()
            p.playWhenReady = true
            b.player.player = p
            player = p

            while (isActive) {
                val st = app.engine.stats.value
                val buffering = p.playbackState == Player.STATE_BUFFERING
                b.info.visibility = if (buffering || error != null || controllerVisible) View.VISIBLE else View.GONE
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
                delay(1000)
            }
        }
    }

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

    override fun onStop() {
        super.onStop()
        player?.pause()
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
        player?.release()
        player = null
        if (!engineClosed) {
            engineClosed = true
            (application as App).engine.closeTorrent()
        }
    }
}
