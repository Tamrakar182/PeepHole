package np.com.manjultamrakar.peephole.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import np.com.manjultamrakar.peephole.Peephole

/** Auto-starts [Peephole] when the app process starts. */
class InspectorInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val autoInit = try {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
            info.metaData?.getBoolean("np.com.manjultamrakar.peephole.AUTO_INIT", true) ?: true
        } catch (_: Exception) {
            true
        }
        if (autoInit) Peephole.init(ctx)
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
