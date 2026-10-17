package ir.bna.assistant.ui

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** ساخت دکمه‌ها و متن‌های بزرگ و سازگار با TalkBack */
object Ui {
    fun column(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    fun button(a: Activity, text: String, onClick: () -> Unit) = Button(a).apply {
        this.text = text; contentDescription = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); minHeight = 160
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 12, 0, 12) }
    }
    fun status(a: Activity) = TextView(a).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(8, 24, 8, 24)
    }
    /** نوشتن پیام در صفحه؛ اگر announce باشد TalkBack آن را می‌خواند */
    fun show(v: TextView, text: String, announce: Boolean) {
        v.text = text
        if (announce) v.announceForAccessibility(text)
    }
}
