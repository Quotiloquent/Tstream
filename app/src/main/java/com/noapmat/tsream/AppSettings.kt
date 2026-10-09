package com.noapmat.tsream

import android.content.Context
import java.io.File

/** User settings (persisted in SharedPreferences) plus a few fixed defaults. 0 for limits means "unlimited". */
data class AppSettings(
    val smartCache: Boolean = true,
    val aheadMb: Int = DEFAULT_AHEAD_MB,     // max data downloaded beyond the player's read position
    val behindMb: Int = DEFAULT_BEHIND_MB,   // data further than this behind the playhead is deleted
    val seedWhileWatching: Boolean = false,
    val cacheDir: String = "",               // folder picked by the user, "" = app-private default
    val maxConnections: Int = 100,
    val downloadLimitBps: Int = 0,
    val uploadLimitBps: Int = 0,
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 60_000,
    val startBufferMs: Int = 2_500,
    val rebufferMs: Int = 5_000,
    val metadataTimeoutMs: Long = 90_000,
) {
    val aheadBytes: Long get() = aheadMb.toLong() shl 20
    val behindBytes: Long get() = behindMb.toLong() shl 20

    /**
     * Seeding and smart cache are mutually exclusive: smart cache deletes data behind the playhead, and
     * libtorrent would keep advertising (and serving) pieces whose bytes are gone. So seeding only
     * counts when smart cache is off, whatever the stored switch says.
     */
    val seeding: Boolean get() = seedWhileWatching && !smartCache

    /** RAM cap for the player's own buffer (it would otherwise grow to ~100+ MB). */
    val playerRamBytes: Int get() = aheadMb.coerceIn(16, 128) shl 20

    companion object {
        const val DEFAULT_AHEAD_MB = 50
        const val DEFAULT_BEHIND_MB = 30
        const val MIN_AHEAD_MB = 10
        const val MIN_BEHIND_MB = 4
        const val MAX_MB = 4096
        const val CACHE_FOLDER = "Tstream-cache"
        private const val PREFS = "settings"

        fun load(ctx: Context): AppSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                smartCache = p.getBoolean("smartCache", true),
                aheadMb = p.getInt("aheadMb", DEFAULT_AHEAD_MB).coerceIn(MIN_AHEAD_MB, MAX_MB),
                behindMb = p.getInt("behindMb", DEFAULT_BEHIND_MB).coerceIn(MIN_BEHIND_MB, MAX_MB),
                seedWhileWatching = p.getBoolean("seed", false),
                cacheDir = p.getString("cacheDir", "") ?: "",
            )
        }

        fun update(ctx: Context, change: (AppSettings) -> AppSettings) {
            val s = change(load(ctx))
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("smartCache", s.smartCache)
                .putInt("aheadMb", s.aheadMb.coerceIn(MIN_AHEAD_MB, MAX_MB))
                .putInt("behindMb", s.behindMb.coerceIn(MIN_BEHIND_MB, MAX_MB))
                .putBoolean("seed", s.seedWhileWatching)
                .putString("cacheDir", s.cacheDir)
                .apply()
        }

        /** The default, app-private cache folder (needs no permissions). */
        fun defaultRoot(ctx: Context) = File(ctx.cacheDir, "torrents")

        /**
         * Where torrents are downloaded. Always a dedicated sub-folder, because everything inside it is
         * deleted when the torrent is closed. Falls back to the default if the picked folder is gone/read-only.
         */
        fun cacheRoot(ctx: Context): File {
            val custom = load(ctx).cacheDir
            if (custom.isNotBlank()) {
                val parent = File(custom)
                if (parent.isDirectory && parent.canWrite()) return File(parent, CACHE_FOLDER)
            }
            return defaultRoot(ctx)
        }
    }
}
