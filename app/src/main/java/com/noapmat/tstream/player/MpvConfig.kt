package com.noapmat.tstream.player

import com.noapmat.tstream.AppSettings
import java.util.Locale

/**
 * Parser for the user's own mpv options (Settings -> Advanced mpv options). The text is written like an
 * `mpv.conf`, one option per line:
 *
 *     # a comment (only at the start of a line, like mpv)
 *     profile=gpu-hq
 *     --deband=yes        <- a leading "--" is accepted, so command-line flags can be pasted as they are
 *     interpolation       <- no value means "yes"
 *     no-deband           <- "no-<name>" means "<name>=no"
 *     vf=lavfi=[eq=contrast=1.1]   <- only the first "=" splits; the value may contain more
 */
object MpvFlags {
    const val MAX_CHARS = 8000

    data class Option(val name: String, val value: String)
    data class Problem(val line: Int, val text: String, val reason: String)
    data class Parsed(val options: List<Option>, val problems: List<Problem>)

    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$")

    fun parse(text: String): Parsed {
        val options = ArrayList<Option>()
        val problems = ArrayList<Problem>()
        text.lineSequence().forEachIndexed { i, raw ->
            val lineNo = i + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line.startsWith("-") && !line.startsWith("--")) {
                problems += Problem(lineNo, line, "Use the long form, e.g. --name=value")
                return@forEachIndexed
            }
            val body = line.removePrefix("--")
            val eq = body.indexOf('=')
            var name = (if (eq < 0) body else body.substring(0, eq)).trim()
            val given: String? = if (eq < 0) null else body.substring(eq + 1).trim()
            val value: String = when {
                given != null -> unquote(given)
                name.startsWith("no-") && name.length > 3 -> { name = name.substring(3); "no" }
                else -> "yes"
            }
            when {
                !NAME.matches(name) -> problems += Problem(lineNo, line, "Not a valid option name")
                value.any { it.isISOControl() } -> problems += Problem(lineNo, line, "Contains control characters")
                else -> options += Option(name, value)
            }
        }
        return Parsed(options, problems)
    }

    private fun unquote(v: String): String =
        if (v.length >= 2 && (v.first() == '"' || v.first() == '\'') && v.last() == v.first()) v.substring(1, v.length - 1) else v
}

/** Ready-made option sets for the buttons in Settings. They only fill the text box; the user can edit them. */
object MpvPresets {
    const val DEFAULT = ""

    val QUALITY = """
        # Quality first (needs a fast, recent phone; lower it if playback stutters)
        profile=gpu-hq
        scale=ewa_lanczossharp
        deband=yes
        video-sync=display-resample
        interpolation=yes
        tscale=oversample
    """.trimIndent()

    val PERFORMANCE = """
        # Smoothness first (older / slower phones, heavy 4K or 10-bit files)
        profile=fast
        vd-lavc-fast=yes
        vd-lavc-skiploopfilter=nonkey
        vd-lavc-threads=0
        framedrop=vo
    """.trimIndent()
}

/** The options the app itself gives mpv, built from the Settings screen. User flags are applied after these. */
object MpvConfig {
    /** How much video mpv may read ahead of the playhead; also what the torrent window is told to expect. */
    const val READAHEAD_SECS = 15

    fun defaults(s: AppSettings, fontsDir: String?, startSecs: Double?): List<Pair<String, String>> {
        val o = ArrayList<Pair<String, String>>()
        // video: the GPU renderer on Android's surface; MediaCodec first (zero-copy), its copy mode second,
        // software when neither can decode the stream (mpv falls back by itself)
        o += "vo" to "gpu"
        o += "gpu-context" to "android"
        o += "hwdec" to if (s.hardwareDecoding) "mediacodec,mediacodec-copy" else "no"
        o += "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"
        if (s.fastDecode) {
            o += "vd-lavc-fast" to "yes"
            o += "vd-lavc-skiploopfilter" to "nonkey"
        }
        // audio
        o += "ao" to when (s.audioOutput) { 1 -> "audiotrack"; 2 -> "opensles"; else -> "audiotrack,opensles" }
        // the app draws its own controls: no mpv OSD, no key bindings
        o += "osd-level" to "0"
        o += "input-default-bindings" to "no"
        o += "input-vo-keyboard" to "no"
        // lifecycle: the surface is attached after init, and the last frame stays up when the file ends
        o += "force-window" to "no"
        o += "idle" to "once"
        o += "keep-open" to "yes"
        o += "sub-auto" to "no" // subtitles come from the picker (torrent files), not from a folder scan
        // subtitles: plain text uses this size, ASS/SSA keep the script's own styles
        o += "sub-font-size" to s.subtitleFontSize.toString()
        if (fontsDir != null) {
            o += "sub-fonts-dir" to fontsDir
            o += "osd-fonts-dir" to fontsDir
        }
        // buffering: the data comes from a torrent window, so keep mpv's own read-ahead small and bounded
        o += "cache" to "yes"
        o += "cache-secs" to READAHEAD_SECS.toString()
        o += "demuxer-readahead-secs" to READAHEAD_SECS.toString()
        o += "demuxer-max-bytes" to s.playerRamBytes.toString()
        o += "demuxer-max-back-bytes" to (minOf(s.behindMb, 32).toLong() shl 20).toString()
        o += "cache-pause" to "yes"
        o += "cache-pause-initial" to "yes"
        o += "cache-pause-wait" to String.format(Locale.US, "%.1f", s.rebufferMs / 1000.0)
        o += "screenshot-format" to "png"
        if (startSecs != null && startSecs > 0) o += "start" to String.format(Locale.US, "%.3f", startSecs)
        return o
    }

    /** The value of `vo` that wins after the user's flags are applied (needed to bring the video back after a Home trip). */
    fun effectiveVo(user: List<MpvFlags.Option>): String = user.lastOrNull { it.name == "vo" }?.value ?: "gpu"
}
