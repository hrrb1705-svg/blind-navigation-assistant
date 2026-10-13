package ir.bna.assistant.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** قابلیت‌های واقعی گوشی که در زمان اجرا بررسی می‌شوند */
data class Capabilities(
    val ramMb: Long,
    val cpuCores: Int,
    val isLowRamDevice: Boolean,
    val hasCamera: Boolean,
    val hasGps: Boolean,
    val hasMagnetometer: Boolean,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasInternet: Boolean,
    val isMeteredNetwork: Boolean
) {
    /** دسته‌بندی توان گوشی؛ A12 با ۴ گیگ در رده LITE قرار می‌گیرد */
    val tier: Tier get() = when {
        ramMb >= 7000 && cpuCores >= 8 -> Tier.HIGH
        ramMb >= 5000 -> Tier.MID
        else -> Tier.LITE
    }
    val hasCompass: Boolean get() = hasMagnetometer && hasAccelerometer

    fun describeFa(): String = buildString {
        append("حافظه: ${ramMb} مگابایت. هسته‌های پردازنده: $cpuCores. ")
        append("رده گوشی: ${tier.fa}. ")
        append("دوربین: ${yn(hasCamera)}. ")
        append("جی‌پی‌اس: ${yn(hasGps)}. ")
        append("قطب‌نما: ${yn(hasCompass)}. ")
        append("ژیروسکوپ: ${yn(hasGyroscope)}. ")
        append("اینترنت: ${yn(hasInternet)}")
        if (hasInternet && isMeteredNetwork) append("، از نوع حجمی")
        append(".")
    }
    private fun yn(b: Boolean) = if (b) "دارد" else "ندارد"

    companion object {
        fun detect(ctx: Context): Capabilities {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val pm = ctx.packageManager
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            return Capabilities(
                ramMb = mi.totalMem / (1024 * 1024),
                cpuCores = Runtime.getRuntime().availableProcessors(),
                isLowRamDevice = am.isLowRamDevice,
                hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                hasGps = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
                hasMagnetometer = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
                hasAccelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
                hasGyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
                hasInternet = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                isMeteredNetwork = cm.isActiveNetworkMetered
            )
        }
    }
}

enum class Tier(val fa: String) { LITE("سبک"), MID("متوسط"), HIGH("قوی") }
