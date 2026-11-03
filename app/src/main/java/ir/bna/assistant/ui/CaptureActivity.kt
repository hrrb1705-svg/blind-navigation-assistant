package ir.bna.assistant.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import ir.bna.assistant.ai.SceneResult
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.Lang
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** پل بین صفحه دوربین و MainActivity */
object CaptureBridge {
    var analyze: ((Bitmap, (SceneResult?, String?) -> Unit) -> Unit)? = null
    var onRoutePhoto: ((String) -> Unit)? = null
    var say: ((String) -> Unit)? = null
    var frameIntervalSec: () -> Int = { 2 }
    /** true یعنی عکس برای یادداشت مسیر است */
    var forRoute = false

    fun clear() { analyze = null; onRoutePhoto = null; say = null }
}

/**
 * دوربین داخل برنامه. حالت «عکس» (پیش‌فرض) یا «فیلم».
 * فیلم ذخیره نمی‌شود؛ فقط فریم‌ها با فاصله تنظیمی تحلیل می‌شوند.
 * در حالت فیلم: «شروع/وقفه/ادامه» یک دکمه است و «پایان» جداگانه.
 */
class CaptureActivity : ComponentActivity() {
    private enum class VideoState { IDLE, RECORDING, PAUSED }

    private lateinit var exec: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var shotBtn: Button
    private lateinit var videoBtn: Button
    private lateinit var endBtn: Button
    private lateinit var modeGroup: RadioGroup

