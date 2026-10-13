package ir.bna.assistant.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ثبت خطاها در فایل داخلی برنامه و Logcat */
object AppLog {
    private const val TAG = "BNA"
    private var file: File? = null

    fun init(ctx: Context) { file = File(ctx.filesDir, "bna_log.txt") }

    fun i(msg: String) { Log.i(TAG, msg); write("INFO", msg) }
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t); write("ERROR", msg + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun write(level: String, msg: String) {
        try {
            val f = file ?: return
            if (f.length() > 200_000) f.writeText("")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$time [$level] $msg\n")
        } catch (_: Exception) { }
    }

    fun read(): String = try { file?.readText()?.takeLast(3000) ?: "" } catch (e: Exception) { "" }
    fun clear() { try { file?.writeText("") } catch (_: Exception) { } }
}
