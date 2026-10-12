package com.noapmat.tsream.cache

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.noapmat.tsream.App

/**
 * Swiping the app away from Recents usually kills the process without running any Activity.onDestroy, so the
 * torrent kept its cache until the next launch. A started service declared with stopWithTask="false" is told
 * about that swipe ([onTaskRemoved]) just before the process goes, which is the moment to stop the torrent and
 * delete the cache. (The wipe at the next start remains as the fallback for crashes and force-stops.)
 */
class ExitCleanupService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = application as App
        app.shutDown()
        // libtorrent finishes removing the torrent asynchronously and may still touch the files for a moment:
        // wipe once more shortly after, if the process is still alive by then.
        Thread({
            try {
                Thread.sleep(1500)
                // the process survived and the user already opened a torrent again: leave its cache alone
                if (!app.isTorrentOpen()) StaleCache.wipe(app)
            } catch (e: Exception) {
                // process is going away
            }
        }, "exit-wipe").start()
        stopSelf()
    }

    companion object {
        /** Called from a visible activity (starting a service from the background is not allowed on Android 12+). */
        fun start(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, ExitCleanupService::class.java))
            } catch (e: Exception) {
                Log.w("ExitCleanup", "could not start the exit-cleanup service", e)
            }
        }
    }
}
