package ir.bna.assistant.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import ir.bna.assistant.core.AppLog
import java.util.Locale

/** دریافت موقعیت با GPS؛ دقت همیشه اعلام می‌شود چون GPS خطا دارد. */
class LocationModule(private val ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    var lastLocation: Location? = null; private set
    private var tracker: LocationListener? = null
    val isTracking get() = tracker != null

    fun isGpsOn() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun getOnce(timeoutMs: Long = 30_000, cb: (Location?, String) -> Unit) {
        if (!isGpsOn()) { cb(null, GPS_OFF); return }
        var done = false
        val listener = listener { l ->
            if (done) return@listener
            done = true; lastLocation = l
            cb(l, describe(l))
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) { AppLog.e("خطای GPS", e); cb(null, "خطا در دریافت موقعیت."); return }
        handler.postDelayed({
            if (done) return@postDelayed
            done = true; lm.removeUpdates(listener)
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) { lastLocation = last; cb(last, "سیگنال تازه نیامد. آخرین موقعیت قبلی: " + describe(last)) }
            else cb(null, "موقعیت پیدا نشد. در فضای باز دوباره امتحان کنید.")
        }, timeoutMs)
    }

    /** دریافت پیوسته موقعیت با فاصله زمانی مشخص، تا stopTracking */
    @SuppressLint("MissingPermission")
    fun startTracking(intervalMs: Long, onFix: (Location) -> Unit): String? {
        if (!isGpsOn()) return GPS_OFF
        stopTracking()
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { lastLocation = loc; onFix(loc) }
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, l, Looper.getMainLooper())
            tracker = l; null
        } catch (e: Exception) { AppLog.e("خطای GPS", e); "خطا در شروع ردیابی موقعیت." }
    }

    fun stopTracking() { tracker?.let { lm.removeUpdates(it) }; tracker = null }

    /**
     * تبدیل مختصات به نشانی (خیابان، محله، شهر) با سرویس نشانی‌یاب گوشی (در سامسونگ: گوگل).
     * نیاز به اینترنت دارد؛ در نخ جداگانه اجرا می‌شود. در صورت شکست، null.
     */
    fun address(l: Location, cb: (String?) -> Unit) {
        if (!Geocoder.isPresent()) { cb(null); return }
        Thread {
            val text = try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale("fa", "IR")).getFromLocation(l.latitude, l.longitude, 1)
                    ?.firstOrNull()?.let { addressFa(it) }
            } catch (e: Exception) { AppLog.e("نشانی‌یابی ناموفق", e); null }
            handler.post { cb(text) }
        }.start()
    }

    private fun addressFa(a: Address): String? {
        val parts = listOfNotNull(a.thoroughfare, a.subLocality, a.locality ?: a.subAdminArea)
            .distinct().filter { it.isNotBlank() }
        return when {
            parts.isNotEmpty() -> parts.joinToString("، ")
            else -> a.getAddressLine(0)
        }
    }

    private fun listener(onFix: (Location) -> Unit) = object : LocationListener {
        override fun onLocationChanged(l: Location) { lm.removeUpdates(this); onFix(l) }
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    companion object {
        const val GPS_OFF = "مکان‌یاب گوشی خاموش است. لطفاً از تنظیمات سریع آن را روشن کنید."
        fun accuracyFa(l: Location) = "دقت حدود ${l.accuracy.toInt()} متر."
        fun describe(l: Location) =
            "عرض ${"%.5f".format(l.latitude)}، طول ${"%.5f".format(l.longitude)}. ${accuracyFa(l)}"
    }
}
