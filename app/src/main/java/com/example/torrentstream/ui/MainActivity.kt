package com.example.torrentstream.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.torrentstream.App
import com.example.torrentstream.torrent.TorrentFileMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private val engine get() = (application as App).engine
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var files: List<TorrentFileMeta> = emptyList()
    private var job: Job? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(null, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        input = EditText(this).apply { hint = "magnet:?xt=urn:btih:..."; setSingleLine() }
        val magnetBtn = Button(this).apply {
            text = "Load magnet"
            setOnClickListener { load(input.text.toString().trim(), null) }
        }
        val fileBtn = Button(this).apply {
            text = "Open .torrent file"
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }
        status = TextView(this).apply { setPadding(0, 16, 0, 16) }
        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ ->
            val f = files[pos]
            if (f.playable) {
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", f.index))
            } else {
                Toast.makeText(this, "Not a recognised video file", Toast.LENGTH_SHORT).show()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(input); addView(magnetBtn); addView(fileBtn); addView(status)
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        val d = i.data ?: return
        if (d.scheme == "magnet") load(d.toString(), null) else load(null, d)
    }

    private fun load(magnet: String?, torrentUri: Uri?) {
        job?.cancel()
        status.text = "Fetching metadata (DHT/trackers)..."
        files = emptyList()
        list.adapter = null
        job = lifecycleScope.launch {
            try {
                val source = if (torrentUri != null) withContext(Dispatchers.IO) {
                    val out = File(cacheDir, "in.torrent")
                    contentResolver.openInputStream(torrentUri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                    out.absolutePath
                } else magnet!!
                val meta = engine.open(source)
                files = meta.files
                list.adapter = ArrayAdapter(
                    this@MainActivity, android.R.layout.simple_list_item_1,
                    files.map { (if (it.playable) "\uD83C\uDFAC " else "\uD83D\uDCC4 ") + it.path + "  (" + fmt(it.size) + ")" },
                )
                status.text = meta.name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) engine.closeTorrent()
    }

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        else -> "%d KB".format(b shr 10)
    }
}
