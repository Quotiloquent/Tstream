package com.noapmat.tsream

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.noapmat.tsream.torrent.LibTorrentEngine
import com.noapmat.tsream.torrent.TorrentEngine
import java.io.File

class App : Application() {
    /** Re-read on every access so changes made in Settings apply to the next playback. */
    val settings: AppSettings get() = AppSettings.load(this)

    val engine: TorrentEngine by lazy {
        LibTorrentEngine(
            rootProvider = { AppSettings.cacheRoot(this) },
            settingsProvider = { settings },
        )
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this) // Material You colours on Android 12+
        // Save Java/Kotlin crashes so MainActivity can show them on next launch (no adb needed).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("thread ${t.name}\n" + e.stackTraceToString()) } catch (x: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }
}
