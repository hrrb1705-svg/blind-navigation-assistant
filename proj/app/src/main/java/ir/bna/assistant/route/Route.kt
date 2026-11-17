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
 * مسیریابی پیاده با Valhalla عمومی (رایگان) و ذخیره‌ی روی گوشی.
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

    // Valhalla با حالت pedestrian: فقط مسیر عابر می‌دهد و یک‌طرفه‌ی خودرو را اعمال نمی‌کند
    private val VALHALLA = listOf(
        "https://valhalla1.openstreetmap.de/route",
        "https://valhalla.openstreetmap.de/route")

    fun fetch(from: Pt, to: Pt, destName: String): Route? {
        val req = JSONObject()
            .put("locations", JSONArray()
                .put(JSONObject().put("lat", from.lat).put("lon", from.lon))
                .put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", "pedestrian")
            .put("directions_options", JSONObject().put("units", "kilometers"))
            .toString()
        var last: Exception? = null
        for (u in VALHALLA) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 25000
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("User-Agent", "ir.bna.assistant")
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(req.toByteArray()) }
                val body = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
                val r = parse(JSONObject(body), destName)
                if (r != null) return r
            } catch (e: Exception) { last = e; ir.bna.assistant.core.AppLog.e("مسیریابی پیاده (Valhalla)", e) }
        }
        if (last != null) throw last
        return null
    }

    private fun decodeShape(str: String): List<Pt> {
        val out = ArrayList<Pt>(); var i = 0; var la = 0; var lo = 0
        while (i < str.length) {
            var sh = 0; var res = 0; var b: Int
            do { b = str[i++].code - 63; res = res or ((b and 31) shl sh); sh += 5 } while (b >= 32)
            la += if (res and 1 != 0) (res shr 1).inv() else res shr 1
            sh = 0; res = 0
            do { b = str[i++].code - 63; res = res or ((b and 31) shl sh); sh += 5 } while (b >= 32)
            lo += if (res and 1 != 0) (res shr 1).inv() else res shr 1
            out.add(Pt(la / 1e6, lo / 1e6))
        }
        return out
    }

    private fun parse(j: JSONObject, destName: String): Route? {
        val trip = j.optJSONObject("trip") ?: return null
        if (trip.optInt("status", 0) != 0) return null
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val pts = decodeShape(leg.getString("shape"))
        if (pts.size < 2) return null
        val ms = leg.getJSONArray("maneuvers")
        val steps = mutableListOf<Step>()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val names = m.optJSONArray("street_names")
            val name = if (names != null && names.length() > 0) names.getString(0) else ""
            val (type, mod) = when (m.optInt("type")) {
                1, 2, 3 -> "depart" to ""
                4, 5, 6 -> "arrive" to ""
                9 -> "turn" to "slight right"; 10 -> "turn" to "right"; 11 -> "turn" to "sharp right"
                12, 13 -> "turn" to "uturn"
                14 -> "turn" to "sharp left"; 15 -> "turn" to "left"; 16 -> "turn" to "slight left"
                26, 27 -> "roundabout" to ""
                else -> "continue" to ""
            }
            val p = pts[m.optInt("begin_shape_index").coerceIn(0, pts.size - 1)]
            steps.add(Step(instructionFa(type, mod, name), p.lat, p.lon, (m.optDouble("length") * 1000).toInt()))
        }
        val total = (trip.getJSONObject("summary").optDouble("length") * 1000).toInt()
        return Route(destName, pts, steps, total)
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
