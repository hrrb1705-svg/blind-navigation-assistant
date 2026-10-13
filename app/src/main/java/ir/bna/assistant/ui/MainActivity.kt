package ir.bna.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import ir.bna.assistant.ai.LocalBasicAnalyzer
import ir.bna.assistant.ai.ProcessingRouter
import ir.bna.assistant.ai.RemoteAnalyzer
import ir.bna.assistant.camera.CameraModule
import ir.bna.assistant.compass.CompassModule
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.Capabilities
import ir.bna.assistant.core.Settings
import ir.bna.assistant.location.LocationModule
import ir.bna.assistant.speech.Speaker

class MainActivity : Activity() {
    private lateinit var caps: Capabilities
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var location: LocationModule
    private lateinit var compass: CompassModule
    private lateinit var router: ProcessingRouter
    private lateinit var status: TextView
    private var prevGpsForBearing: Location? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.init(this)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> AppLog.e("خطای پیش‌بینی‌نشده", e) ; android.os.Process.killProcess(android.os.Process.myPid()) }
        caps = Capabilities.detect(this)
        settings = Settings(this, caps)
        location = LocationModule(this)
        compass = CompassModule(this)
        router = ProcessingRouter(LocalBasicAnalyzer(), RemoteAnalyzer { caps.hasInternet }) { settings.processingMode }

        val col = Ui.column(this)
        status = Ui.status(this)
        col.addView(status)
        col.addView(Ui.button(this, "کجا هستم؟") { whereAmI() })
        col.addView(Ui.button(this, "جهت من کدام طرف است؟") { direction() })
        col.addView(Ui.button(this, "عکس و توصیف محیط") { takePhoto() })
        col.addView(Ui.button(this, "قابلیت‌های گوشی") { speaker.say(caps.describeFa()) })
        col.addView(Ui.button(this, "تنظیمات") { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(Ui.button(this, "راهنمای ایمنی") { speaker.say(SAFETY) })
        setContentView(ScrollView(this).apply { addView(col) })

        speaker = Speaker(this) { runOnUiThread { status.text = it } }
        speaker.rate = settings.speechRate
        speaker.say("دستیار مسیریابی آماده است. " + SAFETY_SHORT)
        AppLog.i("شروع برنامه. ${caps.describeFa()}")
    }

    override fun onResume() {
        super.onResume()
        if (::speaker.isInitialized) speaker.rate = settings.speechRate
        if (settings.compassEnabled) compass.start()
    }
    override fun onPause() { super.onPause(); compass.stop() }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }

    private fun whereAmI() {
        if (!settings.gpsEnabled) { speaker.say("جی‌پی‌اس در این گوشی نیست یا در تنظیمات خاموش است."); return }
        if (!ask(Manifest.permission.ACCESS_FINE_LOCATION, REQ_LOC)) return
        speaker.say("در حال دریافت موقعیت. لطفاً صبر کنید. ممکن است تا نیم دقیقه طول بکشد.")
        location.getOnce { _, msg -> speaker.say(msg) }
    }

    private fun direction() {
        if (settings.compassEnabled) {
            val az = compass.azimuth
            if (az == null) { speaker.say("قطب‌نما هنوز آماده نیست. چند ثانیه بعد دوباره امتحان کنید."); return }
            var m = "رو به ${CompassModule.directionFa(az)} هستید. حدود ${az.toInt()} درجه."
            if (compass.lowAccuracy) m += " دقت قطب‌نما کم است. گوشی را به شکل عدد هشت در هوا بچرخانید."
            speaker.say(m)
        } else {
            // راهکار جایگزین: دو بار موقعیت هنگام راه رفتن
            if (!ask(Manifest.permission.ACCESS_FINE_LOCATION, REQ_LOC)) return
            speaker.say("قطب‌نما در دسترس نیست. از جهت حرکت با جی‌پی‌اس استفاده می‌کنم.")
            location.getOnce { l, msg ->
                if (l == null) { speaker.say(msg); return@getOnce }
                val prev = prevGpsForBearing
                val br = prev?.let { CompassModule.bearingFromGps(it, l) }
                if (br == null) {
                    prevGpsForBearing = l
                    speaker.say("نقطه اول ثبت شد. با احتیاط حدود بیست قدم مستقیم راه بروید و دوباره این دکمه را بزنید.")
                } else {
                    prevGpsForBearing = null
                    speaker.say("جهت حرکت شما تقریباً ${CompassModule.directionFa(br)} بود. این روش دقت کمی دارد.")
                }
            }
        }
    }

    private fun takePhoto() {
        if (!settings.cameraEnabled) { speaker.say("دوربین در این گوشی نیست یا در تنظیمات خاموش است."); return }
        if (!ask(Manifest.permission.CAMERA, REQ_CAM)) return
        if (!CameraModule.open(this)) speaker.say("برنامه دوربین باز نشد.")
        else speaker.say("دوربین باز شد. دکمه گرفتن عکس معمولاً پایین وسط صفحه است.")
    }

    @Deprecated("")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != CameraModule.REQUEST) return
        val bmp = CameraModule.extract(data)
        if (res != RESULT_OK || bmp == null) { speaker.say("عکسی گرفته نشد."); return }
        speaker.say("در حال تحلیل تصویر.")
        Thread {
            router.analyze(bmp) { r, err ->
                runOnUiThread { speaker.say(r?.descriptionFa ?: err ?: "تحلیل ناموفق بود.") }
            }
        }.start()
    }

    private fun ask(perm: String, code: Int): Boolean {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(perm), code); return false
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(code, p, g)
        if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) speaker.say("اجازه داده شد. دوباره دکمه را بزنید.")
        else speaker.say("بدون این اجازه، این بخش کار نمی‌کند.")
    }

    companion object {
        const val REQ_LOC = 1; const val REQ_CAM = 2
        const val SAFETY_SHORT = "این برنامه جایگزین عصای سفید نیست."
        const val SAFETY = "این برنامه فقط کمکی است و جایگزین عصای سفید، سگ راهنما یا آموزش جهت‌یابی نیست. " +
            "تشخیص دوربین ممکن است اشتباه یا با تأخیر باشد. موقعیت جی‌پی‌اس ممکن است چند ده متر خطا داشته باشد. " +
            "همیشه به حواس و وسایل ایمنی خود اعتماد کنید."
    }
}
