package com.example.torrentstream.torrent

import java.io.File

object Magnet {
    private val re = Regex(
        "^magnet:\\?.*xt=urn:btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})(&.*)?$",
        RegexOption.IGNORE_CASE,
    )

    fun isValid(uri: String) = re.matches(uri.trim())
}

object PieceMath {
    fun pieceOf(globalOffset: Long, pieceLength: Int): Int = (globalOffset / pieceLength).toInt()

    /** Inclusive piece range covering [length] bytes starting at [globalStart]. */
    fun pieceRange(globalStart: Long, length: Long, pieceLength: Int): IntRange =
        pieceOf(globalStart, pieceLength)..pieceOf(globalStart + length - 1, pieceLength)

    /** Resolves a torrent-supplied relative path and refuses anything escaping [root]. */
    fun safeResolve(root: File, relative: String): File {
        val base = root.canonicalFile
        val f = File(base, relative).canonicalFile
        require(f.path.startsWith(base.path + File.separator)) { "Unsafe torrent path: $relative" }
        return f
    }
}

object MediaTypes {
    private val ext = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts")
    fun looksPlayable(path: String) = path.substringAfterLast('.', "").lowercase() in ext
}
