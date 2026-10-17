package ir.bna.assistant.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location

/**
 * جهت‌یابی. اگر قطب‌نما باشد از حسگر استفاده می‌کند؛
 * وگرنه راهکار جایگزین: جهت حرکت از دو موقعیت GPS هنگام راه رفتن.
 */
class CompassModule(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val grav = FloatArray(3); private val geo = FloatArray(3)
    private var hasG = false; private var hasM = false
    var azimuth: Float? = null; private set
    var lowAccuracy = false; private set

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val a = 0.15f
        val t = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) grav.also { hasG = true } else geo.also { hasM = true }
        for (i in 0..2) t[i] = t[i] + a * (e.values[i] - t[i])
        if (!hasG || !hasM) return
        val r = FloatArray(9); val o = FloatArray(3)
        if (SensorManager.getRotationMatrix(r, null, grav, geo)) {
            SensorManager.getOrientation(r, o)
            azimuth = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
        }
    }
    override fun onAccuracyChanged(s: Sensor, acc: Int) {
        if (s.type == Sensor.TYPE_MAGNETIC_FIELD) lowAccuracy = acc <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    companion object {
        fun directionFa(deg: Float): String {
            val names = listOf("شمال", "شمال شرقی", "شرق", "جنوب شرقی", "جنوب", "جنوب غربی", "غرب", "شمال غربی")
            return names[(((deg + 22.5f) % 360) / 45).toInt()]
        }
        /** جایگزین قطب‌نما: جهت بین دو نقطه GPS (حداقل ۱۰ متر فاصله) */
        fun bearingFromGps(a: Location, b: Location): Float? =
            if (a.distanceTo(b) < 10f) null else (a.bearingTo(b) + 360) % 360
    }
}
