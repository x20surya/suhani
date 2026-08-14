package app.suhani

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * App-wide diagnostic log: mirrors to logcat AND persists to a capped file so
 * failures on मालकिन's phone can be read (and copied to Surya) from inside the
 * app - no adb required.
 */
object AppLog {
    private const val MAX_BYTES = 200_000L
    private val stamp = DateTimeFormatter.ofPattern("dd-MM HH:mm:ss")
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file == null) {
            file = File(context.applicationContext.filesDir, "app.log")
        }
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                // Keep the newest half when the file grows too big.
                val tail = f.readText().let { it.substring(it.length / 2) }
                f.writeText(tail)
            }
            f.appendText("${LocalDateTime.now().format(stamp)} $level/$tag: $msg\n")
        } catch (_: Exception) {}
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        write("W", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        val detail = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        write("E", tag, msg + detail)
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText() ?: "(empty)"

    fun clear() {
        file?.delete()
    }
}