    private var capture: ImageCapture? = null
    private var state = VideoState.IDLE
    private var videoMode = false
    @Volatile private var recording = false
    @Volatile private var busy = false
    private var lastSent = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exec = Executors.newSingleThreadExecutor()
        buildUi()
        startCamera()
    }

    private fun tr(fa: String, en: String) = if (Lang.code == "en") en else fa
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        previewView = PreviewView(this)
        status = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12))
            text = tr("در حال باز کردن دوربین…", "Opening camera…")
        }

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            val photo = radio(tr("عکس", "Photo"), "photo_mode")
            val video = radio(tr("فیلم", "Video"), "video_mode")
            addView(photo); addView(video)
            check(photo.id)
            setOnCheckedChangeListener { _, id -> setMode(id != photo.id) }
        }

        shotBtn = btn(tr("گرفتن عکس", "Take photo")) { takePhoto() }
        videoBtn = btn(tr("شروع فیلم", "Start video")) { onVideoButton() }
        endBtn = btn(tr("پایان", "End")) { endVideo() }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(shotBtn); addView(videoBtn); addView(endBtn)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(modeGroup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        setMode(false)
    }

    private fun radio(text: String, id: String) = RadioButton(this).apply {
        this.text = text; contentDescription = text; minHeight = dp(56); setPadding(dp(12), 0, dp(12), 0)
        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
        tag = id
        View.generateViewId().let { this.id = it }
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; contentDescription = text; minHeight = dp(72)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { onClick() }
    }

    /** تغییر حالت عکس/فیلم؛ اگر فیلم در حال ضبط بود، پایان می‌یابد */
    private fun setMode(video: Boolean) {
        if (video == videoMode && modeGroup.checkedRadioButtonId != -1) { applyModeViews(); return }
        if (state != VideoState.IDLE) endVideo(silent = true)
        videoMode = video
        applyModeViews()
        say(tr(if (video) "حالت فیلم. برای شروع، دکمه شروع فیلم را بزنید."
               else "حالت عکس. برای گرفتن عکس، دکمه گرفتن عکس را بزنید.",
               if (video) "Video mode. Tap Start video." else "Photo mode. Tap Take photo."))
        focus(if (video) videoBtn else shotBtn)
    }

    private fun applyModeViews() {
        shotBtn.visibility = if (videoMode) View.GONE else View.VISIBLE
        videoBtn.visibility = if (videoMode) View.VISIBLE else View.GONE
        endBtn.visibility = if (videoMode && state != VideoState.IDLE) View.VISIBLE else View.GONE
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bind(future.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun bind(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        capture = ImageCapture.Builder().setTargetResolution(Size(1280, 960)).build()
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(exec) { img -> onFrame(img) } }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            say(tr("دوربین آماده است.", "Camera ready."))
        } catch (e: Exception) {
            AppLog.e("باز شدن دوربین داخلی ناموفق بود", e)
            say(tr("دوربین باز نشد. دسترسی دوربین را بررسی کنید.", "Camera failed to open."))
            finish()
        }
    }

    // ---------- عکس ----------
    private fun takePhoto() {
        val c = capture ?: return
        c.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = toBitmap(image)
                image.close()
                if (bmp == null) { say(tr("عکس خوانده نشد.", "Photo could not be read.")); return }
                say(tr("عکس گرفته شد.", "Photo taken."))
                analyzeAndSay(bmp)
            }
            override fun onError(e: ImageCaptureException) {
                AppLog.e("گرفتن عکس ناموفق بود", e)
                say(tr("عکس گرفته نشد.", "Photo failed."))
            }
        })
    }

    // ---------- فیلم (فقط تحلیل فریم‌ها) ----------
    private fun onVideoButton() {
        when (state) {
            VideoState.IDLE, VideoState.PAUSED -> resumeVideo()
            VideoState.RECORDING -> pauseVideo()
        }
    }

    private fun resumeVideo() {
        val first = state == VideoState.IDLE
        state = VideoState.RECORDING; recording = true; lastSent = 0L
        setVideoLabel(tr("وقفه", "Pause"))
        applyModeViews()
        say(if (first) tr("ضبط شروع شد. برای وقفه، دکمه وقفه را بزنید.", "Recording started. Tap Pause to pause.")
            else tr("ادامه داده شد.", "Resumed."))
        focus(videoBtn)
    }

    private fun pauseVideo() {
        state = VideoState.PAUSED; recording = false
        setVideoLabel(tr("ادامه", "Resume"))
        say(tr("وقفه شد. برای ادامه، دکمه ادامه را بزنید یا پایان را بزنید.", "Paused. Tap Resume or End."))
        focus(videoBtn)
    }

    private fun endVideo(silent: Boolean = false) {
        state = VideoState.IDLE; recording = false
        setVideoLabel(tr("شروع فیلم", "Start video"))
        applyModeViews()
        if (!silent) {
            say(tr("ضبط پایان یافت.", "Recording ended."))
            focus(videoBtn)
        }
    }

    private fun setVideoLabel(t: String) { videoBtn.text = t; videoBtn.contentDescription = t }

    private fun onFrame(img: ImageProxy) {
        val now = System.currentTimeMillis()
        val gap = CaptureBridge.frameIntervalSec() * 1000L
        // فاصله صفر یعنی پیوسته؛ تا جواب فریم قبلی نیامده، فریم تازه نمی‌فرستیم
        val skip = !recording || busy || (gap > 0 && now - lastSent < gap)
        if (skip) { img.close(); return }
        busy = true; lastSent = now
        val bmp = toBitmap(img)
        img.close()
        val a = CaptureBridge.analyze
        if (bmp == null || a == null) { busy = false; return }
        a(bmp) { r, err ->
            busy = false
            if (recording) say(r?.descriptionFa ?: err ?: tr("تحلیل فریم ناموفق بود.", "Frame analysis failed."))
        }
    }

    // ---------- کمک‌ها ----------
    private fun analyzeAndSay(bmp: Bitmap) {
        val a = CaptureBridge.analyze ?: return
        say(tr("در حال تحلیل تصویر…", "Analyzing…"))
        a(bmp) { r, err ->
            val text = r?.descriptionFa
            if (text == null || !CaptureBridge.forRoute) {
                say(text ?: err ?: tr("تحلیل ناموفق بود.", "Analysis failed."))
            } else runOnUiThread {
                say(text)
                android.app.AlertDialog.Builder(this).setTitle(tr("ذخیره در این مکان؟", "Save at this place?"))
                    .setMessage(text)
                    .setPositiveButton(tr("ذخیره", "Save")) { _, _ -> CaptureBridge.onRoutePhoto?.invoke(text) }
                    .setNegativeButton(tr("لغو", "Cancel"), null).show()
            }
        }
    }

    /** ImageProxy را به Bitmap با چرخش درست تبدیل می‌کند */
    private fun toBitmap(image: ImageProxy): Bitmap? = try {
        val b = image.toBitmap()
        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) b
        else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    } catch (e: Exception) {
        AppLog.e("تبدیل تصویر دوربین ناموفق بود", e); null
    }

    /** نمایش متن و گفتار؛ همیشه روی رشته اصلی */
    private fun say(text: String) = runOnUiThread {
        status.text = text
        CaptureBridge.say?.invoke(text)
    }

    /** فوکوس دسترس‌پذیری را روی دکمه می‌گذارد */
    private fun focus(v: View) = v.post {
        v.requestFocus()
        v.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    override fun onDestroy() {
        recording = false
        exec.shutdownNow()
        CaptureBridge.clear()
        super.onDestroy()
    }
}
