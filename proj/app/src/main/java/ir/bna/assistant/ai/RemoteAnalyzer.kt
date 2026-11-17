package ir.bna.assistant.ai

import android.graphics.Bitmap
import android.util.Base64
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * ارسال مستقیم عکس به هوش مصنوعی با رابط سازگار با OpenAI (chat/completions)
 * و دریافت توصیف فارسی. نشانی، مدل و کلید از تنظیمات خوانده می‌شود.
 * ویدیوی کوتاه در مرحله بعد: چند فریم از ویدیو به همین روش فرستاده می‌شود.
 */
class RemoteAnalyzer(private val settings: Settings, private val hasInternet: () -> Boolean) : SceneAnalyzer {
    override val name = "هوش مصنوعی اینترنتی"
    override fun isAvailable() = settings.aiConfigured && hasInternet()

    override fun analyze(image: Bitmap, cb: (SceneResult?, String?) -> Unit) {
        val payload: ByteArray
        try {
            val small = shrink(image)
            val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            val b64 = Base64.encodeToString(jpg, Base64.NO_WRAP)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", PROMPT))
                .put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            val body = JSONObject()
                .put("model", settings.aiModel)
                .put("max_tokens", 300)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            payload = body.toString().toByteArray()
        } catch (e: Exception) {
            AppLog.e("آماده‌سازی عکس برای هوش مصنوعی ناموفق بود", e)
            cb(null, "عکس برای ارسال آماده نشد."); return
        }

        // «unexpected end of stream» معمولاً قطع شدن پاسخ وسط راه است؛ یک بار دیگر امتحان می‌کنیم
        var lastError: IOException? = null
        for (attempt in 1..2) {
            try {
                val (code, text) = post(payload)
                if (code !in 200..299) {
                    AppLog.e("پاسخ هوش مصنوعی: کد $code ${text.take(300)}")
                    cb(null, errorFa(code)); return
                }
                val reply = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                cb(SceneResult(reply, name, 0.7f), null); return
            } catch (e: SocketTimeoutException) {
                AppLog.e("مهلت پاسخ هوش مصنوعی تمام شد", e)
                cb(null, "پاسخ هوش مصنوعی خیلی طول کشید. دوباره امتحان کنید."); return
            } catch (e: IOException) {
                AppLog.e("خطای اتصال هوش مصنوعی (تلاش $attempt)", e)
                lastError = e
            } catch (e: Exception) {
                AppLog.e("خطای هوش مصنوعی", e)
                cb(null, "پاسخ هوش مصنوعی قابل خواندن نبود. نام مدل را در تنظیمات بررسی کنید."); return
            }
        }
        AppLog.e("اتصال هوش مصنوعی پس از دو تلاش برقرار نشد", lastError)
        cb(null, "پاسخ سرویس هوش مصنوعی کامل نرسید. اینترنت را بررسی کنید و دوباره امتحان کنید.")
    }

    private fun post(payload: ByteArray): Pair<Int, String> {
        val c = URL(settings.aiUrl).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Authorization", "Bearer ${settings.aiKey}")
            c.setFixedLengthStreamingMode(payload.size)
            c.outputStream.use { it.write(payload) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** عکس بزرگ را کوچک می‌کند تا حجم درخواست و خطر قطع شدن کم شود */
    private fun shrink(src: Bitmap): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= MAX_SIDE) return src
        val k = MAX_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
    }

    private fun errorFa(code: Int) = when (code) {
        401, 403 -> "کلید هوش مصنوعی نادرست است یا اعتبار ندارد."
        402 -> "اعتبار حساب هوش مصنوعی تمام شده است."
        404 -> "نشانی سرویس یا نام مدل نادرست است."
        429 -> "درخواست‌ها زیاد بوده است. کمی بعد دوباره امتحان کنید."
        in 500..599 -> "سرور هوش مصنوعی فعلاً مشکل دارد. کمی بعد امتحان کنید."
        else -> "هوش مصنوعی خطا داد. کد $code."
    }

    companion object {
        const val MAX_SIDE = 800
        const val PROMPT = "تو دستیار یک فرد نابینا هستی که با گوشی از محیط عکس گرفته است. " +
            "به زبان فارسی ساده و کوتاه، حداکثر چهار جمله، توضیح بده: اول هر خطر یا مانع احتمالی جلوی پا " +
            "(پله، چاله، جدول، ماشین، دیوار، تیر)، بعد کلیات محیط و نوشته‌های مهم. " +
            "جهت‌ها را با جلو، چپ و راست بگو. اگر عکس تار یا تاریک است، همین را بگو. حدس را قطعی بیان نکن."
    }
}
