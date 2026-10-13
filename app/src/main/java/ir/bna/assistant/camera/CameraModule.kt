package ir.bna.assistant.camera

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore

/**
 * نسخه سبک: از برنامه دوربین خود گوشی عکس کوچک می‌گیرد (کم‌مصرف برای A12).
 * در نسخه پیشرفته با CameraX و تحلیل زنده جایگزین می‌شود، بدون تغییر بقیه برنامه.
 */
object CameraModule {
    const val REQUEST = 1001
    fun open(act: Activity): Boolean {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return try { act.startActivityForResult(i, REQUEST); true } catch (e: Exception) { false }
    }
    @Suppress("DEPRECATION")
    fun extract(data: Intent?): Bitmap? = data?.extras?.get("data") as? Bitmap
}
