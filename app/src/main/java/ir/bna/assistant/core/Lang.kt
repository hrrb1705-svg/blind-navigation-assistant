package ir.bna.assistant.core

import java.util.Locale

/** زبان رابط کاربری: فارسی (پیش‌فرض) یا انگلیسی. برای افزودن زبان، یک map جدید در T اضافه کنید. */
object Lang {
    @Volatile var code: String = "fa"

    val options = listOf("fa" to "فارسی", "en" to "English")

    fun locale(): Locale = if (code == "en") Locale.US else Locale("fa", "IR")

    private val fa = mapOf(
        "title" to "دستیار ناوبری",
        "menu" to "منو",
        "where" to "کجا هستم؟",
        "photo" to "توصیف عکس",
        "direction" to "جهت‌یابی",
        "stop_dir" to "توقف جهت‌یابی",
        "route" to "مسیرها",
        "route_stop" to "پایان مسیر",
        "obstacle" to "ثبت مانع با عکس",
        "menu_features" to "قابلیت‌های گوشی",
        "menu_settings" to "تنظیمات",
        "menu_safety" to "راهنمای ایمنی",
        "menu_language" to "زبان / Language",
        "menu_guide" to "راهنمای کاربر",
        "close" to "بستن",
        "pick_lang" to "انتخاب زبان",
    )

    private val en = mapOf(
        "title" to "Navigation Assistant",
        "menu" to "Menu",
        "where" to "Where am I?",
        "photo" to "Describe photo",
        "direction" to "Direction",
        "stop_dir" to "Stop direction",
        "route" to "Routes",
        "route_stop" to "End route",
        "obstacle" to "Report obstacle with photo",
        "menu_features" to "Phone features",
        "menu_settings" to "Settings",
        "menu_safety" to "Safety guide",
        "menu_language" to "Language / زبان",
        "menu_guide" to "User guide",
        "close" to "Close",
        "pick_lang" to "Choose language",
    )

    fun t(key: String): String = (if (code == "en") en else fa)[key] ?: fa[key] ?: key
}
