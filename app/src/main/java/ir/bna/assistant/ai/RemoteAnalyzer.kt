package ir.bna.assistant.ai

import android.graphics.Bitmap
import android.util.Base64
import ir.bna.assistant.core.AppLog
import ir.bna.assistant.core.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
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
        try {
            val jpg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            val b64 = Base64.encodeToString(jpg, Base64.NO_WRAP)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", PROMPT))
                .put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            val body = JSONObject()
                .put("model", settings.aiModel)
                .put("max_tokens", 300)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

            val c = URL(settings.aiUrl).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer ${settings.aiKey}")
            c.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                AppLog.e("پاسخ هوش مصنوعی: کد $code ${text.take(300)}")
                cb(null, errorFa(code)); return
            }
            val reply = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            cb(SceneResult(reply, name, 0.7f), null)
        } catch (e: java.net.SocketTimeoutException) {
            AppLog.e("مهلت پاسخ هوش مصنوعی تمام شد", e); cb(null, "پاسخ هوش مصنوعی خیلی طول کشید. دوباره امتحان کنید.")
        } catch (e: java.io.IOException) {
            AppLog.e("خطای اتصال هوش مصنوعی", e); cb(null, "اتصال به هوش مصنوعی برقرار نشد. اینترنت یا نشانی سرویس را بررسی کنید.")
        } catch (e: Exception) {
            AppLog.e("خطای هوش مصنوعی", e); cb(null, "پاسخ هوش مصنوعی قابل خواندن نبود. نام مدل را در تنظیمات بررسی کنید.")
        }
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
        const val PROMPT = "تو دستیار یک فرد نابینا هستی که با گوشی از محیط عکس گرفته است. " +
            "به زبان فارسی ساده و کوتاه، حداکثر چهار جمله، توضیح بده: اول هر خطر یا مانع احتمالی جلوی پا " +
            "(پله، چاله، جدول، ماشین، دیوار، تیر)، بعد کلیات محیط و نوشته‌های مهم. " +
            "جهت‌ها را با جلو، چپ و راست بگو. اگر عکس تار یا تاریک است، همین را بگو. حدس را قطعی بیان نکن."
    }
}
