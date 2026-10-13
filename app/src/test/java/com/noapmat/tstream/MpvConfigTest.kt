package com.noapmat.tstream

import com.noapmat.tstream.player.MpvConfig
import com.noapmat.tstream.player.MpvFlags
import com.noapmat.tstream.player.MpvPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvConfigTest {
    private fun opts(text: String) = MpvFlags.parse(text).options.map { it.name to it.value }

    @Test fun plainOptionsCommentsAndBlankLines() {
        val text = "# comment\n\nprofile=gpu-hq\n  scale = ewa_lanczossharp  \n"
        assertEquals(listOf("profile" to "gpu-hq", "scale" to "ewa_lanczossharp"), opts(text))
    }

    @Test fun commandLineStyleIsAccepted() {
        assertEquals(listOf("deband" to "yes", "interpolation" to "yes", "deband" to "no"), opts("--deband=yes\ninterpolation\nno-deband"))
    }

    @Test fun onlyTheFirstEqualsSplits() {
        assertEquals(listOf("vf" to "lavfi=[eq=contrast=1.1]"), opts("vf=lavfi=[eq=contrast=1.1]"))
    }

    @Test fun quotesAroundValuesAreRemoved() {
        assertEquals(listOf("sub-font" to "Google Sans"), opts("sub-font=\"Google Sans\""))
    }

    @Test fun badLinesAreReportedAndSkipped() {
        val p = MpvFlags.parse("ok=1\n-fs\nbad name=1\n=x\nfine")
        assertEquals(listOf("ok" to "1", "fine" to "yes"), p.options.map { it.name to it.value })
        assertEquals(listOf(2, 3, 4), p.problems.map { it.line })
    }

    @Test fun orderIsKeptSoLaterLinesWin() {
        assertEquals(listOf("a" to "1", "a" to "2"), opts("a=1\na=2"))
    }

    @Test fun presetsParseWithoutProblems() {
        for (preset in listOf(MpvPresets.QUALITY, MpvPresets.PERFORMANCE)) {
            val p = MpvFlags.parse(preset)
            assertTrue(p.problems.isEmpty())
            assertTrue(p.options.isNotEmpty())
        }
        assertTrue(MpvFlags.parse(MpvPresets.DEFAULT).options.isEmpty())
    }

    @Test fun hardwareDecodingSwitchControlsHwdec() {
        fun hwdec(s: AppSettings) = MpvConfig.defaults(s, null, null).first { it.first == "hwdec" }.second
        assertEquals("mediacodec,mediacodec-copy", hwdec(AppSettings(hardwareDecoding = true)))
        assertEquals("no", hwdec(AppSettings(hardwareDecoding = false)))
    }

    @Test fun audioOutputMapping() {
        fun ao(n: Int) = MpvConfig.defaults(AppSettings(audioOutput = n), null, null).first { it.first == "ao" }.second
        assertEquals("audiotrack,opensles", ao(0))
        assertEquals("audiotrack", ao(1))
        assertEquals("opensles", ao(2))
    }

    @Test fun resumeAndFontsOnlyWhenGiven() {
        val none = MpvConfig.defaults(AppSettings(), null, null).map { it.first }
        assertFalse("start" in none)
        assertFalse("sub-fonts-dir" in none)
        val some = MpvConfig.defaults(AppSettings(), "/fonts", 83.5).toMap()
        assertEquals("83.500", some["start"])
        assertEquals("/fonts", some["sub-fonts-dir"])
    }

    @Test fun effectiveVoPrefersTheLastUserValue() {
        assertEquals("gpu", MpvConfig.effectiveVo(emptyList()))
        assertEquals("gpu-next", MpvConfig.effectiveVo(MpvFlags.parse("vo=gpu\nvo=gpu-next").options))
    }
}
