package ir.bna.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import ir.bna.assistant.ai.RemoteAnalyzer
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.Capabilities
import ir.bna.assistant.core.ProcessingMode
import ir.bna.assistant.core.Settings
import ir.bna.assistant.core.SpeechOutput
import ir.bna.assistant.speech.Speaker

class SettingsActivity : Activity() {
    private lateinit var speaker: Speaker
    private lateinit var s: Settings

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val caps = Capabilities.detect(this)
        s = Settings(this, caps)
        val col = Ui.column(this)
        val status = Ui.status(this)
        speaker = Speaker(this, { s.speechOutput }) { t, a -> runOnUiThread { Ui.show(status, t, a) } }
        speaker.rate = s.speechRate
        col.addView(status)

        fun toggle(label: String, has: Boolean, get: () -> Boolean, set: (Boolean) -> Unit) =
            Ui.button(this, "$label: ${state(has, get())}") {}.also { btn ->
                btn.setOnClickListener {
                    if (!has) { speaker.say("این گوشی $label ندارد و نمی‌توان آن را روشن کرد."); return@setOnClickListener }
                    set(!get())
                    label(btn, "$label: ${state(has, get())}")
                }
            }

        col.addView(toggle("قطب‌نما", caps.hasCompass, { s.wantCompass }) { s.wantCompass = it })
        col.addView(toggle("دوربین", caps.hasCamera, { s.wantCamera }) { s.wantCamera = it })
        col.addView(toggle("جی‌پی‌اس", caps.hasGps, { s.wantGps }) { s.wantGps = it })

        val modeBtn = Ui.button(this, "محل پردازش: ${s.processingMode.fa}") {}
        modeBtn.setOnClickListener {
            val all = ProcessingMode.values()
            s.processingMode = all[(s.processingMode.ordinal + 1) % all.size]
            var msg = "محل پردازش: ${s.processingMode.fa}"
            if (s.processingMode != ProcessingMode.LOCAL) msg += ". توجه: عکس‌ها به سرور هوش مصنوعی فرستاده می‌شوند و حجم اینترنت مصرف می‌کنند."
            if (s.processingMode == ProcessingMode.LOCAL) msg += ". توجه: روی گوشی فقط نور محیط سنجیده می‌شود."
            label(modeBtn, "محل پردازش: ${s.processingMode.fa}", msg)
        }
        col.addView(modeBtn)

        val outBtn = Ui.button(this, "خروجی گفتار: ${s.speechOutput.fa}") {}
        outBtn.setOnClickListener {
            val all = SpeechOutput.values()
            s.speechOutput = all[(s.speechOutput.ordinal + 1) % all.size]
            label(outBtn, "خروجی گفتار: ${s.speechOutput.fa}")
        }
        col.addView(outBtn)

        val rateBtn = Ui.button(this, "سرعت گفتار: ${s.speechRate}") {}
        rateBtn.setOnClickListener {
            val rates = listOf(0.75f, 1.0f, 1.25f, 1.5f)
            s.speechRate = rates[(rates.indexOf(s.speechRate) + 1).mod(rates.size)]
            speaker.rate = s.speechRate
            label(rateBtn, "سرعت گفتار: ${s.speechRate}")
        }
        col.addView(rateBtn)

        val trackBtn = Ui.button(this, "فاصله بررسی جهت: ${s.trackIntervalSec} ثانیه") {}
        trackBtn.setOnClickListener {
            val secs = listOf(3, 5, 10, 15, 30)
            s.trackIntervalSec = secs[(secs.indexOf(s.trackIntervalSec) + 1).mod(secs.size)]
            label(trackBtn, "فاصله بررسی جهت: ${s.trackIntervalSec} ثانیه")
        }
        col.addView(trackBtn)

