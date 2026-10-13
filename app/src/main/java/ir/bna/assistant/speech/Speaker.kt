package ir.bna.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import ir.bna.assistant.core.AppLog
import java.util.Locale

/** خروجی گفتار فارسی. اگر موتور فارسی نباشد، پیام در صفحه نمایش داده می‌شود و TalkBack آن را می‌خواند. */
class Speaker(ctx: Context, private val onFallback: (String) -> Unit) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    private var ready = false
    private val pending = mutableListOf<String>()
    var rate = 1.0f

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { AppLog.e("موتور گفتار راه‌اندازی نشد"); return }
        val r = tts.setLanguage(Locale("fa", "IR"))
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            AppLog.e("زبان فارسی در موتور گفتار نصب نیست")
            onFallback("موتور گفتار فارسی پیدا نشد. پیام‌ها روی صفحه نوشته می‌شوند.")
        }
        val q = pending.toList(); pending.clear()
        if (ready) q.lastOrNull()?.let { say(it) }
    }

    fun say(text: String) {
        onFallback(text) // همیشه در صفحه هم نوشته شود
        if (!ready) { if (pending.size < 5) pending.add(text); return }
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bna")
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
