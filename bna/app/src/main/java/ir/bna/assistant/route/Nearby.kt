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
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter")

    private val TYPES = mapOf(
        "bank" to "بانک", "pharmacy" to "داروخانه", "hospital" to "بیمارستان", "clinic" to "درمانگاه",
        "restaurant" to "رستوران", "cafe" to "کافه", "fast_food" to "فست‌فود", "school" to "مدرسه",
        "university" to "دانشگاه", "mosque" to "مسجد", "place_of_worship" to "عبادتگاه",
        "fuel" to "پمپ بنزین", "atm" to "خودپرداز", "post_office" to "اداره پست", "police" to "پلیس",
        "bakery" to "نانوایی", "supermarket" to "سوپرمارکت", "convenience" to "سوپرمارکت",
        "clothes" to "پوشاک", "mall" to "مرکز خرید", "park" to "پارک", "bus_station" to "ایستگاه اتوبوس",
        "subway_entrance" to "ورودی مترو", "government" to "اداره دولتی", "hotel" to "هتل",
        "dentist" to "دندانپزشکی", "doctors" to "مطب", "kindergarten" to "مهدکودک", "library" to "کتابخانه")

    fun find(lat: Double, lon: Double, radius: Int = 150): List<String> {
        val keys = listOf("amenity", "shop", "office", "tourism", "leisure", "highway", "railway")
        val q = "[out:json][timeout:20];(" + keys.joinToString("") { "nwr(around:$radius,$lat,$lon)[\"$it\"][\"name\"];" } + ");out center 60;"
        var body: String? = null
        var last: Exception? = null
        for (s in SERVERS) {
            try {
                val c = (URL(s).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 10000; readTimeout = 25000
                    setRequestProperty("User-Agent", "ir.bna.assistant")
                }
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                val code = c.responseCode
                if (code != 200) { c.disconnect(); throw java.io.IOException("HTTP $code از ${java.net.URI(s).host}") }
                body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                break
            } catch (e: Exception) {
                last = e
                ir.bna.assistant.core.AppLog.e("اورپس ${java.net.URI(s).host}", e)
            }
        }
        if (body == null) throw last ?: java.io.IOException("overpass")
        val els = JSONObject(body).getJSONArray("elements")
        val kx = cos(Math.toRadians(lat)) * 111320.0
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Int, String>>()
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
            out.add(d to ((if (fa != null) "$fa $name" else name) + " در $d متری"))
        }
        return out.sortedBy { it.first }.take(5).map { it.second }
    }
}
