package com.noapmat.tsream.cache

import android.content.Context

/** Remembers where you stopped, keyed by the exact title (the video's file path inside the torrent). */
object PositionStore {
    private const val PREFS = "positions"
    private const val MAX_ENTRIES = 300

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, title: String): Long? =
        prefs(ctx).getString(title, null)?.substringBefore('|')?.toLongOrNull()

    /** Positions in the first 10 s or the last 30 s count as "not started" / "finished" and are forgotten. */
    fun save(ctx: Context, title: String, positionMs: Long, durationMs: Long) {
        if (title.isBlank()) return
        val p = prefs(ctx)
        if (positionMs < 10_000 || (durationMs > 0 && positionMs > durationMs - 30_000)) {
            p.edit().remove(title).apply()
            return
        }
        val e = p.edit().putString(title, "$positionMs|${System.currentTimeMillis()}")
        val all = p.all
        if (all.size >= MAX_ENTRIES) {
            all.entries.sortedBy { (it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_ENTRIES + 20).forEach { e.remove(it.key) }
        }
        e.apply()
    }

    fun remove(ctx: Context, title: String) { prefs(ctx).edit().remove(title).apply() }

    fun clearAll(ctx: Context) { prefs(ctx).edit().clear().apply() }
}
