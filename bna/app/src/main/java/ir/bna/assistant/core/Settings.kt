package ir.bna.assistant.core

import android.content.Context

enum class ProcessingMode(val fa: String) { LOCAL("روی گوشی"), REMOTE("اینترنت و سرور"), HYBRID("ترکیبی") }

/** کانال خروجی پیام‌ها؛ برای جلوگیری از تداخل با TalkBack */
enum class SpeechOutput(val fa: String) { AUTO("خودکار"), TALKBACK("فقط تاک‌بک"), APP_VOICE("صدای خود برنامه") }

/**
 * تنظیمات کاربر. خواسته کاربر همیشه با قابلیت واقعی گوشی AND می‌شود،
 * پس انتخاب دستی هرگز سخت‌افزار ناموجود را فعال نمی‌کند.
 */
class Settings(ctx: Context, private val caps: Capabilities) {
    private val p = ctx.getSharedPreferences("bna_settings", Context.MODE_PRIVATE)

    var wantCompass: Boolean
        get() = p.getBoolean("compass", true); set(v) = p.edit().putBoolean("compass", v).apply()
    var wantCamera: Boolean
        get() = p.getBoolean("camera", true); set(v) = p.edit().putBoolean("camera", v).apply()
    var wantGps: Boolean
        get() = p.getBoolean("gps", true); set(v) = p.edit().putBoolean("gps", v).apply()
    var speechRate: Float
        get() = p.getFloat("rate", 1.0f); set(v) = p.edit().putFloat("rate", v).apply()
    var speechOutput: SpeechOutput
        get() = SpeechOutput.valueOf(p.getString("output", SpeechOutput.AUTO.name)!!)
        set(v) = p.edit().putString("output", v.name).apply()
    var processingMode: ProcessingMode
        get() = ProcessingMode.valueOf(p.getString("mode", defaultMode().name)!!)
        set(v) = p.edit().putString("mode", v.name).apply()
    /** فقط برای حالت سرور: هشدار هزینه اینترنت حجمی */
    var allowMeteredUpload: Boolean
        get() = p.getBoolean("metered", false); set(v) = p.edit().putBoolean("metered", v).apply()
    /** فاصله زمانی بررسی موقعیت در جهت‌یابی پیوسته (ثانیه) */
    var trackIntervalSec: Int
        get() = p.getInt("track_sec", 5); set(v) = p.edit().putInt("track_sec", v).apply()

    // هوش مصنوعی اینترنتی (سازگار با OpenAI)
    var aiUrl: String
        get() = p.getString("ai_url", DEFAULT_AI_URL)!!; set(v) = p.edit().putString("ai_url", v).apply()
    var aiModel: String
        get() = p.getString("ai_model", DEFAULT_AI_MODEL)!!; set(v) = p.edit().putString("ai_model", v).apply()
    var aiKey: String
        get() = p.getString("ai_key", "")!!; set(v) = p.edit().putString("ai_key", v).apply()
    val aiConfigured get() = aiKey.isNotBlank() && aiUrl.isNotBlank() && aiModel.isNotBlank()

    // مقادیر مؤثر = خواسته کاربر و وجود واقعی
    val compassEnabled get() = wantCompass && caps.hasCompass
    val cameraEnabled get() = wantCamera && caps.hasCamera
    val gpsEnabled get() = wantGps && caps.hasGps

    /** ترکیبی: با کلید و اینترنت از هوش مصنوعی، وگرنه تحلیل ساده محلی */
    private fun defaultMode() = ProcessingMode.HYBRID

    companion object {
        const val DEFAULT_AI_URL = "https://api.gapgpt.app/v1/chat/completions"
        const val DEFAULT_AI_MODEL = "gpt-4o-mini"
    }
}
