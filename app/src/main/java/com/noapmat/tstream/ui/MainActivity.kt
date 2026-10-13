package com.noapmat.tstream.ui

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.noapmat.tstream.App
import com.noapmat.tstream.cache.ExitCleanupService
import com.noapmat.tstream.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val engine get() = (application as App).engine
    private val adapter = FileAdapter { f ->
        if (f.playable) {
            // The player gets the source too: the torrent is closed whenever you leave the player, so it
            // re-opens it itself (see PlayerActivity) instead of this screen doing it in the background.
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra("index", f.index)
                    .putExtra("path", f.path)
                    .putExtra("size", f.size)
                    .putExtra("source", lastSource)
            )
        } else if (f.subtitle) {
            Toast.makeText(this, "Subtitles are picked inside the player (CC button)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Not a recognised video file", Toast.LENGTH_SHORT).show()
        }
    }
    private var job: Job? = null
    private var lastSource: String? = null
    private var torrentName = ""
    private var loaded = false
    private var loadGeneration = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(null, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Fonts.apply(b.root)
        b.diag.typeface = Fonts.mono(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.btnStream.setOnClickListener { streamFromInput() }
        b.input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { streamFromInput(); true } else false
        }
        b.btnTorrent.setOnClickListener { picker.launch(arrayOf("*/*")) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.inputLayout.setEndIconOnClickListener { pasteFromClipboard() }

        ExitCleanupService.start(this)
        showDiagnostics()
        // Only act on the launch intent once; recreation must not re-open the torrent.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        val d = i.data ?: return
        setIntent(Intent(i).apply { data = null }) // consume it
        if (d.scheme == "magnet") {
            b.input.setText(d.toString())
            load(d.toString(), null)
        } else {
            load(null, d)
        }
    }

    private fun streamFromInput() {
        val text = b.input.text.toString().trim()
        if (text.isEmpty()) {
            b.inputLayout.error = "Paste a magnet link first"
        } else {
            b.inputLayout.error = null
            load(text, null)
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrBlank()) b.input.setText(text.trim())
    }

    private fun setLoading(on: Boolean) {
        b.progress.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun load(magnet: String?, torrentUri: Uri?) {
        job?.cancel()
        val generation = ++loadGeneration
        loaded = false
        adapter.submit(emptyList())
        b.status.text = "Fetching metadata (DHT / trackers)\u2026"
        setLoading(true)
        job = lifecycleScope.launch {
            try {
                val source = if (torrentUri != null) withContext(Dispatchers.IO) {
                    val out = File(cacheDir, "in.torrent")
                    contentResolver.openInputStream(torrentUri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                    out.absolutePath
                } else magnet!!
                lastSource = source
                val meta = engine.open(source)
                adapter.submit(meta.files.sortedByDescending { it.playable }) // videos first, then subtitles/other
                loaded = meta.files.isNotEmpty()
                torrentName = meta.name
                b.status.text = meta.name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.status.text = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (generation == loadGeneration) setLoading(false) // a newer load may still be running
            }
        }
    }

    /**
     * Leaving the player stops the torrent and deletes its cache. The file list stays on screen, but the torrent
     * is NOT re-opened here: doing that restarted the download (and refilled the cache) the moment you left the
     * player. The player re-opens it only when you pick a video again.
     */
    override fun onResume() {
        super.onResume()
        if (loaded && job?.isActive != true && !engine.isOpen) {
            b.status.text = "$torrentName"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) engine.closeTorrent()
    }

    private fun showDiagnostics() {
        val sb = StringBuilder()
        val crash = File(filesDir, "crash.txt")
        if (crash.exists()) {
            sb.append("Last crash:\n").append(crash.readText().take(1200)).append('\n')
            crash.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val am = getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let {
                // 4 = Java crash, 5 = native crash, 3 = ANR (see ApplicationExitInfo)
                if (it.reason == 4 || it.reason == 5 || it.reason == 3) {
                    sb.append("Last exit: reason=${it.reason} ${it.description ?: ""}")
                }
            }
        }
        b.diag.text = sb.toString()
        b.diag.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
    }
}
