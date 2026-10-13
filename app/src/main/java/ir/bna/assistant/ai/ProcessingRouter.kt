package ir.bna.assistant.ai

import android.graphics.Bitmap
import ir.bna.assistant.core.ProcessingMode

/** تصمیم می‌گیرد هر تحلیل کجا اجرا شود؛ در قطعی اینترنت به محلی برمی‌گردد. */
class ProcessingRouter(
    private val local: SceneAnalyzer,
    private val remote: SceneAnalyzer,
    private val mode: () -> ProcessingMode
) {
    fun analyze(img: Bitmap, cb: (SceneResult?, String?) -> Unit) {
        when (mode()) {
            ProcessingMode.LOCAL -> local.analyze(img, cb)
            ProcessingMode.REMOTE, ProcessingMode.HYBRID ->
                if (remote.isAvailable()) remote.analyze(img) { r, err ->
                    if (r == null) local.analyze(img, cb) else cb(r, null)
                } else local.analyze(img) { r, e ->
                    cb(r?.copy(descriptionFa = "سرور در دسترس نیست؛ تحلیل محلی انجام شد. " + r.descriptionFa), e)
                }
        }
    }
}
