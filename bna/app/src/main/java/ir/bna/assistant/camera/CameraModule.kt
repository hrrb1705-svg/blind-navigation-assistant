package ir.bna.assistant.camera

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore

/**
 * نسخه سبک: با برنامه دوربین خود گوشی عکس می‌گیرد و در فایل موقت ذخیره می‌کند.
 * عکس هنگام خواندن کوچک می‌شود تا حافظه A12 پر نشود.
 * در نسخه پیشرفته با CameraX و تحلیل زنده جایگزین می‌شود، بدون تغییر بقیه برنامه.
 */
object CameraModule {
    const val REQUEST = 1001
    private const val MAX_SIDE = 1024

    fun open(act: Activity): Boolean {
        PhotoProvider.file(act.cacheDir).delete()
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.URI)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try { act.startActivityForResult(i, REQUEST); true } catch (e: Exception) { false }
    }

    /** عکس کامل از فایل؛ اگر دوربین فایل ننوشت، تصویر کوچک داخل نتیجه */
    fun extract(act: Activity, data: Intent?): Bitmap? {
        val f = PhotoProvider.file(act.cacheDir)
        if (f.length() > 0) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= MAX_SIDE) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.let { return it }
        }
        @Suppress("DEPRECATION")
        return data?.extras?.get("data") as? Bitmap
    }
}
