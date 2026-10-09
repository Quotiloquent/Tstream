package com.noapmat.tsream.cache

import android.content.Context
import com.noapmat.tsream.AppSettings
import java.io.File

/**
 * Deletes cache left behind by a crash, a killed process or a swipe-away from Recents.
 * Runs once per process start, before any torrent is opened. Only ever touches our own dedicated folders.
 */
object StaleCache {
    fun wipe(ctx: Context) {
        val roots = LinkedHashSet<File>()
        roots += AppSettings.defaultRoot(ctx)
        roots += AppSettings.cacheRoot(ctx)
        AppSettings.knownRoots(ctx).forEach { roots += File(it) }
        for (r in roots) {
            // safety: only the app-private default or a folder literally named Tstream-cache
            if (r == AppSettings.defaultRoot(ctx) || r.name == AppSettings.CACHE_FOLDER) Cleanup.deleteTree(r)
        }
    }
}
