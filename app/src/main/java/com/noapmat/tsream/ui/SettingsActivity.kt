package com.noapmat.tsream.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noapmat.tsream.AppSettings
import com.noapmat.tsream.cache.TreeUriPaths
import com.noapmat.tsream.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private var pendingPath: String? = null

    /** System folder picker (Storage Access Framework): works on every Android version, needs no permission. */
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderPicked(uri)
    }

    private val allFilesScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val p = pendingPath
        pendingPath = null
        if (p != null) {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) commitFolder(p)
            else toast("\"All files access\" was not granted, so the folder was not changed.")
        }
    }

    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingPath
        pendingPath = null
        if (p != null) {
            if (granted) commitFolder(p) else toast("Storage permission denied, so the folder was not changed.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Fonts.apply(b.root)
        b.cachePath.typeface = Fonts.mono(this)
        b.toolbar.setNavigationOnClickListener { finish() }

        val s = AppSettings.load(this)
        b.switchSmart.isChecked = s.smartCache
        b.switchSeed.isChecked = s.seedWhileWatching
        b.ahead.setText(s.aheadMb.toString())
        b.behind.setText(s.behindMb.toString())
        refreshUi()

        b.switchSmart.setOnCheckedChangeListener { _, on ->
            AppSettings.update(this) { it.copy(smartCache = on) }
            refreshUi()
        }
        b.switchSeed.setOnCheckedChangeListener { _, on ->
            AppSettings.update(this) { it.copy(seedWhileWatching = on) }
            refreshUi()
        }
        b.btnChooseFolder.setOnClickListener { treePicker.launch(null) }
        b.btnResetFolder.setOnClickListener {
            AppSettings.update(this) { it.copy(cacheDir = "") }
            refreshUi()
        }
    }

    override fun onPause() {
        super.onPause()
        val ahead = b.ahead.text.toString().toIntOrNull() ?: AppSettings.DEFAULT_AHEAD_MB
        val behind = b.behind.text.toString().toIntOrNull() ?: AppSettings.DEFAULT_BEHIND_MB
        AppSettings.update(this) { it.copy(aheadMb = ahead, behindMb = behind) } // clamped inside
    }

    private fun refreshUi() {
        val s = AppSettings.load(this)
        b.cachePath.text = if (s.cacheDir.isBlank()) {
            "Default (app storage)\n" + AppSettings.defaultRoot(this).absolutePath
        } else {
            s.cacheDir + "/" + AppSettings.CACHE_FOLDER
        }
        b.btnResetFolder.isEnabled = s.cacheDir.isNotBlank()
        b.aheadLayout.isEnabled = s.smartCache
        b.behindLayout.isEnabled = s.smartCache
        b.ahead.isEnabled = s.smartCache
        b.behind.isEnabled = s.smartCache
        b.seedWarning.visibility = if (s.smartCache && s.seedWhileWatching) View.VISIBLE else View.GONE
    }

    // ---- folder picking -------------------------------------------------------------------------

    private fun onFolderPicked(uri: Uri) {
        val path = TreeUriPaths.resolve(this, uri)
        if (path == null) {
            toast("That location is not a regular folder (cloud or virtual storage). Pick a folder on internal storage or an SD card.")
            return
        }
        if (TreeUriPaths.isAppPrivate(this, path)) {
            commitFolder(path)
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                commitFolder(path)
            } else {
                pendingPath = path
                MaterialAlertDialogBuilder(this)
                    .setTitle("Allow access to all files")
                    .setMessage(
                        "The torrent engine writes with normal file paths, which Android 11+ only allows " +
                            "outside the app's own storage if you grant \"All files access\".\n\n" +
                            "Tstream only touches the \"${AppSettings.CACHE_FOLDER}\" sub-folder of the folder you picked."
                    )
                    .setPositiveButton("Open settings") { _, _ ->
                        allFilesScreen.launch(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    }
                    .setNegativeButton("Cancel") { _, _ -> pendingPath = null }
                    .show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                commitFolder(path)
            } else {
                pendingPath = path
                legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    /** Make sure we can really write there before remembering the folder. */
    private fun commitFolder(path: String) {
        val dir = File(path, AppSettings.CACHE_FOLDER)
        val ok = try {
            dir.mkdirs()
            val probe = File(dir, ".probe")
            probe.writeText("ok")
            probe.delete() && dir.isDirectory
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Can't write to that folder")
                .setMessage(
                    "$path is not writable by Tstream. Try another folder. (On Android 10 some locations stay " +
                        "blocked by scoped storage even after the permission is granted.)"
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AppSettings.update(this) { it.copy(cacheDir = path) }
        refreshUi()
        toast("Cache folder set")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
