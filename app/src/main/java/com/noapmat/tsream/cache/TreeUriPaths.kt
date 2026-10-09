package com.noapmat.tsream.cache

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.noapmat.tsream.torrent.StoragePaths
import java.io.File

/** Turns the folder the user picked in the system picker (a SAF tree Uri) into a regular file path. */
object TreeUriPaths {
    /** @return absolute path, or null for providers that are not real folders (cloud drives, Downloads "virtual" roots...). */
    fun resolve(ctx: Context, tree: Uri): String? {
        if (tree.authority != "com.android.externalstorage.documents") return null
        val (volume, rel) = StoragePaths.parseDocId(DocumentsContract.getTreeDocumentId(tree)) ?: return null
        val root: File = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            volumeRoot(ctx, volume) ?: return null
        }
        return if (rel.isEmpty()) root.absolutePath else File(root, rel).absolutePath
    }

    private fun volumeRoot(ctx: Context, uuid: String): File? {
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = ctx.getSystemService(StorageManager::class.java)
            sm.storageVolumes.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.directory?.let { return it }
        }
        return File("/storage/$uuid").takeIf { it.exists() }
    }

    /** Folders inside the app's own storage never need extra permissions. */
    fun isAppPrivate(ctx: Context, path: String) =
        path.contains("/Android/data/${ctx.packageName}") || path.startsWith(ctx.filesDir.parent ?: "\u0000")
}
