package dev.phonerecall

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.StrictMode

/**
 * Debug builds only: runs at process start, before the Application. StrictMode logs main-thread disk, network
 * and slow calls to logcat (tag StrictMode), and [dev.phonerecall.clio.MainWatch] puts main-thread stalls in Clio.
 */
class DebugInit : ContentProvider() {
    override fun onCreate(): Boolean {
        // Robolectric's main looper only runs when a test idles it, so the watchdog would see one long stall.
        if (android.os.Build.FINGERPRINT == "robolectric") return true
        // StrictMode in the debuggable build only; the fast build keeps just the watchdog.
        if (context!!.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().penaltyLog().build(),
        )
        dev.phonerecall.clio.MainWatch.start()
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
