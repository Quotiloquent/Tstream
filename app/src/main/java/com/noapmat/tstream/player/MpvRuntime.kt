package com.noapmat.tstream.player

import android.content.Context
import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.File
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

    @Volatile private var grabScriptFor: MPVLib? = null // the core the grab script was loaded into

    /**
     * Grabs the current frame as a Bitmap without needing an image encoder (this libmpv build has none): a tiny Lua
     * script inside mpv runs its raw screenshot ("screenshot-raw") and writes the pixels to a file, which is read
     * back here. [flags] is "video" (just the picture) or "subtitles" (picture with subtitles burned in).
     * Blocking: call off the main thread. Returns the bitmap, or null plus the reason.
     */
    fun grabFrame(ctx: Context, flags: String): Pair<Bitmap?, String> {
        if (!alive) return null to "mpv not available"
        val m = mpv ?: return null to "mpv not available"
        val dir = ctx.cacheDir
        val script = File(dir, "tstream_grab.lua")
        val ready = File(dir, "tstream_ready.flag")
        val base = File(dir, "grab-${System.nanoTime()}")
        val raw = File(base.path + ".raw")
        val meta = File(base.path + ".meta")
        val errFile = File(base.path + ".err")
        try {
            if (grabScriptFor !== m) {
                ready.delete()
                script.writeText(GRAB_LUA.replace("__READY__", ready.absolutePath))
                val err = commandError("load-script", script.absolutePath)
                if (err != null) return null to "couldn't load the grab script: $err"
                if (!waitFor(4000) { ready.exists() })
                    return null to "the grab script never started (Lua scripting may be missing from this libmpv)"
                grabScriptFor = m
            }
            val err = commandError("script-message", "tstream-grab", base.absolutePath, flags)
            if (err != null) return null to "couldn't reach the grab script: $err"
            if (!waitFor(10000) { meta.length() > 0 || errFile.length() > 0 })
                return null to "the grab script didn't answer in time"
            if (errFile.length() > 0) return null to "grab script: ${errFile.readText().trim()}"

            val parts = meta.readText().trim().split(" ")
            val w = parts.getOrNull(0)?.toIntOrNull()
            val h = parts.getOrNull(1)?.toIntOrNull()
            val stride = parts.getOrNull(2)?.toIntOrNull()
            val fmt = parts.getOrNull(3) ?: ""
            if (w == null || h == null || stride == null || w <= 0 || h <= 0 || stride < w * 4)
                return null to "unexpected frame info: ${meta.readText().trim()}"
            if (fmt != "bgr0" && fmt != "bgra") return null to "unsupported pixel format: $fmt"

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val row = ByteArray(stride)
            val px = IntArray(w)
            DataInputStream(raw.inputStream().buffered(1 shl 16)).use { input ->
                for (y in 0 until h) {
                    input.readFully(row, 0, if (y == h - 1) w * 4 else stride)
                    for (x in 0 until w) {
                        val o = x * 4
                        px[x] = (0xFF shl 24) or ((row[o + 2].toInt() and 0xFF) shl 16) or
                            ((row[o + 1].toInt() and 0xFF) shl 8) or (row[o].toInt() and 0xFF)
                    }
                    bmp.setPixels(px, 0, w, 0, y, w, 1)
                }
            }
            return bmp to ""
        } catch (e: Throwable) {
            return null to (e.message ?: e.javaClass.simpleName)
        } finally {
            raw.delete(); meta.delete(); errFile.delete()
        }
    }

    private inline fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(25)
        }
        return cond()
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

// Runs inside mpv: answers "script-message tstream-grab <outBase> <flags>" with the raw frame in <outBase>.raw plus
// <outBase>.meta ("w h stride format", written last), or <outBase>.err with the reason. __READY__ is replaced by the
// path of a marker file created once the script is listening.
private const val GRAB_LUA = """
mp.register_script_message("tstream-grab", function(out, flags)
    local ok, err = pcall(function()
        local r, e = mp.command_native({"screenshot-raw", flags})
        if not r then error(e or "screenshot-raw failed") end
        if type(r.data) ~= "string" then error("unexpected data type " .. type(r.data)) end
        local f = assert(io.open(out .. ".raw", "wb"))
        f:write(r.data)
        f:close()
        local m = assert(io.open(out .. ".meta", "wb"))
        m:write(string.format("%d %d %d %s", r.w, r.h, r.stride, r.format))
        m:close()
    end)
    if not ok then
        local m = io.open(out .. ".err", "wb")
        if m then m:write(tostring(err)); m:close() end
    end
end)
local rf = io.open("__READY__", "wb")
if rf then rf:write("1"); rf:close() end
"""
