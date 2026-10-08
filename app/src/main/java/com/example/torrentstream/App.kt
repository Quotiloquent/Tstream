package com.example.torrentstream

import android.app.Application
import com.example.torrentstream.torrent.LibTorrentEngine
import com.example.torrentstream.torrent.TorrentEngine
import java.io.File

class App : Application() {
    val settings = AppSettings()
    val engine: TorrentEngine by lazy { LibTorrentEngine(File(cacheDir, "torrents"), settings) }

    override fun onCreate() {
        super.onCreate()
        // Save Java/Kotlin crashes so MainActivity can show them on next launch (no adb needed).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("thread ${t.name}\n" + e.stackTraceToString()) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }
}
