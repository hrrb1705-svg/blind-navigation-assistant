package ir.bna.assistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.SpeechOutput
import java.util.Locale

/**
 * خروجی گفتار فارسی.
 * اگر TalkBack روشن باشد، صدای برنامه و TalkBack همدیگر را قطع می‌کنند؛
 * پس در حالت خودکار پیام فقط به TalkBack سپرده می‌شود (show با announce=true).
 */
class Speaker(
    ctx: Context,
    private val output: () -> SpeechOutput,
    private val show: (text: String, announce: Boolean) -> Unit
) : TextToSpeech.OnInitListener {
    private val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val handler = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(ctx, this)
    private var ready = false
    private val pending = mutableListOf<String>()
    var rate = 1.0f

    /** پس از تغییر زبان برنامه، زبان موتور گفتار هم عوض می‌شود */
    fun applyLanguage() { if (ready) tts.setLanguage(ir.bna.assistant.core.Lang.locale()) }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { AppLog.e("موتور گفتار راه‌اندازی نشد"); return }
        val r = tts.setLanguage(ir.bna.assistant.core.Lang.locale())
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) AppLog.e("زبان فارسی در موتور گفتار نصب نیست")
        val q = pending.toList(); pending.clear()
        if (ready) q.lastOrNull()?.let { speakNow(it) }
    }

    private fun talkBackOn() = am.isEnabled && am.isTouchExplorationEnabled

    fun say(text: String) {
        val viaTalkBack = when (output()) {
            SpeechOutput.TALKBACK -> true
            SpeechOutput.APP_VOICE -> false
            SpeechOutput.AUTO -> talkBackOn() || !ready
        }
        show(text, viaTalkBack)
        if (viaTalkBack) { tts.stop(); return }
        if (!ready) { if (pending.size < 5) pending.add(text); return }
        // اگر TalkBack روشن است، کمی صبر تا بازخورد لمس TalkBack تمام شود و گفتار ما را قطع نکند
        if (talkBackOn()) handler.postDelayed({ speakNow(text) }, 800) else speakNow(text)
    }

    private fun speakNow(text: String) {
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bna")
    }

    fun shutdown() { handler.removeCallbacksAndMessages(null); tts.stop(); tts.shutdown() }
}
