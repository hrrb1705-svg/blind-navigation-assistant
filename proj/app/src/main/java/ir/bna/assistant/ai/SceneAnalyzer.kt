package ir.bna.assistant.ai

import android.graphics.Bitmap

/** نتیجه تحلیل؛ همیشه احتمالی است، نه قطعی */
data class SceneResult(val descriptionFa: String, val source: String, val confidence: Float)

/** هر موتور هوش مصنوعی (محلی یا سرور) این رابط را پیاده می‌کند */
interface SceneAnalyzer {
    val name: String
    fun isAvailable(): Boolean
    fun analyze(image: Bitmap, cb: (SceneResult?, String?) -> Unit)
}
