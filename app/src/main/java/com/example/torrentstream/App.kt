package com.example.torrentstream

import android.app.Application
import com.example.torrentstream.torrent.LibTorrentEngine
import com.example.torrentstream.torrent.TorrentEngine
import java.io.File

class App : Application() {
    val settings = AppSettings()
    val engine: TorrentEngine by lazy { LibTorrentEngine(File(cacheDir, "torrents"), settings) }
}
