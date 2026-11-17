package ir.bna.assistant.ai

import android.graphics.Bitmap
import android.graphics.Color

/**
 * تحلیل محلی بسیار ساده و آفلاین (بدون مدل): روشنایی و تاریکی تصویر.
 * جای مدل‌های واقعی تشخیص مانع (مثلاً TensorFlow Lite) در مراحل بعد است.
 */
class LocalBasicAnalyzer : SceneAnalyzer {
    override val name = "محلی ساده"
    override fun isAvailable() = true
    override fun analyze(image: Bitmap, cb: (SceneResult?, String?) -> Unit) {
        val w = image.width; val h = image.height
        var sum = 0L; var n = 0
        var y = 0
        while (y < h) { var x = 0
            while (x < w) { val c = image.getPixel(x, y)
                sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000; n++; x += 4 }
            y += 4 }
        val avg = if (n > 0) sum / n else 0
        val txt = when {
            avg < 40 -> "تصویر خیلی تاریک است. شاید جلوی دوربین پوشیده باشد یا محیط تاریک است."
            avg < 100 -> "نور محیط کم است."
            avg < 200 -> "نور محیط مناسب است."
            else -> "تصویر خیلی روشن است. شاید رو به نور شدید باشید."
        }
        cb(SceneResult("$txt تشخیص مانع در این نسخه هنوز فعال نیست.", name, 0.5f), null)
    }
}
