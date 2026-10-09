package com.noapmat.tsream.cache

import java.io.File
import java.io.RandomAccessFile

object Cleanup {
    /**
     * Deletes [dir] and everything inside it. A file that cannot be unlinked (still open, flaky SD card
     * driver...) is truncated to 0 bytes first so its space is freed anyway.
     * @return true when the folder is gone.
     */
    fun deleteTree(dir: File): Boolean {
        if (!dir.exists()) return true
        dir.walkBottomUp().forEach { f ->
            if (!f.delete() && f.isFile) {
                try { RandomAccessFile(f, "rw").use { it.setLength(0) } } catch (e: Exception) { /* best effort */ }
                f.delete()
            }
        }
        return !dir.exists()
    }
}
