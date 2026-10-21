package ir.bna.assistant.camera

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * ارائه یک فایل موقت به برنامه دوربین تا عکس با کیفیت کامل در آن نوشته شود.
 * جایگزین سبک FileProvider بدون کتابخانه اضافه.
 */
class PhotoProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(context!!.cacheDir), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "ir.bna.assistant.photo"
        val URI: Uri = Uri.parse("content://$AUTHORITY/photo.jpg")
        fun file(cache: File) = File(cache, "photo.jpg")
    }
}
