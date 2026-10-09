package com.noapmat.tsream.cache

import android.system.Os
import java.io.File
import java.io.RandomAccessFile

/**
 * Smart cache saves space by (a) sparse files and (b) punching holes behind the playhead. FAT/exFAT SD cards
 * support neither, so we test the chosen folder instead of guessing from its name.
 */
object CacheProbe {
    data class Result(val sparse: Boolean, val canFreeSpace: Boolean) {
        val ok get() = sparse && canFreeSpace
    }

    private const val MB = 1L shl 20

    /** Blocking (writes ~8 MB): call from a background thread. */
    fun run(dir: File): Result {
        val f = File(dir, ".probe")
        return try {
            RandomAccessFile(f, "rw").use { raf ->
                raf.setLength(64 * MB)                           // extend without writing
                val sparse = allocated(f) < 4 * MB               // a sparse FS allocates (almost) nothing
                val chunk = ByteArray(1 shl 20) { 1 }
                raf.seek(0)
                repeat(8) { raf.write(chunk) }
                raf.fd.sync()
                val before = allocated(f)
                val punched = HolePuncher.available && HolePuncher.punch(f.absolutePath, 0, 8 * MB) == 0
                val canFree = punched && allocated(f) <= before - 4 * MB
                Result(sparse, canFree)
            }
        } catch (e: Exception) {
            Result(sparse = false, canFreeSpace = false)
        } finally {
            f.delete()
        }
    }

    private fun allocated(f: File): Long = Os.stat(f.absolutePath).st_blocks * 512L
}
