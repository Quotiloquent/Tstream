package com.noapmat.tsream

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.noapmat.tsream.cache.StaleCache
import com.noapmat.tsream.torrent.LibTorrentEngine
import com.noapmat.tsream.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File

class App : Application() {
    /** Re-read on every access so changes made in Settings apply to the next playback. */
    val settings: AppSettings get() = AppSettings.load(this)

    /**
     * On every process start, delete cache left by a crash / killed process / swipe-away.
     * (Lazy so it only runs once the application context exists; started in onCreate.)
     */
    private val startupCleanup: Deferred<Unit> =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).async(start = CoroutineStart.LAZY) {
            try { StaleCache.wipe(this@App) } catch (e: Exception) { /* best effort */ }
        }

    val engine: TorrentEngine by lazy {
        LibTorrentEngine(
            rootProvider = { AppSettings.cacheRoot(this).also { AppSettings.rememberRoot(this, it) } },
            settingsProvider = { settings },
            startupGate = { startupCleanup.await() }, // nothing is opened before stale caches are gone
        )
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this) // Material You colours on Android 12+
        startupCleanup.start()
        // Save Java/Kotlin crashes so MainActivity can show them on next launch (no adb needed).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("thread ${t.name}\n" + e.stackTraceToString()) } catch (x: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }
}
