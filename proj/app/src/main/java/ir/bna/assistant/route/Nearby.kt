package ir.bna.assistant.route

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sqrt

/** مکان‌های اطراف از OpenStreetMap (Overpass)؛ رایگان. باید در Thread اجرا شود */
object Nearby {
    private val SERVERS = listOf(
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.osm.jp/api/interpreter"
        )

    private fun cacheFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "nearby_cache.json")

    private fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Int {
        val dx = (lo2 - lo1) * cos(Math.toRadians(la1)) * 111320.0; val dy = (la2 - la1) * 110540.0
        return sqrt(dx * dx + dy * dy).toInt()
    }

    private fun saveCache(ctx: android.content.Context, lat: Double, lon: Double, items: List<Triple<String, Double, Double>>) {
        try {
            val f = cacheFile(ctx)
            val all = try { org.json.JSONArray(f.readText()) } catch (e: Exception) { org.json.JSONArray() }
            val keep = org.json.JSONArray()
            for (i in 0 until all.length()) { val o = all.getJSONObject(i); if (dist(lat, lon, o.getDouble("lat"), o.getDouble("lon")) > 30) keep.put(o) }
            val arr = org.json.JSONArray()
            items.forEach { arr.put(JSONObject().put("n", it.first).put("la", it.second).put("lo", it.third)) }
            keep.put(JSONObject().put("lat", lat).put("lon", lon).put("t", System.currentTimeMillis()).put("items", arr))
            val out = org.json.JSONArray()
            for (i in maxOf(0, keep.length() - 30) until keep.length()) out.put(keep.get(i))
            f.writeText(out.toString())
        } catch (e: Exception) { ir.bna.assistant.core.AppLog.e("ذخیره حافظه مکان‌ها", e) }
    }

    private fun loadCache(ctx: android.content.Context, lat: Double, lon: Double): List<String>? {
        try {
            val all = org.json.JSONArray(cacheFile(ctx).readText())
            var best: JSONObject? = null; var bd = 101
            for (i in 0 until all.length()) { val o = all.getJSONObject(i); val d = dist(lat, lon, o.getDouble("lat"), o.getDouble("lon")); if (d < bd) { bd = d; best = o } }
            val items = best?.getJSONArray("items") ?: return null
            val out = ArrayList<Pair<Int, String>>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val d = dist(lat, lon, o.getDouble("la"), o.getDouble("lo"))
                out.add(d to (o.getString("n") + " در $d متری"))
            }
            return out.sortedBy { it.first }.take(5).map { it.second }
        } catch (e: Exception) { return null }
    }

    /** با سرور؛ در شکست، از حافظهٔ گوشی (second=true یعنی قدیمی) */
    fun findCached(ctx: android.content.Context, lat: Double, lon: Double, radius: Int = 150): Pair<List<String>, Boolean> {
        try { return find(ctx, lat, lon, radius) to false } catch (e: Exception) {
            val c = loadCache(ctx, lat, lon)
            if (c != null && c.isNotEmpty()) { ir.bna.assistant.core.AppLog.i("جزئیات: سرور جواب نداد؛ نتیجهٔ ذخیره‌شده استفاده شد"); return c to true }
            throw e
        }
    }

    private val TYPES = mapOf(
        "bank" to "بانک", "pharmacy" to "داروخانه", "hospital" to "بیمارستان", "clinic" to "درمانگاه",
        "restaurant" to "رستوران", "cafe" to "کافه", "fast_food" to "فست‌فود", "school" to "مدرسه",
        "university" to "دانشگاه", "mosque" to "مسجد", "place_of_worship" to "عبادتگاه",
        "fuel" to "پمپ بنزین", "atm" to "خودپرداز", "post_office" to "اداره پست", "police" to "پلیس",
        "bakery" to "نانوایی", "supermarket" to "سوپرمارکت", "convenience" to "سوپرمارکت",
        "clothes" to "پوشاک", "mall" to "مرکز خرید", "park" to "پارک", "bus_station" to "ایستگاه اتوبوس",
        "subway_entrance" to "ورودی مترو", "government" to "اداره دولتی", "hotel" to "هتل",
        "dentist" to "دندانپزشکی", "doctors" to "مطب", "kindergarten" to "مهدکودک", "library" to "کتابخانه")

    fun find(ctx: android.content.Context, lat: Double, lon: Double, radius: Int = 150): List<String> {
        val keys = listOf("amenity", "shop", "office", "tourism", "leisure", "highway", "railway")
        val q = "[out:json][timeout:10];(" + keys.joinToString("") { "nwr(around:$radius,$lat,$lon)[\"$it\"][\"name\"];" } + ");out center 60;"
        var body: String? = null
        var last: Exception? = null
        val log = ir.bna.assistant.core.AppLog
        log.i("جزئیات مکان‌های اطراف: lat=$lat lon=$lon شعاع=$radius متر؛ پرس‌وجو ${q.length} نویسه")
        for (s in listOf(SERVERS[0]) + SERVERS) {
            if (body != null) break
            val host = java.net.URI(s).host
            val t0 = System.currentTimeMillis()
            try {
                val c = (URL(s).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 6000; readTimeout = 12000
                    setRequestProperty("User-Agent", "ir.bna.assistant")
                }
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                val code = c.responseCode
                log.i("جزئیات: $host پاسخ HTTP $code پس از ${System.currentTimeMillis() - t0} میلی‌ثانیه")
                if (code != 200) { c.disconnect(); throw java.io.IOException("HTTP $code از ${java.net.URI(s).host}") }
                body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                log.i("جزئیات: $host ${body!!.length} نویسه دریافت شد")
                break
            } catch (e: Exception) {
                last = e
                log.e("اورپس $host پس از ${System.currentTimeMillis() - t0} میلی‌ثانیه (اتصال ${if (e is java.net.SocketTimeoutException) "یا پاسخ کند" else "ناموفق"})", e)
            }
        }
        if (body == null) throw last ?: java.io.IOException("overpass")
        val els = JSONObject(body).getJSONArray("elements")
        val kx = cos(Math.toRadians(lat)) * 111320.0
        log.i("جزئیات: ${els.length()} عنصر از سرور آمد")
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Int, String>>()
        val raw = ArrayList<Triple<String, Double, Double>>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name:fa").ifBlank { tags.optString("name") }
            if (name.isBlank() || !seen.add(name)) continue
            val la = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.getDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.getDouble("lon") ?: continue
            val dx = (lo - lon) * kx; val dy = (la - lat) * 110540.0
            val d = sqrt(dx * dx + dy * dy).toInt()
            val kind = listOf("amenity", "shop", "office", "tourism", "leisure", "highway", "railway")
                .firstNotNullOfOrNull { k -> tags.optString(k).takeIf { it.isNotEmpty() } }
            val fa = kind?.let { TYPES[it] }
            val label = if (fa != null) "$fa $name" else name
            out.add(d to "$label در $d متری")
            raw.add(Triple(label, la, lo))
        }
        saveCache(ctx, lat, lon, raw)
        return out.sortedBy { it.first }.take(5).map { it.second }
    }
}
