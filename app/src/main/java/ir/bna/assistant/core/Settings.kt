package ir.bna.assistant.core

import android.content.Context

enum class ProcessingMode(val fa: String) { LOCAL("روی گوشی"), REMOTE("اینترنت و سرور"), HYBRID("ترکیبی") }

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
    var processingMode: ProcessingMode
        get() = ProcessingMode.valueOf(p.getString("mode", defaultMode().name)!!)
        set(v) = p.edit().putString("mode", v.name).apply()
    /** فقط برای حالت سرور: هشدار هزینه اینترنت حجمی */
    var allowMeteredUpload: Boolean
        get() = p.getBoolean("metered", false); set(v) = p.edit().putBoolean("metered", v).apply()

    // مقادیر مؤثر = خواسته کاربر و وجود واقعی
    val compassEnabled get() = wantCompass && caps.hasCompass
    val cameraEnabled get() = wantCamera && caps.hasCamera
    val gpsEnabled get() = wantGps && caps.hasGps

    /** پیش‌فرض بر اساس A12: محلی برای حفظ حریم خصوصی و کار آفلاین */
    private fun defaultMode() = if (caps.tier == Tier.HIGH) ProcessingMode.HYBRID else ProcessingMode.LOCAL
}
