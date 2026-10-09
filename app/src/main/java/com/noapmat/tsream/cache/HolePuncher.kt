package com.noapmat.tsream.cache

/** Thin wrapper over fallocate(PUNCH_HOLE): really gives disk space back while the file stays the same size. */
object HolePuncher {
    val available: Boolean = try {
        System.loadLibrary("holepunch")
        true
    } catch (e: Throwable) {
        false
    }

    /** @return 0 on success, otherwise -errno. */
    @JvmStatic
    external fun punch(path: String, offset: Long, length: Long): Int
}
