package com.example.torrentstream.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.example.torrentstream.App
import com.example.torrentstream.streaming.TorrentDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as App
        val s = app.settings

        val pv = PlayerView(this)
        val info = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); setPadding(16, 8, 16, 8)
        }
        setContentView(FrameLayout(this).apply {
            addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(info, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        })

        val index = intent.getIntExtra("index", -1)
        lifecycleScope.launch {
            val stream = try {
                withContext(Dispatchers.IO) { app.engine.openFile(index) }
            } catch (e: Exception) {
                info.text = "Error: ${e.message}"; return@launch
            }
            val factory = DataSource.Factory { TorrentDataSource(stream) }
            val p = ExoPlayer.Builder(this@PlayerActivity)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(s.minBufferMs, s.maxBufferMs, s.startBufferMs, s.rebufferMs)
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
            })
            p.setMediaSource(
                ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory())
                    .createMediaSource(MediaItem.fromUri("torrent://stream/$index"))
            )
            p.prepare()
            p.playWhenReady = true
            pv.player = p
            player = p

            while (isActive) {
                val st = app.engine.stats.value
                val buffering = p.playbackState == Player.STATE_BUFFERING
                info.text = buildString {
                    error?.let { appendLine(it) }
                    if (buffering) {
                        appendLine("BUFFERING - waiting for torrent pieces...")
                        if (st.peers == 0) appendLine("No peers available yet.")
                    }
                    append("Buffer: ${p.totalBufferedDuration / 1000}s  ")
                    append("Down: %.1f MB/s  ".format(st.downBps / 1048576.0))
                    append("Peers: ${st.peers} (seeds ${st.seeds})")
                }
                delay(1000)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
