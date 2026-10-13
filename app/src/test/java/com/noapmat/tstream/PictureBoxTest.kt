package com.noapmat.tstream

import com.noapmat.tstream.torrent.PictureBox
import com.noapmat.tstream.torrent.PictureBox.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureBoxTest {
    private val wide = 16f / 9f

    @Test fun pillarboxOnTallPhoneIsCutOff() {
        // 20:9 phone (2400 x 1080) showing a 16:9 video: 240 px bars left and right
        assertEquals(Box(240, 0, 1920, 1080), PictureBox.fit(2400, 1080, wide))
    }

    @Test fun letterboxInPortraitIsCutOff() {
        assertEquals(Box(0, 930, 1080, 540), PictureBox.fit(1080, 2400, 2f))
    }

    @Test fun surfaceWithoutBarsIsKeptWhole() {
        assertEquals(Box(0, 0, 1920, 1080), PictureBox.fit(1920, 1080, wide))
        assertEquals(Box(0, 0, 1921, 1080), PictureBox.fit(1921, 1080, wide)) // rounding noise is not a bar
    }

    @Test fun unknownInputKeepsTheWholeSurface() {
        assertEquals(Box(0, 0, 2400, 1080), PictureBox.fit(2400, 1080, 0f))
        assertEquals(Box(0, 0, 0, 0), PictureBox.fit(0, 0, wide))
    }
}
