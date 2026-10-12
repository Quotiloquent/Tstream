package com.noapmat.tsream

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.material.color.DynamicColors
import com.noapmat.tsream.cache.PositionStore
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

    private val engineLazy = lazy {
        LibTorrentEngine(
            rootProvider = { AppSettings.cacheRoot(this).also { AppSettings.rememberRoot(this, it) } },
            settingsProvider = { settings },
            startupGate = { startupCleanup.await() }, // nothing is opened before stale caches are gone
            resumeHint = { path, size ->
                // where you stopped in this exact file, so the download starts there and not at the beginning
                if (!settings.rememberPosition) null
                else PositionStore.getFull(this, path)?.takeIf { it.size == size && it.bytes >= 0 }?.bytes
            },
        )
    }

    val engine: TorrentEngine get() = engineLazy.value

    private val ui = Handler(Looper.getMainLooper())
    private var visibleActivities = 0
    private val goBackground = Runnable { if (engineLazy.isInitialized()) engineLazy.value.setBackground(true) }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this) // Material You colours on Android 12+
        startupCleanup.start()
        // No screen visible (home button, screen off, app switched away): pause all network activity, so nothing
        // keeps downloading in the background. The short delay avoids flapping while one screen replaces another.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities++
                ui.removeCallbacks(goBackground)
                if (engineLazy.isInitialized()) engineLazy.value.setBackground(false)
            }

            override fun onActivityStopped(activity: Activity) {
                visibleActivities--
                if (visibleActivities <= 0) ui.postDelayed(goBackground, 3000)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Save Java/Kotlin crashes so MainActivity can show them on next launch (no adb needed).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("thread ${t.name}\n" + e.stackTraceToString()) } catch (x: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }
}
