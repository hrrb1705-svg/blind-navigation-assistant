package ir.bna.assistant.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import ir.bna.assistant.core.AppLog

/** دریافت یک‌باره موقعیت با GPS؛ دقت همیشه اعلام می‌شود چون GPS خطا دارد. */
class LocationModule(ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    var lastLocation: Location? = null; private set

    fun isGpsOn() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun getOnce(timeoutMs: Long = 30_000, cb: (Location?, String) -> Unit) {
        if (!isGpsOn()) { cb(null, "مکان‌یاب گوشی خاموش است. لطفاً از تنظیمات سریع آن را روشن کنید."); return }
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (done) return; done = true
                lm.removeUpdates(this); lastLocation = l
                cb(l, describe(l))
            }
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
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

    private fun describe(l: Location) =
        "عرض ${"%.5f".format(l.latitude)}، طول ${"%.5f".format(l.longitude)}. دقت حدود ${l.accuracy.toInt()} متر."
}
