package ir.bna.assistant.ai

import android.graphics.Bitmap
import ir.bna.assistant.core.ProcessingMode

/**
 * تصمیم می‌گیرد هر تحلیل کجا اجرا شود.
 * تحلیل محلی فعلاً فقط نور را می‌سنجد، پس فقط در حالت «روی گوشی» یا قطعی اینترنت در حالت ترکیبی استفاده می‌شود.
 */
class ProcessingRouter(
    private val local: SceneAnalyzer,
    private val remote: SceneAnalyzer,
    private val mode: () -> ProcessingMode
) {
    fun analyze(img: Bitmap, cb: (SceneResult?, String?) -> Unit) {
        when (mode()) {
            ProcessingMode.LOCAL -> local.analyze(img, cb)
            ProcessingMode.REMOTE -> remote.analyze(img, cb)
            ProcessingMode.HYBRID -> remote.analyze(img) { r, err ->
                if (r != null) cb(r, null)
                else local.analyze(img) { l, e -> cb(l?.copy(descriptionFa = "$err تحلیل ساده محلی: ${l.descriptionFa}"), e) }
            }
        }
    }
}
