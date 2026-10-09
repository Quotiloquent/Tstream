package com.noapmat.tsream

import com.noapmat.tsream.torrent.CachePolicy
import com.noapmat.tsream.torrent.MediaTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    // file spans pieces 10..1009
    private val f = 10
    private val l = 1009

    @Test fun keepRangeIsClampedToFile() {
        // anchor (playhead) 500, reader 520, keep 20 behind and 50 ahead
        assertEquals(480..570, CachePolicy.keepRange(500, 20, 520, 50, f, l))
        assertEquals(10..62, CachePolicy.keepRange(12, 20, 12, 50, f, l))
        assertEquals(990..1009, CachePolicy.keepRange(1000, 10, 1000, 50, f, l))
    }

    @Test fun evictsOnlyClearlyBehindThePlayhead() {
        // playhead 500, reader 520, behind 20, ahead 50
        assertTrue(CachePolicy.shouldEvict(479, 500, 520, 20, 50, f, l))
        assertFalse(CachePolicy.shouldEvict(480, 500, 520, 20, 50, f, l))
        // the buffer between playhead and reader is never touched
        assertFalse(CachePolicy.shouldEvict(510, 500, 520, 20, 50, f, l))
    }

    @Test fun shortRewindNeverEvictsDataAhead() {
        // reader moved back from 520 to 505: what was downloaded up to 570 must stay
        for (p in 506..570) assertFalse(CachePolicy.shouldEvict(p, 485, 505, 20, 50, f, l))
        // only a whole extra window beyond the reader (leftover of a long seek back) goes
        assertFalse(CachePolicy.shouldEvict(605, 485, 505, 20, 50, f, l))
        assertTrue(CachePolicy.shouldEvict(606, 485, 505, 20, 50, f, l))
    }

    @Test fun headerAndIndexPiecesAreNeverEvicted() {
        for (p in listOf(10, 11, 1008, 1009)) assertFalse(CachePolicy.shouldEvict(p, 500, 520, 20, 50, f, l))
        assertTrue(CachePolicy.shouldEvict(12, 500, 520, 20, 50, f, l))
    }

    @Test fun piecesForRoundsDownWithMinimum() {
        assertEquals(25, CachePolicy.piecesFor(25L shl 20, 1 shl 20, 2))
        assertEquals(2, CachePolicy.piecesFor(1, 1 shl 20, 2))
    }

    @Test fun subtitleExtensions() {
        assertTrue(MediaTypes.isSubtitle("Movie/Subs/English.SRT"))
        assertTrue(MediaTypes.isSubtitle("a.ass"))
        assertTrue(MediaTypes.isSubtitle("a.vtt"))
        assertFalse(MediaTypes.isSubtitle("a.mkv"))
    }
}
