package ir.bna.assistant.ui

import android.view.ViewGroup
import java.util.Locale
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
import ir.bna.assistant.route.Note
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
    private lateinit var bottomBar: LinearLayout
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
    private val announcedNotes = mutableSetOf<Note>()
    private var pendingRouteNote = false

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

        // ردیف بالا: دکمه منو در راست (چیدمان RTL: اولین عنصر راست است)، عنوان در چپ
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.button(this, Lang.t("menu")) { showMenu() }.apply {
            textSize = 16f; minHeight = 0; minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        top.addView(TextView(this).apply { text = Lang.t("title"); textSize = 20f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val col = Ui.column(this)
        status = Ui.status(this)
        col.addView(top)
        col.addView(status)
        // پایین صفحه: نوار دکمه‌ها، جدا از بخش پیام‌ها و همیشه دیده می‌شود
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            setPadding(8, 8, 8, 8)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        setRouteBtn()

        speaker = Speaker(this, { settings.speechOutput }) { t, a -> runOnUiThread { Ui.show(status, t, a) } }
        speaker.rate = settings.speechRate
        speaker.say(tr("دستیار مسیریابی آماده است. " + SAFETY_SHORT, "Navigation assistant is ready. This app does not replace a white cane."))
        AppLog.i("شروع برنامه. ${caps.describeFa()}")
    }

    override fun onResume() {
        super.onResume()
        val saved = getSharedPreferences("ui", MODE_PRIVATE).getString("lang", "fa") ?: "fa"
        if (saved != Lang.code) { Lang.code = saved; recreate(); return }
        if (::speaker.isInitialized) speaker.rate = settings.speechRate
        if (settings.compassEnabled) compass.start()
    }
    override fun onPause() { super.onPause(); compass.stop() }
    override fun onDestroy() { stopTracking(silent = true); speaker.shutdown(); super.onDestroy() }

    private fun tr(fa: String, en: String) = if (Lang.code == "en") en else fa

    // ---------- منو ----------
    private fun showMenu() {
        val items = arrayOf(Lang.t("menu_features"), Lang.t("menu_settings"), Lang.t("menu_safety"),
            Lang.t("menu_guide"))
        AlertDialog.Builder(this).setTitle(Lang.t("menu")).setItems(items) { _, i ->
            when (i) {
                0 -> speaker.say(caps.describeFa())
                1 -> startActivity(Intent(this, SettingsActivity::class.java))
                2 -> speaker.say(SAFETY)
                3 -> speaker.say(if (Lang.code == "en") GUIDE_EN else GUIDE_FA)
            }
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
                val base = if (addr != null) tr("نزدیک $addr. ${LocationModule.describe(l)}", "Near $addr. ${LocationModule.describe(l)}")
                    else tr("نشانی پیدا نشد؛ شاید اینترنت وصل نیست. $msg", "Address not found. $msg")
                speaker.say(base)
                Thread {
                    var old = false
                    val places = try { val r = ir.bna.assistant.route.Nearby.findCached(this, l.latitude, l.longitude); old = r.second; r.first } catch (e: Exception) { AppLog.e("مکان‌های اطراف", e); null }
                    runOnUiThread {
                        speaker.say(when {
                            places == null -> tr("اطلاعات مکان‌های اطراف دریافت نشد.", "Nearby places unavailable.")
                            places.isEmpty() -> tr("مکان نام‌داری در اطراف ثبت نشده است.", "No named places nearby.")
                            else -> (if (old) tr("سرور جواب نداد؛ اطلاعات ذخیره‌شدهٔ قبلی: ", "Server down; saved data: ") else tr("اطراف شما: ", "Nearby: ")) + places.joinToString("، ")
                        })
                    }
                }.start()
            }
        }
    }

    // ---------- مسیر به مقصد ----------
    private fun showRouteList() {
        if (!settings.gpsEnabled) { speaker.say(tr("جی‌پی‌اس در دسترس نیست.", "GPS is not available.")); return }
        val files = RouteService.saved(this)
        val routes = files.map { RouteService.loadFile(it) }
        val items = (listOf(tr("مسیر جدید", "New route")) + files.indices.map { routes[it]?.destName ?: files[it].name }).toTypedArray()
        AlertDialog.Builder(this).setTitle(tr("مسیرها", "Routes"))
            .setItems(items) { _, i ->
                if (i == 0) askDestination()
                else routes[i - 1]?.let { showRouteInfo(it, files[i - 1], false) }
            }
            .setNegativeButton(Lang.t("close"), null).show()
    }

    private fun routeText(r: Route): String {
        val sb = StringBuilder(tr("مسیر به ${r.destName}، حدود ${r.totalM} متر\n\n", "Route to ${r.destName}, ${r.totalM} m\n\n"))
        r.steps.forEachIndexed { i, s -> sb.append("${i + 1}. ${s.text} (${s.meters} ${tr("متر", "m")})\n") }
        return sb.toString()
    }

    private fun showRouteInfo(r: Route, file: java.io.File?, isNew: Boolean) {
        val tv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lines = routeText(r).split("\n").filter { it.isNotBlank() }
            lines.forEach { line ->
                addView(TextView(context).apply { text = line; textSize = 16f; setTextIsSelectable(true); setPadding(32, 18, 32, 18) })
                addView(android.view.View(context).apply {
                    setBackgroundColor(0xFF888888.toInt()); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(-1, 3)
                })
            }
        }
        // دکمه‌ها داخل خود صفحه و زیر فهرست هستند تا با طولانی بودن مراحل از صفحه بیرون نروند
        val dlg = AlertDialog.Builder(this).setTitle(r.destName).create()
        fun btn(label: String, onClick: () -> Unit) = android.widget.Button(this).apply {
            text = label; setOnClickListener { dlg.dismiss(); onClick() }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 6, 8, 6) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.75).toInt())
        }
        // نام مسیر قابل ویرایش است؛ خالی بماند، نام مقصد استفاده می‌شود
        val nameEt = android.widget.EditText(this).apply {
            setText(r.destName); hint = tr("نام مسیر", "Route name"); setSingleLine(true)
            contentDescription = tr("نام مسیر", "Route name")
        }
        fun named(): Route {
            val n = nameEt.text.toString().trim().ifEmpty { r.destName }
            return Route(n, r.points, r.steps, r.totalM, r.notes)
        }
        root.addView(nameEt, LinearLayout.LayoutParams(-1, -2).apply { setMargins(24, 6, 24, 6) })
        root.addView(ScrollView(this).apply { addView(tv) }, LinearLayout.LayoutParams(-1, 0, 1f))
        // یک ردیف پایین: شروع پیاده‌روی، ذخیره، حذف (اگر ذخیره‌شده است)، بستن
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        row.addView(btn(tr("شروع پیاده‌روی", "Start walking")) {
            val nr = named()
            route = nr; stepIdx = 0; announcedIdx = -1
            RouteService.save(this, nr); setRouteBtn()
            speaker.say(tr("شروع مسیر به ${nr.destName}. ", "Starting route. ") + (nr.steps.firstOrNull()?.text ?: ""))
            if (!location.isTracking) startTracking(announce = false)
        })
        row.addView(btn(tr("ذخیره", "Save")) {
            val nr = named()
            // تغییر نام = حذف فایل قبلی و ذخیره با نام جدید
            if (file != null && nr.destName != r.destName) RouteService.deleteSaved(file)
            RouteService.saveToLibrary(this, nr)
            speaker.say(tr("مسیر ذخیره شد.", "Route saved."))
        })
        if (file != null) row.addView(btn(tr("حذف", "Delete")) { RouteService.deleteSaved(file); speaker.say(tr("مسیر حذف شد.", "Route deleted.")) })
        row.addView(btn(Lang.t("close")) {})
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        dlg.setView(root)
        dlg.show()
        speaker.say(tr("مسیر ${if (isNew) "ساخته شد" else "ذخیره‌شده"}: ${r.destName}، حدود ${r.totalM} متر، ${r.steps.size} مرحله. جزئیات در صفحه است. برای رفتن، شروع پیاده‌روی را بزنید؛ برای نگه‌داشتن، ذخیره مسیر را بزنید.",
            "Route to ${r.destName}: ${r.totalM} m. Details on screen."))
    }

    private fun askDestination() {
        if (!settings.gpsEnabled) { speaker.say(tr("جی‌پی‌اس در دسترس نیست.", "GPS is not available.")); return }
        if (!ask(Manifest.permission.ACCESS_FINE_LOCATION, REQ_LOC)) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        val org = EditText(this).apply {
            hint = tr("مبدا: مختصات مثل 35.7,51.4 یا نام مکان؛ خالی یعنی مکان فعلی", "Origin: 35.7,51.4 or a place; empty = current location")
        }
        val dlg = AlertDialog.Builder(this).setTitle(tr("مسیر جدید", "New route")).create()
        val et = EditText(this).apply { hint = tr("مقصد، مثلاً: میدان آزادی", "Destination, e.g. Azadi Square") }
        val label = EditText(this).apply { hint = tr("نام مسیر (اختیاری؛ خالی یعنی نام مقصد)", "Route name (optional; empty = destination)") }
        box.addView(org); box.addView(et); box.addView(label)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        fun rb(t: String, f: () -> Unit) = android.widget.Button(this).apply {
            text = t; setOnClickListener { dlg.dismiss(); f() }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 6, 8, 6) }
        }
        row.addView(rb(tr("مسیر", "Route")) {
            val dest = et.text.toString().trim()
            startRoute(dest, org.text.toString().trim().ifEmpty { null }, label.text.toString().trim().ifEmpty { dest })
        })
        row.addView(rb(Lang.t("close")) {})
        box.addView(row)
        dlg.setView(box); dlg.show()
    }

    private fun parseCoords(t: String): Pt? {
        val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,،\s]\s*(-?\d+(?:\.\d+)?)\s*$""").find(t.map { if (it in '۰'..'۹') '0' + (it - '۰') else it }.joinToString("")) ?: return null
        val la = m.groupValues[1].toDouble(); val lo = m.groupValues[2].toDouble()
        return if (la in -90.0..90.0 && lo in -180.0..180.0) Pt(la, lo) else null
    }

    private fun startRoute(name: String, originText: String?, label: String = name) {
        if (name.isEmpty()) return
        speaker.say(tr("در حال یافتن مسیر به $name. لطفاً صبر کنید.", "Finding route to $name."))
        if (originText == null) {
            location.getOnce { here, msg ->
                if (here == null) { speaker.say(msg); return@getOnce }
                buildRoute(Pt(here.latitude, here.longitude), name, null, label)
            }
        } else buildRoute(null, name, originText, label)
    }

    private fun buildRoute(here0: Pt?, name: String, originText: String? = null, label: String = name) {
            Thread {
                var failMsg: String? = null
                val r = try {
                    val here = here0 ?: parseCoords(originText ?: "") ?: originText?.let { RouteService.geocode(this, it) }
                    if (here == null) throw IllegalStateException(tr("مبدا نامعتبر است.", "Invalid origin."))
                    val dest = RouteService.geocode(this, name)
                    if (dest == null) {
                        failMsg = if (!Capabilities.internetNow(this)) tr("اینترنت وصل نیست؛ برای پیدا کردن مقصد لازم است.", "No internet; needed to find the destination.")
                        else tr("مقصد پیدا نشد. نام را کامل‌تر بنویسید، مثلاً همراه با نام شهر.", "Destination not found. Write a fuller name with the city.")
                        null
                    } else RouteService.fetch(here, dest, label) ?: run {
                        failMsg = tr("مقصد پیدا شد ولی مسیر پیاده ساخته نشد. کمی بعد دوباره تلاش کنید.", "Destination found but no walking route.")
                        null
                    }
                } catch (e: Exception) {
                    AppLog.e("مسیریابی ناموفق", e)
                    failMsg = if (!Capabilities.internetNow(this)) tr("اینترنت وصل نیست.", "No internet.")
                    else tr("سرویس مسیریابی پاسخ نداد. کد خطا: ${e.javaClass.simpleName} ${e.message?.take(60) ?: ""}", "Routing service failed: ${e.javaClass.simpleName}")
                    null
                }
                runOnUiThread {
                    if (r == null) { speaker.say(failMsg ?: tr("مسیری پیدا نشد.", "No route found.")); return@runOnUiThread }
                    showRouteInfo(r, null, true)
                }
            }.start()
    }

    private fun addNote() {
        val r = route
        if (r == null) { speaker.say(tr("ابتدا یک مسیر را شروع کنید.", "Start a route first.")); return }
        val et = EditText(this).apply { hint = tr("متن یادداشت", "Note text") }
        AlertDialog.Builder(this).setTitle(tr("یادداشت در این مکان", "Note at this place")).setView(et)
            .setPositiveButton(tr("ذخیره", "Save")) { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                location.getOnce { l, msg ->
                    if (l == null) { speaker.say(msg); return@getOnce }
                    val n = Note(t, l.latitude, l.longitude)
                    r.notes.add(n); announcedNotes.add(n)
                    RouteService.save(this, r)
                    speaker.say(tr("یادداشت ذخیره شد.", "Note saved."))
                }
            }.setNegativeButton(Lang.t("close"), null).show()
    }

    private fun endRoute(say: Boolean) {
        route = null; RouteService.clear(this); setRouteBtn()
        if (say) speaker.say(tr("مسیر پایان یافت.", "Route ended."))
    }

    /** نوار پایین را بر اساس حالت (عادی یا پیاده‌روی) می‌سازد */
    private fun setRouteBtn() {
        bottomBar.removeAllViews()
        fun b(t: String, f: () -> Unit) = Button(this).apply {
            text = t; contentDescription = t; textSize = 15f; minHeight = 0; minimumHeight = 0
            setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 6, 4, 6) }
        }
        fun row(vararg bs: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            bs.forEach { addView(it) }
        }
        trackBtn = b(if (location.isTracking) Lang.t("stop_dir") else Lang.t("direction")) { toggleTracking() }
        if (route == null) {
            routeBtn = b(Lang.t("route")) { showRouteList() }
            bottomBar.addView(row(b(Lang.t("where")) { whereAmI() }, trackBtn, routeBtn, b(Lang.t("photo")) { takePhoto() }))
        } else {
            // ردیف بالای نوار: یادداشت‌ها و عکس‌ها در موقعیت فعلی
            bottomBar.addView(row(
                b(tr("افزودن متن", "Add text")) { addNote() },
                b(tr("ثبت با عکس", "Photo note")) { takePhoto(forRoute = true) },
                b(tr("ذخیره", "Save")) { saveRouteNotes() }))
            routeBtn = b(Lang.t("route_stop")) { endRoute(true) }
            bottomBar.addView(row(b(Lang.t("where")) { whereAmI() }, trackBtn, routeBtn,
                b(Lang.t("close")) { moveTaskToBack(true) }))
        }
    }

    /** ذخیره‌ی مسیر جاری همراه یادداشت‌ها و عکس‌ها */
    private fun saveRouteNotes() {
        val r = route ?: return
        RouteService.save(this, r)
        speaker.say(tr("مسیر و یادداشت‌ها ذخیره شد.", "Route and notes saved."))
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
        for (n in r.notes) if (distTo(l, n.lat, n.lon) <= 20 && announcedNotes.add(n))
            speaker.say(tr("یادداشت شما: ${n.text}", "Your note: ${n.text}"))
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
    private fun takePhoto(forRoute: Boolean = false) {
        if (forRoute && route == null) { speaker.say(tr("ابتدا یک مسیر را شروع کنید.", "Start a route first.")); return }
        if (!settings.cameraEnabled) { speaker.say(tr("دوربین در این گوشی نیست یا در تنظیمات خاموش است.", "Camera is not available.")); return }
        val mode = settings.processingMode
        if (mode != ProcessingMode.LOCAL && !settings.aiConfigured) {
            speaker.say(tr("هوش مصنوعی هنوز تنظیم نشده است. در تنظیمات، بخش هوش مصنوعی، کلید را وارد کنید. " +
                "یا محل پردازش را روی «روی گوشی» بگذارید که فقط نور محیط را می‌سنجد.", "AI is not configured. Add the key in Settings."))
            return
        }
        if (mode != ProcessingMode.LOCAL && !Capabilities.internetNow(this)) {
            speaker.say(if (mode == ProcessingMode.REMOTE) tr("اینترنت وصل نیست. توصیف محیط بدون اینترنت ممکن نیست.", "No internet.")
                        else tr("اینترنت وصل نیست. فقط تحلیل ساده نور انجام می‌شود.", "No internet. Only simple analysis."))
            if (mode == ProcessingMode.REMOTE) return
        }
        if (!ask(Manifest.permission.CAMERA, REQ_CAM)) return
        pendingRouteNote = forRoute
        if (!CameraModule.open(this)) { pendingRouteNote = false; speaker.say(tr("برنامه دوربین باز نشد.", "Camera app did not open.")) }
        else speaker.say(tr("دوربین باز شد. دکمه گرفتن عکس معمولاً پایین وسط صفحه است.", "Camera opened. Tap the shutter button."))
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != CameraModule.REQUEST) return
        val forRoute = pendingRouteNote; pendingRouteNote = false
        if (res != RESULT_OK) { speaker.say(tr("عکسی گرفته نشد.", "No photo taken.")); return }
        speaker.say(tr("در حال تحلیل تصویر. ممکن است چند ثانیه طول بکشد.", "Analyzing image."))
        Thread {
            val bmp = CameraModule.extract(this, data)
            if (bmp == null) { runOnUiThread { speaker.say(tr("عکس خوانده نشد.", "Photo could not be read.")) }; return@Thread }
            if (forRoute) { addPhotoNote(bmp); return@Thread }
            router.analyze(bmp) { r, err ->
                runOnUiThread { speaker.say(r?.descriptionFa ?: err ?: tr("تحلیل ناموفق بود.", "Analysis failed.")) }
            }
        }.start()
    }

    /** توصیف عکس را به‌عنوان یادداشت در مکان فعلی به مسیر پیاده اضافه می‌کند */
    private fun addPhotoNote(bmp: Bitmap) {
        runOnUiThread {
            val r = route ?: return@runOnUiThread
            location.getOnce { l, msg ->
                if (l == null) { speaker.say(msg); return@getOnce }
                Thread {
                    router.analyze(bmp) { res, err ->
                        val text = res?.descriptionFa ?: err ?: tr("توصیف عکس انجام نشد.", "Photo description failed.")
                        runOnUiThread {
                            val n = Note(text, l.latitude, l.longitude)
                            r.notes.add(n); announcedNotes.add(n)
                            RouteService.save(this, r)
                            speaker.say(tr("توصیف عکس به مسیر اضافه شد: $text", "Photo description added to route: $text"))
                        }
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
            "ثبت با عکس: هنگام پیاده‌روی از اطراف عکس بگیرید تا توصیف آن به‌عنوان یادداشت در مسیر ذخیره شود. " +
            "توصیف عکس: برای توصیف، هوش مصنوعی باید در تنظیمات وارد شود."
        const val GUIDE_EN = "User guide. Where am I: tells approximate address. Direction: checks and announces heading while you walk. " +
            "Route: enter a destination; steps are spoken and off-route warnings are given. " +
            "Photo note: while walking, take a photo; its description is saved as a route note. " +
            "Describe photo: requires AI settings."
    }
}
