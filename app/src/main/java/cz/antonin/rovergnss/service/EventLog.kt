package cz.antonin.rovergnss.service

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deník událostí aplikace (0.9.0) – k prošetření konce záznamu bez zásahu (25. 10.) a pádu aplikace (23. 10.):
 * začátek a konec záznamu trasy i s důvodem, testy, NTRIP, popředí/pozadí, spojení s roverem a nezachycené
 * výjimky (pád) se zásobníkem volání. Soubor `files/events.log`, nejvýš ~1 MB (pak se starší polovina zahodí).
 * Přikládá se do ZIPu testu (`events.log` za dobu testu).
 */
object EventLog {
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null
    private val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "events.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            log("PÁD vlákno ${t.name}: $sw")
            prev?.uncaughtException(t, e)
        }
        log("start aplikace")
    }

    @Synchronized
    fun log(msg: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep)
            }
            f.appendText("${df.format(Date())} $msg\n")
        } catch (_: Exception) {
        }
    }

    /** Řádky deníku od času [fromMs] (pro ZIP testu). */
    @Synchronized
    fun since(fromMs: Long): String {
        val f = file ?: return ""
        return try {
            val from = df.format(Date(fromMs))
            f.readLines().filter { it.length >= 23 && it.substring(0, 23) >= from }.joinToString("\n", postfix = "\n")
        } catch (_: Exception) {
            ""
        }
    }
}
