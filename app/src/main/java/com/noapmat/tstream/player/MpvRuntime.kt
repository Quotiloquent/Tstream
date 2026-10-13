package com.noapmat.tstream.player

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * The ONLY place that talks to libmpv ([MPVLib]). Everything else in the app goes through here.
 *
 * Why a wrapper:
 *  - [MPVLib] is a process-wide singleton (one mpv core at a time). Leaving the player and opening another file
 *    quickly would otherwise let a new `create()` race the old `destroy()`. Create and destroy therefore run
 *    in order on one dedicated thread.
 *  - mpv calls are not safe once the core is destroyed. Every call is guarded: after [close] they return
 *    null / false at once instead of touching freed native memory, and [close] waits for calls in flight.
 *  - Property access is typed and failure-tolerant (an unavailable property is just null), and the library's
 *    return types are not relied upon (see [rawSetOption]).
 */
object MpvRuntime {
    private val lifecycle = Executors.newSingleThreadExecutor { r -> Thread(r, "mpv-lifecycle").apply { isDaemon = true } }
    private val lock = ReentrantReadWriteLock()

    @Volatile private var alive = false // usable: created + initialised, not closing
    private var created = false         // a native core exists (guarded by the lifecycle thread)

    /**
     * Creates and initialises a core, applying [options] in order before init (so later entries win, which is
     * how user flags override the app's defaults). Blocks: call it off the UI thread.
     *
     * [abort] is asked on the lifecycle thread right before the core would be created: a screen that was closed
     * while it waited for the previous core to shut down returns true there, so no orphan core is ever made.
     *
     * @return the options mpv refused, as "name=value" (an unknown option or an unparsable value), or null when
     *         [abort] said no
     * @throws IllegalStateException when the previous player is still shutting down or mpv fails to start
     */
    fun start(ctx: Context, options: List<Pair<String, String>>, abort: () -> Boolean): List<String>? {
        val app = ctx.applicationContext
        val task = lifecycle.submit(Callable<List<String>?> {
            lock.writeLock().lock()
            try {
                if (abort()) return@Callable null
                val rejected = ArrayList<String>()
                MPVLib.create(app)
                created = true
                try {
                    for ((name, value) in options) if (!rawSetOption(name, value)) rejected += "$name=$value"
                    MPVLib.init()
                } catch (e: Throwable) {
                    try { MPVLib.destroy() } catch (x: Throwable) { /* nothing more to do */ }
                    created = false
                    throw e
                }
                alive = true
                rejected
            } finally {
                lock.writeLock().unlock()
            }
        })
        try {
            return task.get(20, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw IllegalStateException(e.cause?.message ?: "mpv could not start", e.cause)
        } catch (e: TimeoutException) {
            task.cancel(false)
            throw IllegalStateException("The previous player is still closing. Try again in a moment.")
        }
    }

    /** Tears the core down in the background (destroy can block while mpv waits on a stalled read). */
    fun close() {
        alive = false // from here on every call returns immediately
        lifecycle.execute {
            lock.writeLock().lock() // waits for calls that are already running
            try {
                if (created) {
                    created = false
                    try { MPVLib.destroy() } catch (e: Throwable) { /* already gone */ }
                }
            } finally {
                lock.writeLock().unlock()
            }
        }
    }

    // ---- guarded calls ---------------------------------------------------------------------------------

    /** Runs [block] only while the core is alive; never blocks waiting for a shutdown in progress. */
    private inline fun <T> guarded(block: () -> T): T? {
        if (!alive) return null
        if (!lock.readLock().tryLock()) return null
        try {
            return if (alive) block() else null
        } finally {
            lock.readLock().unlock()
        }
    }

    private inline fun read(block: () -> Any?): Any? = try { block() } catch (e: Exception) { null }

    fun attachSurface(surface: Surface) { guarded { MPVLib.attachSurface(surface) } }
    fun detachSurface() { guarded { MPVLib.detachSurface() } }

    /** Runs an mpv command, e.g. `command("seek", "10", "relative")`. */
    fun command(vararg args: String) {
        guarded { MPVLib.command(arrayOf(*args)) }
    }

    fun setString(name: String, value: String) { guarded { MPVLib.setPropertyString(name, value) } }
    fun setBool(name: String, value: Boolean) { guarded { MPVLib.setPropertyBoolean(name, value) } }
    fun setDouble(name: String, value: Double) { guarded { MPVLib.setPropertyDouble(name, value) } }

    /** Sets an option on a running core (used for the few options that change with the surface). */
    fun setOption(name: String, value: String) { guarded { rawSetOption(name, value) } }

    fun getString(name: String): String? = guarded { read { MPVLib.getPropertyString(name) } } as? String
    fun getInt(name: String): Int? = (guarded { read { MPVLib.getPropertyInt(name) } } as? Number)?.toInt()
    fun getDouble(name: String): Double? = (guarded { read { MPVLib.getPropertyDouble(name) } } as? Number)?.toDouble()
    fun getBool(name: String): Boolean? = guarded { read { MPVLib.getPropertyBoolean(name) } } as? Boolean

    /**
     * true when mpv accepted the option. The result is read as `Any?` on purpose: the library reports a negative
     * mpv error code as an Int, and treating "no code" as success keeps this compiling whichever way the
     * binding declares it.
     */
    private fun rawSetOption(name: String, value: String): Boolean {
        val rc: Any? = try { MPVLib.setOptionString(name, value) } catch (e: Exception) { return false }
        return (rc as? Int)?.let { it >= 0 } ?: true
    }
}
