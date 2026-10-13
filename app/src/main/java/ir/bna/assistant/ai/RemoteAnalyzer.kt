package ir.bna.assistant.ai

import android.graphics.Bitmap

/** جای خالی برای اتصال به سرور در مراحل بعد. فعلاً غیرفعال. */
class RemoteAnalyzer(private val hasInternet: () -> Boolean) : SceneAnalyzer {
    override val name = "سرور"
    override fun isAvailable() = false && hasInternet()
    override fun analyze(image: Bitmap, cb: (SceneResult?, String?) -> Unit) {
        cb(null, "تحلیل از طریق اینترنت هنوز راه‌اندازی نشده است.")
    }
}
