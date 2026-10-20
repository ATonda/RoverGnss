package cz.antonin.rovergnss.service

import android.content.Context
import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cíl pokusu doby do fixu roveru. */
enum class TtffTarget(val label: String) {
    FIX3D("3D fix"),
    CORR("korekce (SBAS / HAS)"),
    FLOAT("float (HAS / RTK)"),
    RTK_FIX("RTK fix"),
}

/** Jeden pokus: časy od restartu X20 [ms], null = nedosaženo. */
data class TtffTry(
    val kind: String,          // hot, warm, cold
    val startMs: Long,         // čas telefonu
    val fix3d: Long? = null,
    val corr: Long? = null,
    val float: Long? = null,
    val rtkFix: Long? = null,
    val timeout: Boolean = false,
    val firstSv: Int? = null,  // SV při 3D fixu
)

data class TtffState(
    val running: Boolean = false,
    val kind: String = "hot",
    val target: TtffTarget = TtffTarget.FIX3D,
    val reps: Int = 3,
    val limitSec: Int = 300,
    val pauseSec: Int = 10,
    val current: TtffTry? = null,
    val done: List<TtffTry> = emptyList(),
    val status: String = "",
)

/**
 * Doba do prvního fixu po zapnutí roveru (test zálohy X20 superkondenzátorem).
 * [offMin] = odhad doby vypnutí: od posledních dat z roveru do jeho zapnutí.
 */
