package ir.bna.assistant.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ir.bna.assistant.ai.LocalBasicAnalyzer
import ir.bna.assistant.ai.ProcessingRouter
import ir.bna.assistant.ai.RemoteAnalyzer
import ir.bna.assistant.camera.CameraModule
import ir.bna.assistant.compass.CompassModule
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.Capabilities
import ir.bna.assistant.core.Lang
import ir.bna.assistant.core.ProcessingMode
import ir.bna.assistant.core.Settings
import ir.bna.assistant.location.LocationModule
import ir.bna.assistant.route.Obstacle
import ir.bna.assistant.route.Obstacles
import ir.bna.assistant.route.Pt
import ir.bna.assistant.route.Route
import ir.bna.assistant.route.RouteService
import ir.bna.assistant.speech.Speaker
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var caps: Capabilities
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var location: LocationModule
    private lateinit var compass: CompassModule
    private lateinit var router: ProcessingRouter
    private lateinit var status: TextView
    private lateinit var trackBtn: Button
    private lateinit var routeBtn: Button
    private val handler = Handler(Looper.getMainLooper())

    // وضعیت جهت‌یابی پیوسته
    private var trackAnchor: Location? = null
    private var lastDirection: String? = null
    private var lastFixAt = 0L

    // وضعیت مسیر و موانع
    private var route: Route? = null
    private var stepIdx = 0
    private var announcedIdx = -1
    private var lastOffAlert = 0L
    private val announcedObs = mutableSetOf<Long>()
    private var pendingObstacle = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppLog.init(this)
        Lang.code = getSharedPreferences("ui", MODE_PRIVATE).getString("lang", "fa") ?: "fa"
        caps = Capabilities.detect(this)
        settings = Settings(this, caps)
        location = LocationModule(this)
        compass = CompassModule(this)
        router = ProcessingRouter(LocalBasicAnalyzer(), RemoteAnalyzer(settings) { Capabilities.internetNow(this) }) { settings.processingMode }
        route = RouteService.load(this)

        // ردیف بالا: عنوان در چپ، دکمه منو در راست
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = Lang.t("title"); textSize = 20f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.button(this, Lang.t("menu")) { showMenu() }.apply {
            textSize = 16f; minHeight = 0; minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        val col = Ui.column(this)
        status = Ui.status(this)
        col.addView(top)
        col.addView(status)
        col.addView(Ui.button(this, Lang.t("where")) { whereAmI() })
        trackBtn = Ui.button(this, Lang.t("direction")) { toggleTracking() }
        col.addView(trackBtn)
        routeBtn = Ui.button(this, Lang.t("route")) { if (route != null) endRoute(true) else askDestination() }
        col.addView(routeBtn)
        col.addView(Ui.button(this, Lang.t("photo")) { takePhoto(false) })
        col.addView(Ui.button(this, Lang.t("obstacle")) { takePhoto(true) })
        setContentView(ScrollView(this).apply { addView(col) })
        setRouteBtn()

        speaker = Speaker(this, { settings.speechOutput }) { t, a -> runOnUiThread { Ui.show(status, t, a) } }
        speaker.rate = settings.speechRate
        speaker.say(tr("دستیار مسیریابی آماده است. " + SAFETY_SHORT, "Navigation assistant is ready. This app does not replace a white cane."))
        AppLog.i("شروع برنامه. ${caps.describeFa()}")
    }

    override fun onResume() {
        super.onResume()
        if (::speaker.isInitialized) speaker.rate = settings.speechRate
        if (settings.compassEnabled) compass.start()
    }
    override fun onPause() { super.onPause(); compass.stop() }
    override fun onDestroy() { stopTracking(silent = true); speaker.shutdown(); super.onDestroy() }

    private fun tr(fa: String, en: String) = if (Lang.code == "en") en else fa

    // ---------- منو ----------
    private fun showMenu() {
        val items = arrayOf(Lang.t("menu_features"), Lang.t("menu_settings"), Lang.t("menu_safety"),
            Lang.t("menu_language"), Lang.t("menu_guide"))
        AlertDialog.Builder(this).setTitle(Lang.t("menu")).setItems(items) { _, i ->
            when (i) {
                0 -> speaker.say(caps.describeFa())
                1 -> startActivity(Intent(this, SettingsActivity::class.java))
                2 -> speaker.say(SAFETY)
                3 -> showLanguage()
                4 -> speaker.say(if (Lang.code == "en") GUIDE_EN else GUIDE_FA)
            }
        }.setNegativeButton(Lang.t("close"), null).show()
    }

    private fun showLanguage() {
        val labels = Lang.options.map { it.second }.toTypedArray()
        val current = Lang.options.indexOfFirst { it.first == Lang.code }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(Lang.t("pick_lang"))
            .setSingleChoiceItems(labels, current) { d, which ->
                val code = Lang.options[which].first
                getSharedPreferences("ui", MODE_PRIVATE).edit().putString("lang", code).apply()
                Lang.code = code
                d.dismiss()
                recreate()
            }.setNegativeButton(Lang.t("close"), null).show()
    }

    // ---------- کجا هستم ----------
    private fun whereAmI() {
        if (!settings.gpsEnabled) { speaker.say(tr("جی‌پی‌اس در این گوشی نیست یا در تنظیمات خاموش است.", "GPS is not available or is off.")); return }
        if (!ask(Manifest.permission.ACCESS_FINE_LOCATION, REQ_LOC)) return
        speaker.say(tr("در حال دریافت موقعیت. لطفاً صبر کنید. ممکن است تا نیم دقیقه طول بکشد.", "Getting your location. Please wait."))
        location.getOnce { l, msg ->
            if (l == null) { speaker.say(msg); return@getOnce }
            location.address(l) { addr ->
                speaker.say(
                    if (addr != null) tr("نزدیک $addr. ${LocationModule.accuracyFa(l)}", "Near $addr.")
                    else tr("نشانی پیدا نشد؛ شاید اینترنت وصل نیست. $msg", "Address not found. $msg")
                )
            }
        }
    }

    // ---------- مسیر به مقصد ----------
    private fun askDestination() {
        if (!settings.gpsEnabled) { speaker.say(tr("جی‌پی‌اس در دسترس نیست.", "GPS is not available.")); return }
        if (!ask(Manifest.permission.ACCESS_FINE_LOCATION, REQ_LOC)) return
        val et = EditText(this).apply { hint = tr("مثلاً: میدان آزادی", "e.g. Azadi Square") }
        AlertDialog.Builder(this).setTitle(tr("مقصد را بنویسید", "Enter destination")).setView(et)
            .setPositiveButton(tr("مسیر", "Route")) { _, _ -> startRoute(et.text.toString().trim()) }
            .setNegativeButton(Lang.t("close"), null).show()
    }

    private fun startRoute(name: String) {
        if (name.isEmpty()) return
        speaker.say(tr("در حال یافتن مسیر به $name. لطفاً صبر کنید.", "Finding route to $name."))
        location.getOnce { here, msg ->
            if (here == null) { speaker.say(msg); return@getOnce }
            Thread {
                var failMsg: String? = null
                val r = try {
                    val dest = RouteService.geocode(this, name)
                    if (dest == null) {
                        failMsg = if (!Capabilities.internetNow(this)) tr("اینترنت وصل نیست؛ برای پیدا کردن مقصد لازم است.", "No internet; needed to find the destination.")
                        else tr("مقصد پیدا نشد. نام را کامل‌تر بنویسید، مثلاً همراه با نام شهر.", "Destination not found. Write a fuller name with the city.")
                        null
                    } else RouteService.fetch(here, dest, name) ?: run {
                        failMsg = tr("مقصد پیدا شد ولی مسیر پیاده ساخته نشد. کمی بعد دوباره تلاش کنید.", "Destination found but no walking route.")
                        null
                    }
                } catch (e: Exception) {
                    AppLog.e("مسیریابی ناموفق", e)
                    failMsg = if (!Capabilities.internetNow(this)) tr("اینترنت وصل نیست.", "No internet.")
                    else tr("سرویس مسیریابی پاسخ نداد. کمی بعد دوباره تلاش کنید.", "Routing service did not respond.")
                    null
                }
                runOnUiThread {
                    if (r == null) { speaker.say(failMsg ?: tr("مسیری پیدا نشد.", "No route found.")); return@runOnUiThread }
                    route = r; stepIdx = 0; announcedIdx = -1
                    RouteService.save(this, r)
                    setRouteBtn()
                    speaker.say(tr("مسیر به ${r.destName}: حدود ${r.totalM} متر، ${r.steps.size} مرحله. ", "Route to ${r.destName}: about ${r.totalM} meters. ") +
                        (r.steps.firstOrNull()?.text ?: ""))
                    if (!location.isTracking) startTracking(announce = false)
                }
            }.start()
        }
    }

    private fun endRoute(say: Boolean) {
        route = null; RouteService.clear(this); setRouteBtn()
        if (say) speaker.say(tr("مسیر پایان یافت.", "Route ended."))
    }

    private fun setRouteBtn() {
        val t = if (route != null) Lang.t("route_stop") else Lang.t("route")
        routeBtn.text = t; routeBtn.contentDescription = t
    }

    /** بررسی مسیر در هر دریافت موقعیت: خروج از مسیر، اعلام مرحله بعد، رسیدن */
    private fun checkRoute(l: Location) {
        val r = route ?: return
        val d = RouteService.distanceToRoute(r, l.latitude, l.longitude)
        val now = System.currentTimeMillis()
        if (d > RouteService.OFF_ROUTE_M) {
            if (now - lastOffAlert > OFF_ALERT_MS) {
                lastOffAlert = now
                speaker.say(tr("از مسیر خارج شده‌اید. حدود ${d.toInt()} متر فاصله. برای برگشت، مسیر را دوباره بخواهید.",
                    "You are off route by about ${d.toInt()} meters."))
            }
            return
        }
        val s = r.steps.getOrNull(stepIdx)
        if (s == null) { speaker.say(tr("به مقصد رسیدید.", "You have arrived.")); endRoute(false); return }
        val dist = distTo(l, s.lat, s.lon)
        if (dist <= 25 && stepIdx != announcedIdx) {
            announcedIdx = stepIdx
            speaker.say("${s.text}. ${tr("تا این نقطه حدود ${dist.toInt()} متر", "About ${dist.toInt()} meters")}")
        }
        if (dist <= 12) stepIdx++
    }

    private fun checkObstacles(l: Location) {
        for (o in Obstacles.near(this, l.latitude, l.longitude, 20.0)) {
            if (!announcedObs.add(o.time)) continue
            speaker.say(tr("هشدار: مانع نزدیک. ${o.text}", "Warning: obstacle nearby. ${o.text}"))
        }
    }

    private fun distTo(l: Location, lat: Double, lon: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(l.latitude, l.longitude, lat, lon, r)
        return r[0]
    }

    // ---------- جهت‌یابی ----------
    private fun toggleTracking() { if (location.isTracking) stopTracking() else startTracking() }

    /** هر چند ثانیه موقعیت را می‌گیرد و فقط وقتی جهت حرکت عوض شد آن را اعلام می‌کند */
    private fun startTracking(announce: Boolean = true) {
        if (!settings.gpsEnabled) { speaker.say(tr("جی‌پی‌اس در این گوشی نیست یا در تنظیمات خاموش است.", "GPS is not available.")); return }
        if (!ask(Manifest.permission.ACCESS_FINE_LOCATION, REQ_LOC)) return
        trackAnchor = null; lastDirection = null; lastFixAt = System.currentTimeMillis()
        val sec = settings.trackIntervalSec
        val err = location.startTracking(sec * 1000L) { onTrackFix(it) }
        if (err != null) { speaker.say(err); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTrackBtn(true)
        if (announce) {
            var m = tr("جهت‌یابی شروع شد. هر $sec ثانیه بررسی می‌کنم. با احتیاط راه بروید؛ هر وقت جهت حرکت عوض شود می‌گویم.",
                "Direction tracking started. Checking every $sec seconds.")
            compass.azimuth?.takeIf { settings.compassEnabled }?.let { m += tr(" الان رو به ${CompassModule.directionFa(it)} هستید.", "") }
            speaker.say(m)
        }
        handler.postDelayed(noFixCheck, NO_FIX_MS)
    }

    private fun stopTracking(silent: Boolean = false) {
        if (!location.isTracking) return
        location.stopTracking()
        handler.removeCallbacks(noFixCheck)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTrackBtn(false)
        if (!silent) speaker.say(tr("جهت‌یابی متوقف شد.", "Direction tracking stopped."))
    }

    private fun onTrackFix(l: Location) {
        lastFixAt = System.currentTimeMillis()
        checkRoute(l)
        checkObstacles(l)
        val anchor = trackAnchor ?: run { trackAnchor = l; return }
        // جابجایی کمتر از خطای GPS را حرکت حساب نمی‌کنیم
        if (anchor.distanceTo(l) < maxOf(MIN_MOVE_M, l.accuracy)) return
        val br = CompassModule.bearingFromGps(anchor, l) ?: return
        trackAnchor = l
        val dir = CompassModule.directionFa(br)
        if (dir != lastDirection) { lastDirection = dir; speaker.say(tr("در حال حرکت به سمت $dir.", "Moving $dir.")) }
    }

    private val noFixCheck: Runnable = object : Runnable {
        override fun run() {
            if (!location.isTracking) return
            if (System.currentTimeMillis() - lastFixAt >= NO_FIX_MS) speaker.say(tr("سیگنال جی‌پی‌اس دریافت نمی‌شود. در فضای باز بهتر کار می‌کند.", "No GPS signal."))
            handler.postDelayed(this, NO_FIX_MS)
        }
    }

    private fun setTrackBtn(on: Boolean) {
        val t = if (on) Lang.t("stop_dir") else Lang.t("direction")
        trackBtn.text = t; trackBtn.contentDescription = t
    }

    // ---------- عکس، توصیف و ثبت مانع ----------
    private fun takePhoto(obstacle: Boolean) {
        if (!settings.cameraEnabled) { speaker.say(tr("دوربین در این گوشی نیست یا در تنظیمات خاموش است.", "Camera is not available.")); return }
        val mode = settings.processingMode
        if (!obstacle && mode != ProcessingMode.LOCAL && !settings.aiConfigured) {
            speaker.say(tr("هوش مصنوعی هنوز تنظیم نشده است. در تنظیمات، بخش هوش مصنوعی، کلید را وارد کنید. " +
                "یا محل پردازش را روی «روی گوشی» بگذارید که فقط نور محیط را می‌سنجد.", "AI is not configured. Add the key in Settings."))
            return
        }
        if (!obstacle && mode != ProcessingMode.LOCAL && !Capabilities.internetNow(this)) {
            speaker.say(if (mode == ProcessingMode.REMOTE) tr("اینترنت وصل نیست. توصیف محیط بدون اینترنت ممکن نیست.", "No internet.")
                        else tr("اینترنت وصل نیست. فقط تحلیل ساده نور انجام می‌شود.", "No internet. Only simple analysis."))
            if (mode == ProcessingMode.REMOTE) return
        }
        if (!ask(Manifest.permission.CAMERA, REQ_CAM)) return
        pendingObstacle = obstacle
        if (!CameraModule.open(this)) { pendingObstacle = false; speaker.say(tr("برنامه دوربین باز نشد.", "Camera app did not open.")) }
        else speaker.say(tr("دوربین باز شد. دکمه گرفتن عکس معمولاً پایین وسط صفحه است.", "Camera opened. Tap the shutter button."))
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != CameraModule.REQUEST) return
        val obstacle = pendingObstacle; pendingObstacle = false
        if (res != RESULT_OK) { speaker.say(tr("عکسی گرفته نشد.", "No photo taken.")); return }
        speaker.say(tr("در حال تحلیل تصویر. ممکن است چند ثانیه طول بکشد.", "Analyzing image."))
        Thread {
            val bmp = CameraModule.extract(this, data)
            if (bmp == null) { runOnUiThread { speaker.say(tr("عکس خوانده نشد.", "Photo could not be read.")) }; return@Thread }
            if (obstacle) { saveObstacle(bmp); return@Thread }
            router.analyze(bmp) { r, err ->
                runOnUiThread { speaker.say(r?.descriptionFa ?: err ?: tr("تحلیل ناموفق بود.", "Analysis failed.")) }
            }
        }.start()
    }

    /** مانع را با عکس و مکان ذخیره می‌کند و توصیف آن را (در صورت امکان) از هوش مصنوعی می‌گیرد */
    private fun saveObstacle(bmp: Bitmap) {
        runOnUiThread {
            if (!settings.gpsEnabled) { speaker.say(tr("بدون جی‌پی‌اس مکان مانع ثبت نمی‌شود.", "Cannot save without GPS.")); return@runOnUiThread }
            location.getOnce { l, msg ->
                if (l == null) { speaker.say(msg); return@getOnce }
                Thread {
                    val time = System.currentTimeMillis()
                    val file = java.io.File(Obstacles.dir(this), "$time.jpg")
                    try { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
                    catch (e: Exception) { AppLog.e("ذخیره عکس مانع", e) }
                    router.analyze(bmp) { r, _ ->
                        val text = r?.descriptionFa ?: tr("مانع ثبت شد", "Obstacle saved")
                        Obstacles.add(this, Obstacle(l.latitude, l.longitude, text, file.absolutePath, time))
                        runOnUiThread { speaker.say(tr("مانع ثبت شد: $text", "Obstacle saved: $text")) }
                    }
                }.start()
            }
        }
    }

    private fun ask(perm: String, code: Int): Boolean {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(perm), code); return false
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(code, p, g)
        if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) speaker.say(tr("اجازه داده شد. دوباره دکمه را بزنید.", "Permission granted. Press again."))
        else speaker.say(tr("بدون این اجازه، این بخش کار نمی‌کند.", "This feature needs permission."))
    }

    companion object {
        const val REQ_LOC = 1; const val REQ_CAM = 2
        const val MIN_MOVE_M = 8f
        const val NO_FIX_MS = 30_000L
        const val OFF_ALERT_MS = 20_000L
        const val SAFETY_SHORT = "این برنامه جایگزین عصای سفید نیست."
        const val SAFETY = "این برنامه فقط کمکی است و جایگزین عصای سفید، سگ راهنما یا آموزش جهت‌یابی نیست. " +
            "تشخیص دوربین ممکن است اشتباه یا با تأخیر باشد. موقعیت جی‌پی‌اس ممکن است چند ده متر خطا داشته باشد. " +
            "همیشه به حواس و وسایل ایمنی خود اعتماد کنید."
        const val GUIDE_FA = "راهنمای کاربر. کجا هستم: نشانی تقریبی را می‌گوید. جهت‌یابی: هنگام حرکت، جهت را هر چند ثانیه بررسی و اعلام می‌کند. " +
            "مسیر به مقصد: نام مقصد را بنویسید؛ مراحل را می‌گوید و اگر از مسیر خارج شوید هشدار می‌دهد. " +
            "ثبت مانع: از مانع عکس بگیرید تا با مکان ذخیره شود و هنگام نزدیک شدن دوباره اعلام شود. " +
            "توصیف عکس: برای توصیف، هوش مصنوعی باید در تنظیمات وارد شود."
        const val GUIDE_EN = "User guide. Where am I: tells approximate address. Direction: checks and announces heading while you walk. " +
            "Route: enter a destination; steps are spoken and off-route warnings are given. " +
            "Report obstacle: take a photo; it is saved with location and announced when you approach. " +
            "Describe photo: requires AI settings."
    }
}
