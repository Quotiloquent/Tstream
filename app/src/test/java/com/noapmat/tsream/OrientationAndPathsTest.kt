package com.noapmat.tsream

import com.noapmat.tsream.torrent.OrientationPicker
import com.noapmat.tsream.torrent.OrientationPicker.Choice.LANDSCAPE
import com.noapmat.tsream.torrent.OrientationPicker.Choice.PORTRAIT
import com.noapmat.tsream.torrent.StoragePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrientationAndPathsTest {
    // a tall phone: 2400 x 1080 (long 2400, short 1080) and a squarer one: 1600 x 1200
    private fun pick(r: Float, long: Float = 2400f, short: Float = 1080f) = OrientationPicker.pick(r, long, short)

    @Test fun widescreenAndFourThreeAreLandscape() {
        assertEquals(LANDSCAPE, pick(16f / 9f))
        assertEquals(LANDSCAPE, pick(4f / 3f))
        assertEquals(LANDSCAPE, pick(2.39f))
        assertEquals(LANDSCAPE, pick(4f / 3f, 1600f, 1200f))
    }

    @Test fun squareAndTallVideosArePortrait() {
        assertEquals(PORTRAIT, pick(1f))
        assertEquals(PORTRAIT, pick(1.02f)) // within the square tolerance
        assertEquals(PORTRAIT, pick(9f / 16f))
        assertEquals(PORTRAIT, pick(3f / 4f))
    }

    @Test fun badInputFallsBackToPortrait() {
        assertEquals(PORTRAIT, OrientationPicker.pick(0f, 2400f, 1080f))
        assertEquals(PORTRAIT, OrientationPicker.pick(1.78f, 0f, 0f))
    }

    @Test fun docIdParsing() {
        assertEquals("primary" to "Download/Movies", StoragePaths.parseDocId("primary:Download/Movies"))
        assertEquals("ABCD-1234" to "", StoragePaths.parseDocId("ABCD-1234:"))
        assertEquals("primary" to "a/b", StoragePaths.parseDocId("primary:/a/b/"))
        assertNull(StoragePaths.parseDocId("nonsense"))
        assertNull(StoragePaths.parseDocId(":x"))
    }
}
