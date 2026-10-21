package dev.phonerecall

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Clio's export for the laptop without run-as (the fast build isn't debuggable). Readable only with
 * android.permission.DUMP, which only the adb shell holds:
 *   adb exec-out content read --uri content://dev.phonerecall.clio/export.jsonl > export.jsonl
 * Regenerates files/clio/export.jsonl, then hands it out read-only. Debug and fast builds only.
 */
class ClioExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.lastPathSegment != "export.jsonl" || mode != "r") throw SecurityException("export.jsonl, read-only")
        val f = dev.phonerecall.clio.Clio.get()?.export() ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/x-ndjson"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
