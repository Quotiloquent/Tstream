package com.noapmat.tsream

import com.noapmat.tsream.torrent.Magnet
import com.noapmat.tsream.torrent.MediaTypes
import com.noapmat.tsream.torrent.PieceMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class CoreTest {
    @Test fun magnetValidation() {
        assertTrue(Magnet.isValid("magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=x"))
        assertTrue(Magnet.isValid("magnet:?xt=urn:btih:" + "A".repeat(32)))
        assertFalse(Magnet.isValid("magnet:?xt=urn:btih:xyz"))
        assertFalse(Magnet.isValid("http://example.com"))
    }

    @Test fun byteOffsetToPiece() {
        val pl = 1 shl 20
        assertEquals(0, PieceMath.pieceOf(0, pl))
        assertEquals(0, PieceMath.pieceOf((pl - 1).toLong(), pl))
        assertEquals(1, PieceMath.pieceOf(pl.toLong(), pl))
        assertEquals(0..2, PieceMath.pieceRange(0, 2L * pl + 1, pl))
        assertEquals(1..1, PieceMath.pieceRange(pl.toLong(), pl.toLong(), pl))
    }

    @Test fun pathTraversalIsRejected() {
        val root = Files.createTempDirectory("t").toFile()
        PieceMath.safeResolve(root, "dir/movie.mkv")
        // A leading slash is re-rooted under the cache dir by java.io.File, so it is contained.
        val abs = PieceMath.safeResolve(root, "/etc/passwd")
        assertTrue(abs.path.startsWith(root.canonicalPath))
        for (bad in listOf("../../etc/passwd", "a/../../b", "..")) {
            try {
                PieceMath.safeResolve(root, bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test fun extensions() {
        assertTrue(MediaTypes.looksPlayable("a/b.MKV"))
        assertFalse(MediaTypes.looksPlayable("a/b.srt"))
    }
}
