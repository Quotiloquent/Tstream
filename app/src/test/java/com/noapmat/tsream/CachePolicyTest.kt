package com.noapmat.tsream

import com.noapmat.tsream.torrent.CachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    // file spans pieces 10..1009, reader at 500, keep 20 behind and 50 ahead
    private val f = 10
    private val l = 1009

    @Test fun keepRangeIsClampedToFile() {
        assertEquals(480..550, CachePolicy.keepRange(500, 20, 50, f, l))
        assertEquals(10..60, CachePolicy.keepRange(12, 20, 50, f, l))
        assertEquals(990..1009, CachePolicy.keepRange(1000, 10, 50, f, l))
    }

    @Test fun evictsOnlyOutsideWindow() {
        assertTrue(CachePolicy.shouldEvict(479, 500, 20, 50, f, l))
        assertFalse(CachePolicy.shouldEvict(480, 500, 20, 50, f, l))
        assertTrue(CachePolicy.shouldEvict(551, 500, 20, 50, f, l)) // after a backward seek, old lookahead goes
    }

    @Test fun headerAndIndexPiecesAreNeverEvicted() {
        for (p in listOf(10, 11, 1008, 1009)) assertFalse(CachePolicy.shouldEvict(p, 500, 20, 50, f, l))
        assertTrue(CachePolicy.shouldEvict(12, 500, 20, 50, f, l))
    }

    @Test fun piecesForRoundsDownWithMinimum() {
        assertEquals(50, CachePolicy.piecesFor(50L shl 20, 1 shl 20, 2))
        assertEquals(2, CachePolicy.piecesFor(1, 1 shl 20, 2))
    }
}
