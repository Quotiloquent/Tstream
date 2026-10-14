package com.noapmat.tstream.player

import android.content.Context
import com.noapmat.tstream.R
import java.io.File

/**
 * libass (mpv's subtitle renderer) reads fonts from a plain folder, not from Android's resources. The app's own
 * Google Sans files are copied there once, so text subtitles always have a real font to fall back on even when a
 * subtitle names a font the phone doesn't have. Fonts embedded in the video (MKV attachments) are used by mpv
 * directly and are not affected.
 */
object MpvFonts {
    fun ensure(ctx: Context): String? = try {
        val dir = File(ctx.filesDir, "mpv-fonts").apply { mkdirs() }
        copyOnce(ctx, R.font.google_sans_regular, File(dir, "GoogleSans-Regular.ttf"))
        copyOnce(ctx, R.font.google_sans_bold, File(dir, "GoogleSans-Bold.ttf"))
        dir.absolutePath
    } catch (e: Exception) {
        null // subtitles still work with whatever mpv finds; only the fallback font is missing
    }

    private fun copyOnce(ctx: Context, res: Int, out: File) {
        if (out.length() > 0) return
        val tmp = File(out.parentFile, out.name + ".tmp")
        ctx.resources.openRawResource(res).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(out)) tmp.delete()
    }
}
