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
 *  - [MPVLib] (libmpv 1.0.0+) is an instance: `MPVLib.create()` returns it and every call goes through it. This
 *    object keeps the one live instance (one mpv core at a time). Leaving the player and opening another file
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
    @Volatile private var mpv: MPVLib? = null // the native core, if one exists (written under the write lock)

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
                val m = MPVLib.create(app) ?: throw IllegalStateException("mpv could not start")
                mpv = m
                try {
                    for ((name, value) in options) if (!rawSetOption(m, name, value)) rejected += "$name=$value"
                    m.init()
                } catch (e: Throwable) {
                    try { m.destroy() } catch (x: Throwable) { /* nothing more to do */ }
                    mpv = null
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
                val m = mpv
                if (m != null) {
                    mpv = null
                    try { m.destroy() } catch (e: Throwable) { /* already gone */ }
                }
            } finally {
                lock.writeLock().unlock()
            }
        }
    }

    // ---- guarded calls ---------------------------------------------------------------------------------

    /**
     * Runs [block] with the live core, only while it is alive; never blocks waiting for a shutdown in progress.
     * The instance is read under the read lock, so [close] cannot destroy it while [block] runs.
     */
    private inline fun <T> guarded(block: (MPVLib) -> T): T? {
        if (!alive) return null
        if (!lock.readLock().tryLock()) return null
        try {
            val m = mpv
            return if (alive && m != null) block(m) else null
        } finally {
            lock.readLock().unlock()
        }
    }

    private inline fun <T> read(block: () -> T?): T? = try { block() } catch (e: Exception) { null }

    fun attachSurface(surface: Surface) { guarded { it.attachSurface(surface) } }
    fun detachSurface() { guarded { it.detachSurface() } }

    /** Runs an mpv command, e.g. `command("seek", "10", "relative")`. */
    fun command(vararg args: String) {
        guarded { it.command(arrayOf(*args)) }
    }

    /**
     * Like [command], but reports the outcome: null when mpv ran it, otherwise why not (the core is gone or
     * closing, or mpv threw). A failing command such as a screenshot must not crash the caller.
     */
    fun commandError(vararg args: String): String? =
        try {
            if (guarded { it.command(arrayOf(*args)); true } == true) null else "mpv not available"
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        }

    fun setString(name: String, value: String) { guarded { it.setPropertyString(name, value) } }
    fun setBool(name: String, value: Boolean) { guarded { it.setPropertyBoolean(name, value) } }
    fun setDouble(name: String, value: Double) { guarded { it.setPropertyDouble(name, value) } }

    /** Sets an option on a running core (used for the few options that change with the surface). */
    fun setOption(name: String, value: String) { guarded { rawSetOption(it, name, value) } }

    fun getString(name: String): String? = guarded { m -> read { m.getPropertyString(name) } }
    fun getInt(name: String): Int? = guarded { m -> read { m.getPropertyInt(name) } }
    fun getDouble(name: String): Double? = guarded { m -> read { m.getPropertyDouble(name) } }
    fun getBool(name: String): Boolean? = guarded { m -> read { m.getPropertyBoolean(name) } }

    /** true when mpv accepted the option (a negative mpv error code means it refused it). */
    private fun rawSetOption(m: MPVLib, name: String, value: String): Boolean =
        try { m.setOptionString(name, value) >= 0 } catch (e: Exception) { false }
}
