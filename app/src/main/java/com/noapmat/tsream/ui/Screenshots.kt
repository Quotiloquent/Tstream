package com.noapmat.tsream.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves screenshots to Pictures/Tstream: a normal gallery folder, separate from the torrent cache. */
object Screenshots {
    fun save(ctx: Context, bmp: Bitmap, title: String, positionMs: Long): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val s = positionMs / 1000
        val clock = "%02d-%02d-%02d".format(s / 3600, s / 60 % 60, s % 60)
        val base = title.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
        val name = "Tstream_${base}_${clock}_$stamp.png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tstream")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri: Uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, cv, null, null)
                "Pictures/Tstream/$name"
            } else {
                // Android 8/9: no storage permission needed for the app's own external pictures folder
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Tstream").apply { mkdirs() }
                File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, name).absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