        // هوش مصنوعی
        val keyBtn = Ui.button(this, keyLabel()) {}
        keyBtn.setOnClickListener {
            // کلید هرگز نمایش یا خوانده نمی‌شود؛ فقط تنظیم‌بودن آن اعلام می‌شود
            input("کلید هوش مصنوعی", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                s.aiKey = it.trim(); label(keyBtn, keyLabel())
            }
        }
        col.addView(keyBtn)
        val urlBtn = Ui.button(this, "نشانی سرور هوش مصنوعی") {}
        urlBtn.setOnClickListener {
            input("نشانی سرور (سازگار با OpenAI)", s.aiUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
                s.aiUrl = it.trim().ifEmpty { Settings.DEFAULT_AI_URL }; speaker.say("نشانی ذخیره شد: ${s.aiUrl}")
            }
        }
        col.addView(urlBtn)
        val modelBtn = Ui.button(this, "مدل هوش مصنوعی: ${s.aiModel}") {}
        modelBtn.setOnClickListener {
            input("نام مدل", s.aiModel, InputType.TYPE_CLASS_TEXT) {
                s.aiModel = it.trim().ifEmpty { Settings.DEFAULT_AI_MODEL }; label(modelBtn, "مدل هوش مصنوعی: ${s.aiModel}")
            }
        }
        col.addView(modelBtn)
        col.addView(Ui.button(this, "آزمایش اتصال هوش مصنوعی") { testAi() })

        col.addView(Ui.button(this, "خواندن گزارش خطاها") {
            val errs = AppLog.read().lines().filter { "[ERROR]" in it }.takeLast(30)
            val text = if (errs.isEmpty()) "خطایی ثبت نشده است." else errs.joinToString("\n\n")
            val tv = android.widget.TextView(this).apply { this.text = text; textSize = 16f; setTextIsSelectable(true); setPadding(32, 16, 32, 16) }
            android.app.AlertDialog.Builder(this).setTitle("خطاها (${errs.size})")
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("بستن", null).show()
            speaker.say(if (errs.isEmpty()) text else "${errs.size} خطا ثبت شده است. متن در صفحه است؛ با انگشت بخوانید.")
        })
        col.addView(Ui.button(this, "پاک کردن گزارش خطاها") { AppLog.clear(); speaker.say("گزارش پاک شد.") })
        col.addView(Ui.button(this, "زبان / Language") {
            val labels = ir.bna.assistant.core.Lang.options.map { it.second }.toTypedArray()
            val cur = ir.bna.assistant.core.Lang.options.indexOfFirst { it.first == ir.bna.assistant.core.Lang.code }.coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle("زبان / Language")
                .setSingleChoiceItems(labels, cur) { d, which ->
                    val code = ir.bna.assistant.core.Lang.options[which].first
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putString("lang", code).apply()
                    ir.bna.assistant.core.Lang.code = code
                    d.dismiss()
                    speaker.say(if (code == "en") "Language changed to English." else "زبان به فارسی تغییر کرد.")
                }.setNegativeButton("بستن", null).show()
        })
        col.addView(Ui.button(this, "بازگشت") { finish() })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun keyLabel() = "کلید هوش مصنوعی: " + if (s.aiKey.isBlank()) "تنظیم نشده" else "تنظیم شده"

    private fun label(btn: Button, text: String, say: String = text) {
        btn.text = text; btn.contentDescription = text; speaker.say(say)
    }

    private fun input(title: String, value: String, type: Int, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(value); inputType = type; contentDescription = title; textSize = 20f }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("ذخیره") { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("انصراف", null).show()
        et.requestFocus()
    }

    /** یک تصویر کوچک ساده می‌فرستد تا کلید، نشانی و مدل بررسی شوند */
    private fun testAi() {
        if (!s.aiConfigured) { speaker.say("ابتدا کلید هوش مصنوعی را وارد کنید."); return }
        if (!Capabilities.internetNow(this)) { speaker.say("اینترنت وصل نیست."); return }
        speaker.say("در حال آزمایش اتصال…")
        val img = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        Thread {
            RemoteAnalyzer(s) { true }.analyze(img) { r, err ->
                runOnUiThread { speaker.say(if (r != null) "اتصال موفق بود. هوش مصنوعی پاسخ داد." else "اتصال ناموفق: ${err ?: "خطای نامشخص"}") }
            }
        }.start()
    }

    private fun state(has: Boolean, want: Boolean) = when { !has -> "در گوشی موجود نیست"; want -> "روشن"; else -> "خاموش" }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}
