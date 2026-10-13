package ir.bna.assistant.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.Capabilities
import ir.bna.assistant.core.ProcessingMode
import ir.bna.assistant.core.Settings
import ir.bna.assistant.speech.Speaker

class SettingsActivity : Activity() {
    private lateinit var speaker: Speaker

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val caps = Capabilities.detect(this)
        val s = Settings(this, caps)
        val col = Ui.column(this)
        val status = Ui.status(this)
        speaker = Speaker(this) { runOnUiThread { status.text = it } }
        speaker.rate = s.speechRate
        col.addView(status)

        fun toggle(label: String, has: Boolean, get: () -> Boolean, set: (Boolean) -> Unit) =
            Ui.button(this, "$label: ${state(has, get())}") {}.also { btn ->
                btn.setOnClickListener {
                    if (!has) { speaker.say("این گوشی $label ندارد و نمی‌توان آن را روشن کرد."); return@setOnClickListener }
                    set(!get())
                    btn.text = "$label: ${state(has, get())}"; btn.contentDescription = btn.text
                    speaker.say(btn.text.toString())
                }
            }

        col.addView(toggle("قطب‌نما", caps.hasCompass, { s.wantCompass }) { s.wantCompass = it })
        col.addView(toggle("دوربین", caps.hasCamera, { s.wantCamera }) { s.wantCamera = it })
        col.addView(toggle("جی‌پی‌اس", caps.hasGps, { s.wantGps }) { s.wantGps = it })

        val modeBtn = Ui.button(this, "محل پردازش: ${s.processingMode.fa}") {}
        modeBtn.setOnClickListener {
            val all = ProcessingMode.values()
            s.processingMode = all[(s.processingMode.ordinal + 1) % all.size]
            modeBtn.text = "محل پردازش: ${s.processingMode.fa}"; modeBtn.contentDescription = modeBtn.text
            var msg = modeBtn.text.toString()
            if (s.processingMode != ProcessingMode.LOCAL) msg += ". توجه: در حالت سرور، عکس‌ها به اینترنت فرستاده می‌شوند و حجم مصرف می‌کنند."
            speaker.say(msg)
        }
        col.addView(modeBtn)

        val rateBtn = Ui.button(this, "سرعت گفتار: ${s.speechRate}") {}
        rateBtn.setOnClickListener {
            val rates = listOf(0.75f, 1.0f, 1.25f, 1.5f)
            s.speechRate = rates[(rates.indexOf(s.speechRate) + 1).mod(rates.size)]
            speaker.rate = s.speechRate
            rateBtn.text = "سرعت گفتار: ${s.speechRate}"; rateBtn.contentDescription = rateBtn.text
            speaker.say(rateBtn.text.toString())
        }
        col.addView(rateBtn)

        col.addView(Ui.button(this, "خواندن گزارش خطاها") {
            val log = AppLog.read().lines().filter { "[ERROR]" in it }.takeLast(5)
            speaker.say(if (log.isEmpty()) "خطایی ثبت نشده است." else log.joinToString(". "))
        })
        col.addView(Ui.button(this, "پاک کردن گزارش خطاها") { AppLog.clear(); speaker.say("گزارش پاک شد.") })
        col.addView(Ui.button(this, "بازگشت") { finish() })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun state(has: Boolean, want: Boolean) = when { !has -> "در گوشی موجود نیست"; want -> "روشن"; else -> "خاموش" }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}
