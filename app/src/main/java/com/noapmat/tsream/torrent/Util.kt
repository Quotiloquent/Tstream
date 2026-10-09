package com.noapmat.tsream.torrent

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

/** Piece-window rules of the smart cache, kept free of Android/libtorrent so they can be unit-tested. */
object CachePolicy {
    /** First and last two pieces of a file hold container headers / indexes (mp4 moov, mkv cues): never evict. */
    fun isProtected(p: Int, firstPiece: Int, lastPiece: Int) = p <= firstPiece + 1 || p >= lastPiece - 1

    /** Pieces worth keeping on disk around the read position [first]. */
    fun keepRange(first: Int, behind: Int, ahead: Int, firstPiece: Int, lastPiece: Int): IntRange =
        maxOf(firstPiece, first - behind)..minOf(lastPiece, first + ahead)

    fun shouldEvict(p: Int, first: Int, behind: Int, ahead: Int, firstPiece: Int, lastPiece: Int) =
        p !in keepRange(first, behind, ahead, firstPiece, lastPiece) && !isProtected(p, firstPiece, lastPiece)

    fun piecesFor(bytes: Long, pieceLength: Int, min: Int) = (bytes / pieceLength).toInt().coerceAtLeast(min)
}

/** Chooses the player orientation that shows the video as large as possible on this screen. */
object OrientationPicker {
    enum class Choice { LANDSCAPE, PORTRAIT }

    /** Area of a video with aspect ratio [ratio] (w/h) scaled to fit inside a [boxW] x [boxH] box. */
    fun fitArea(ratio: Float, boxW: Float, boxH: Float): Float {
        val w = minOf(boxW, boxH * ratio)
        return w * (w / ratio)
    }

    /**
     * Compares the picture size in both orientations for a screen of [screenLong] x [screenShort] px.
     * Videos within [squareTolerance] of 1:1 are treated as square and shown in portrait.
     * Nothing is tied to particular aspect ratios, so 21:9, 4:3, 9:16, 1:1 ... all just work.
     */
    fun pick(videoRatio: Float, screenLong: Float, screenShort: Float, squareTolerance: Float = 0.03f): Choice {
        if (videoRatio <= 0f || screenLong <= 0f || screenShort <= 0f) return Choice.PORTRAIT
        if (kotlin.math.abs(videoRatio - 1f) <= squareTolerance) return Choice.PORTRAIT
        val landscape = fitArea(videoRatio, screenLong, screenShort)
        val portrait = fitArea(videoRatio, screenShort, screenLong)
        return if (landscape > portrait) Choice.LANDSCAPE else Choice.PORTRAIT
    }
}

object StoragePaths {
    /** Splits an ExternalStorage document id such as "primary:Download/Movies" into volume + relative path. */
    fun parseDocId(docId: String): Pair<String, String>? {
        val i = docId.indexOf(':')
        if (i <= 0) return null
        return docId.substring(0, i) to docId.substring(i + 1).trim('/')
    }
}

object Fmt {
    fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        else -> "%d KB".format(b shr 10)
    }
}
