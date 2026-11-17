package ir.bna.assistant.route

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

data class Obstacle(val lat: Double, val lon: Double, val text: String, val photo: String, val time: Long)

/** موانع گزارش‌شده همراه با عکس و مکان؛ عکس‌ها در filesDir/obs ذخیره می‌شوند */
object Obstacles {
    private const val FILE = "obstacles.json"

    fun dir(ctx: Context): File = File(ctx.filesDir, "obs").apply { mkdirs() }

    fun load(ctx: Context): MutableList<Obstacle> = try {
        val a = JSONArray(File(ctx.filesDir, FILE).readText())
        (0 until a.length()).map { val o = a.getJSONObject(it)
            Obstacle(o.getDouble("lat"), o.getDouble("lon"), o.getString("t"), o.optString("p"), o.getLong("time")) }
            .toMutableList()
    } catch (e: Exception) { mutableListOf() }

    fun add(ctx: Context, o: Obstacle) {
        val list = load(ctx); list.add(o)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("lat", it.lat).put("lon", it.lon)
            .put("t", it.text).put("p", it.photo).put("time", it.time)) }
        File(ctx.filesDir, FILE).writeText(a.toString())
    }

    /** موانع نزدیک (شعاع متر) */
    fun near(ctx: Context, lat: Double, lon: Double, radiusM: Double = 20.0): List<Obstacle> {
        val kx = cos(Math.toRadians(lat)) * 111320.0
        return load(ctx).filter {
            val dx = (it.lon - lon) * kx; val dy = (it.lat - lat) * 110540.0
            sqrt(dx * dx + dy * dy) <= radiusM
        }
    }
}
