package ir.bna.assistant.route

import android.content.Context
import android.location.Geocoder
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

data class Pt(val lat: Double, val lon: Double)
data class Step(val text: String, val lat: Double, val lon: Double, val meters: Int)
data class Note(val text: String, val lat: Double, val lon: Double)
class Route(val destName: String, val points: List<Pt>, val steps: List<Step>, val totalM: Int,
            val notes: MutableList<Note> = mutableListOf())

/**
 * مسیریابی پیاده با OSRM عمومی (رایگان) و ذخیره‌ی روی گوشی.
 * نقشه‌ی گرافیکی نیست؛ خروجی، مراحل متنی و فایل متنی قابل خواندن است.
 */
object RouteService {
    private const val FILE = "route.json"
    const val OFF_ROUTE_M = 40.0

    /** نام مقصد را به مختصات تبدیل می‌کند؛ باید در Thread اجرا شود */
    fun geocode(ctx: Context, name: String): Pt? {
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale("fa", "IR")).getFromLocationName(name, 1)?.firstOrNull()
            if (a != null) return Pt(a.latitude, a.longitude)
        } catch (e: Exception) { /* ادامه با سرویس جایگزین */ }
        return nominatim(name)
    }

    /** جایگزین رایگان OpenStreetMap وقتی Geocoder گوشی کار نمی‌کند */
    private fun nominatim(name: String): Pt? {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=fa&q=" + encode(name)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("User-Agent", "ir.bna.assistant")
        }
        val body = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        val arr = org.json.JSONArray(body)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return Pt(o.getString("lat").toDouble(), o.getString("lon").toDouble())
    }

    /** مسیر پیاده از مبدا به مقصد؛ باید در Thread اجرا شود */
    fun fetch(from: Location, to: Pt, destName: String): Route? = fetch(Pt(from.latitude, from.longitude), to, destName)

    fun fetch(from: Pt, to: Pt, destName: String): Route? {
        val tail = "${from.lon},${from.lat};${to.lon},${to.lat}?steps=true&geometries=geojson&overview=full"
        // سرور پیاده‌ی FOSSGIS؛ نام پروفایل در نشانی نادیده گرفته می‌شود، پس هر دو شکل را امتحان می‌کنیم
        val base = "https://routing.openstreetmap.de/routed-foot/route/v1/"
        val urls = listOf(base + "foot/" + tail, base + "driving/" + tail, base + "foot/" + tail)
        var last: Exception? = null
        for (u in urls) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 25000
                    setRequestProperty("User-Agent", "ir.bna.assistant")
                }
                val body = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
                val j = JSONObject(body)
                if (!isWalking(j)) { ir.bna.assistant.core.AppLog.e("مسیریابی", java.io.IOException("پاسخ مسیر خودرو بود و رد شد")); continue }
                val r = parse(j, destName)
                if (r != null) return r
            } catch (e: Exception) { last = e; ir.bna.assistant.core.AppLog.e("مسیریابی پیاده", e) }
        }
        if (last != null) throw last
        return null
    }

    /** سرعت میانگین مسیر را می‌سنجد: پیاده حدود ۵ کیلومتر در ساعت است، خودرو چند برابر */
    private fun isWalking(j: JSONObject): Boolean {
        if (j.optString("code") != "Ok") return true
        val r = j.getJSONArray("routes").getJSONObject(0)
        val d = r.optDouble("distance"); val t = r.optDouble("duration")
        if (d < 300 || t <= 0) return true
        return d / t < 2.8 // متر بر ثانیه؛ بالاتر از حدود ۱۰ کیلومتر در ساعت یعنی خودرو
    }

    private fun parse(j: JSONObject, destName: String): Route? {
        if (j.optString("code") != "Ok") return null
        val r = j.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { i ->
            val c = coords.getJSONArray(i); Pt(c.getDouble(1), c.getDouble(0))
        }
        val steps = mutableListOf<Step>()
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val ss = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until ss.length()) {
                val s = ss.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val name = s.optString("name")
                steps.add(Step(
                    text = instructionFa(m.optString("type"), m.optString("modifier"), name),
                    lat = loc.getDouble(1), lon = loc.getDouble(0),
                    meters = s.optDouble("distance").toInt()))
            }
        }
        return Route(destName, pts, steps, r.optDouble("distance").toInt())
    }

    fun instructionFa(type: String, modifier: String, name: String): String {
        val dir = when (modifier) {
            "left" -> "به چپ"; "right" -> "به راست"; "slight left" -> "کمی به چپ"
            "slight right" -> "کمی به راست"; "sharp left" -> "تند به چپ"; "sharp right" -> "تند به راست"
            "uturn" -> "دور بزنید"; else -> ""
        }
        val road = if (name.isNotBlank()) " در $name" else ""
        return when (type) {
            "depart" -> "شروع از اینجا$road"
            "arrive" -> "به مقصد رسیدید"
            "turn", "end of road", "fork", "off ramp", "on ramp" -> "$dir بپیچید$road"
            "roundabout", "rotary" -> "در میدان $dir بروید$road"
            "new name", "continue", "merge" -> if (dir.isNotEmpty()) "$dir بروید$road" else "مستقیم ادامه دهید$road"
            else -> if (dir.isNotEmpty()) "$dir بروید$road" else "مستقیم ادامه دهید$road"
        }
    }

    /** فاصله‌ی متر تا نزدیک‌ترین نقطه‌ی مسیر */
    fun distanceToRoute(r: Route, lat: Double, lon: Double): Double {
        var best = Double.MAX_VALUE
        val kx = cos(Math.toRadians(lat)) * 111320.0
        val ky = 110540.0
        for (i in 0 until r.points.size - 1) {
            val a = r.points[i]; val b = r.points[i + 1]
            val ax = (a.lon - lon) * kx; val ay = (a.lat - lat) * ky
            val bx = (b.lon - lon) * kx; val by = (b.lat - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + dx * t; val py = ay + dy * t
            best = minOf(best, sqrt(px * px + py * py))
        }
        return if (best == Double.MAX_VALUE) 0.0 else best
    }

    private fun toJson(r: Route): JSONObject {
        val j = JSONObject().put("dest", r.destName).put("total", r.totalM)
        j.put("points", JSONArray().apply { r.points.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
        j.put("steps", JSONArray().apply {
            r.steps.forEach { put(JSONObject().put("t", it.text).put("lat", it.lat).put("lon", it.lon).put("m", it.meters)) }
        })
        j.put("notes", JSONArray().apply {
            r.notes.forEach { put(JSONObject().put("t", it.text).put("lat", it.lat).put("lon", it.lon)) }
        })
        return j
    }

    private fun libFile(ctx: Context, r: Route) = File(File(ctx.filesDir, "routes"), safeName(r.destName) + ".json")

    fun isSaved(ctx: Context, r: Route) = libFile(ctx, r).exists()

    /** ذخیره در فهرست مسیرها؛ فقط با موافقت کاربر صدا زده می‌شود */
    fun saveToLibrary(ctx: Context, r: Route) {
        val f = libFile(ctx, r); f.parentFile?.mkdirs(); f.writeText(toJson(r).toString())
    }

    /** مسیر جاری را می‌نویسد؛ اگر در فهرست هم ذخیره شده بود آن را هم به‌روز می‌کند */
    fun save(ctx: Context, r: Route) {
        File(ctx.filesDir, FILE).writeText(toJson(r).toString())
        if (isSaved(ctx, r)) saveToLibrary(ctx, r)
        // نقشه‌ی متنی برای خواندن با هر برنامه‌ی متنی
        val sb = StringBuilder("مسیر به ${r.destName} - حدود ${r.totalM} متر\n\n")
        r.steps.forEachIndexed { i, s -> sb.append("${i + 1}. ${s.text} (${s.meters} متر)\n") }
        if (r.notes.isNotEmpty()) { sb.append("\nیادداشت‌ها:\n"); r.notes.forEach { sb.append("- ${it.text} (${it.lat}, ${it.lon})\n") } }
        File(ctx.filesDir, "route.txt").writeText(sb.toString())
    }

    private fun safeName(n: String) = n.replace(Regex("[^\\p{L}\\p{N}]+"), "_").take(60).ifEmpty { "route" }

    /** فهرست مسیرهای ذخیره‌شده، تازه‌ترین اول */
    fun saved(ctx: Context): List<File> =
        File(ctx.filesDir, "routes").listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun loadFile(f: File): Route? = parseFile(f)
    fun deleteSaved(f: File) { f.delete() }

    fun load(ctx: Context): Route? = parseFile(File(ctx.filesDir, FILE))

    private fun parseFile(f: File): Route? = try {
        val j = JSONObject(f.readText())
        val p = j.getJSONArray("points")
        val s = j.getJSONArray("steps")
        Route(j.getString("dest"),
            (0 until p.length()).map { Pt(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) },
            (0 until s.length()).map { val o = s.getJSONObject(it)
                Step(o.getString("t"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("m")) },
            j.getInt("total"),
            (j.optJSONArray("notes") ?: JSONArray()).let { n ->
                (0 until n.length()).map { val o = n.getJSONObject(it); Note(o.getString("t"), o.getDouble("lat"), o.getDouble("lon")) }.toMutableList() })
    } catch (e: Exception) { null }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete(); File(ctx.filesDir, "route.txt").delete()
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