data class BootRecord(
    val atMs: Long,            // čas telefonu při zjištění
    val offMin: Double?,       // [min], null = neznámé (minule nepřipojen)
    val fixS: Double?,         // [s] od zapnutí
    val corrS: Double?,
    val floatS: Double?,
    val rtkS: Double?,
) {
    fun toJson(): JSONObject = JSONObject().put("at", atMs).apply {
        offMin?.let { put("off", it) }
        fixS?.let { put("fix", it) }
        corrS?.let { put("corr", it) }
        floatS?.let { put("float", it) }
        rtkS?.let { put("rtk", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = BootRecord(
            atMs = o.optLong("at"),
            offMin = if (o.has("off")) o.optDouble("off") else null,
            fixS = if (o.has("fix")) o.optDouble("fix") else null,
            corrS = if (o.has("corr")) o.optDouble("corr") else null,
            floatS = if (o.has("float")) o.optDouble("float") else null,
            rtkS = if (o.has("rtk")) o.optDouble("rtk") else null,
        )
    }
}

/**
 * Test doby do fixu roveru: restart X20 (horký / teplý / studený) příkazem a měření času do 3D fixu,
 * korekcí, float a RTK fix podle $PRVR,POS. Navíc vyhodnocení po zapnutí roveru (FW 0.13.0+ hlásí v `get info`
 * dobu od zapnutí do fixu) – kolik vydrží záloha X20.
 */
class RoverTtff(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val reset: suspend (String) -> Boolean,
    private val message: (String) -> Unit,
) {
    private val _state = MutableStateFlow(TtffState())
    val state: StateFlow<TtffState> = _state.asStateFlow()
    private var job: Job? = null

    private val sp = context.getSharedPreferences("rover_ttff", Context.MODE_PRIVATE)
    private val _boots = MutableStateFlow(loadBoots())
    val boots: StateFlow<List<BootRecord>> = _boots.asStateFlow()
    private var lastSeenSave = 0L

    fun configure(kind: String, target: TtffTarget, reps: Int, limitSec: Int, pauseSec: Int) {
        if (_state.value.running) return
        _state.value = _state.value.copy(kind = kind, target = target, reps = reps.coerceIn(1, 50),
            limitSec = limitSec.coerceIn(10, 3600), pauseSec = pauseSec.coerceIn(0, 600))
    }

    fun start() {
        if (_state.value.running) return
        _state.value = _state.value.copy(running = true, done = emptyList(), current = null, status = "start")
        job = scope.launch {
            val s0 = _state.value
            for (i in 1..s0.reps) {
                if (!_state.value.running) break
                _state.value = _state.value.copy(status = "pokus $i/${s0.reps}: restart X20 (${kindText(s0.kind)})")
                if (!reset(s0.kind)) {
                    message("Test doby do fixu: rover nepotvrdil restart X20")
                    break
                }
                val t = TtffTry(kind = s0.kind, startMs = System.currentTimeMillis())
                _state.value = _state.value.copy(current = t)
                // čekat na cíl nebo limit
                while (_state.value.running) {
                    val c = _state.value.current ?: break
                    val el = System.currentTimeMillis() - c.startMs
                    if (reached(c, s0.target)) break
                    if (el > s0.limitSec * 1000L) {
                        _state.value = _state.value.copy(current = c.copy(timeout = true))
                        break
                    }
                    _state.value = _state.value.copy(status = "pokus $i/${s0.reps}: ${el / 1000} s")
                    delay(200)
                }
                val fin = _state.value.current ?: break
                _state.value = _state.value.copy(current = null, done = _state.value.done + fin)
                if (i < s0.reps && _state.value.running && s0.pauseSec > 0) {
                    _state.value = _state.value.copy(status = "pauza ${s0.pauseSec} s")
                    delay(s0.pauseSec * 1000L)
                }
            }
            _state.value = _state.value.copy(running = false, current = null, status = "hotovo")
        }
    }

    fun stop() {
        _state.value = _state.value.copy(running = false)
        job?.cancel()
        _state.value.current?.let { c -> _state.value = _state.value.copy(current = null, done = _state.value.done + c) }
        _state.value = _state.value.copy(status = "zastaveno")
    }

    private fun reached(t: TtffTry, target: TtffTarget) = when (target) {
        TtffTarget.FIX3D -> t.fix3d != null
        TtffTarget.CORR -> t.corr != null
        TtffTarget.FLOAT -> t.float != null
        TtffTarget.RTK_FIX -> t.rtkFix != null
    }

    /** Každá epocha z roveru. */
    fun onPos(p: Pos) {
        val now = System.currentTimeMillis()
        if (now - lastSeenSave > 5000) {
            lastSeenSave = now
            val seen = sp.getLong("lastSeen", 0L)
            val e = sp.edit()
            // začátek mezery v datech (rover vypnutý nebo mimo dosah) – pro odhad doby vypnutí
            if (seen > 0 && now - seen > 60_000L) e.putLong("gapStart", seen)
            e.putLong("lastSeen", now).apply()
        }
        val c = _state.value.current ?: return
        val el = p.rxMs - c.startMs
        if (el < 0) return
        var n = c
        val fix = p.hasFix && p.quality != Quality.DR
        if (n.fix3d == null && fix) n = n.copy(fix3d = el, firstSv = p.numSv)
        if (n.corr == null && fix && p.quality in setOf(Quality.DGNSS, Quality.HAS, Quality.FLOAT, Quality.FIX)) n = n.copy(corr = el)
        if (n.float == null && fix && p.carrSoln >= 1) n = n.copy(float = el)
        if (n.rtkFix == null && p.quality == Quality.FIX) n = n.copy(rtkFix = el)
        if (n != c) _state.value = _state.value.copy(current = n)
    }

    /**
     * Odpověď `get info`: po zapnutí napájení roveru (ne po SW restartu – X20 pak běží dál) zapsat dobu
     * do fixu od zapnutí. Opakované `get info` během stejného startu jen doplní časy.
     */
    fun onInfo(i: RoverInfo) {
        val b = i.boot ?: return
        val up = i.upMs ?: return
        if (i.resetReason != 1) return            // 1 = zapnutí napájení
        if (up > 30 * 60_000L) return              // rover běží dlouho – start už byl vyhodnocen
        val now = System.currentTimeMillis()
        val bootAt = now - up
        val l = _boots.value
        val prevBoot = sp.getLong("lastBootAt", 0L)
        val same = prevBoot != 0L && kotlin.math.abs(prevBoot - bootAt) < 5000 && l.isNotEmpty()
        val off = if (same) l.first().offMin else {
            val gap = sp.getLong("gapStart", 0L).takeIf { it in 1 until bootAt }
                ?: sp.getLong("lastSeen", 0L).takeIf { it in 1 until bootAt }
            gap?.let { (bootAt - it) / 60_000.0 }
        }
        val rec = BootRecord(now, off, b.fix?.div(1000.0), b.corr?.div(1000.0), b.float?.div(1000.0), b.fixRtk?.div(1000.0))
        sp.edit().putLong("lastBootAt", bootAt).remove("gapStart").apply()
        saveBoots((if (same) listOf(rec) + l.drop(1) else listOf(rec) + l).take(100))
    }

    fun clearBoots() = saveBoots(emptyList())

    private fun loadBoots(): List<BootRecord> = try {
        val a = JSONArray(sp.getString("boots", "[]"))
        (0 until a.length()).map { BootRecord.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveBoots(l: List<BootRecord>) {
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        sp.edit().putString("boots", a.toString()).apply()
        _boots.value = l
    }

    /** CSV pokusů restartu X20. */
    fun csv(out: OutputStream) {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("cas;start;fix3d_s;korekce_s;float_s;rtk_fix_s;limit;sv_pri_fixu\r\n")
        fun s(v: Long?) = v?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: ""
        for (t in _state.value.done) {
            w.write(listOf(df.format(Date(t.startMs)), t.kind, s(t.fix3d), s(t.corr), s(t.float), s(t.rtkFix),
                if (t.timeout) "1" else "0", t.firstSv?.toString() ?: "").joinToString(";"))
            w.write("\r\n")
        }
        w.write("\r\n# po zapnutí roveru\r\ncas;vypnuto_min;fix3d_s;korekce_s;float_s;rtk_fix_s\r\n")
        fun d(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        for (b in _boots.value) {
            w.write(listOf(df.format(Date(b.atMs)), d(b.offMin), d(b.fixS), d(b.corrS), d(b.floatS), d(b.rtkS)).joinToString(";"))
            w.write("\r\n")
        }
        w.flush()
    }

    companion object {
        fun kindText(k: String) = when (k) {
            "hot" -> "horký"
            "warm" -> "teplý"
            "cold" -> "studený"
            else -> k
        }
    }
}
