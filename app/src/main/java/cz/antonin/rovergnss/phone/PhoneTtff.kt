package cz.antonin.rovergnss.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Varianta startu GNSS telefonu. */
enum class TtffVariant(val label: String) {
    COLD_OFFLINE("studený bez internetu"),
    COLD_AGNSS("studený s A-GNSS"),
    HOT("horký"),
}

/** Jeden pokus testu doby do fixu [s od spuštění příjmu]. */
data class TtffResult(
    val n: Int,
    val variant: TtffVariant,
    val startMs: Long,
    val deleteOk: Boolean?,   // delete_aiding_data přijat (null = neposláno / výjimka)
    val injectOk: Boolean?,   // force_psds_injection / force_xtra_injection přijat
    val online: Boolean,
    val firstAny: Double?,    // první hlášená poloha (i vložená z asistence, bez družic)
    val firstFix: Double?,    // první poloha s aspoň 4 družicemi v řešení
    val accAtFix: Double?,    // hlášená přesnost první polohy se 4 družicemi [m]
    val acc10: Double?,       // hlášená přesnost < 10 m (s aspoň 4 družicemi)
    val acc5: Double?,
    val to10: Double?,        // 2D chyba vůči roveru < 10 m
    val to5: Double?,
    val fusedFirst: Double?,
    val fusedTo10: Double?,
    val svAtFix: Int?,
    val svMax: Int,
    val timedOut: Boolean,
    val invalidCold: Boolean = false, // studený start s fixem do 5 s – čip nebyl vypnutý (jiná aplikace s polohou)
)

data class TtffState(
    val running: Boolean = false,
    val variant: TtffVariant? = null,
    val attempt: Int = 0,
    val attempts: Int = 0,
    val phase: String = "",
    val elapsed: Double = 0.0,
    val current: TtffResult? = null,
)

/**
 * Test doby do fixu GNSS telefonu (vliv A-GNSS). Příjem se vypne, podle varianty se smažou / stáhnou asistenční
 * data čipu (sendExtraCommand – podpora závisí na výrobci, výsledek se zapíše) a měří se čas do první polohy
 * a do přesnosti vůči roveru.
 */
class PhoneTtff(
    private val context: Context,
    private val scope: CoroutineScope,
    private val phone: PhoneGnss,
    private val errorOf: (PhoneFix) -> Double?,
    private val roverFix: () -> Boolean,      // rover má fix – jinak se test ukončí podle hlášené přesnosti
    private val message: (String) -> Unit,
) {
    private val _state = MutableStateFlow(TtffState())
    val state: StateFlow<TtffState> = _state.asStateFlow()

    private val _results = MutableStateFlow<List<TtffResult>>(emptyList())
    val results: StateFlow<List<TtffResult>> = _results.asStateFlow()

    private var job: Job? = null
    @Volatile private var attemptStartEl = 0L
    @Volatile private var cur: TtffResult? = null
    @Volatile private var withFused = false

    fun online(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun airplane(): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun start(variant: TtffVariant, repeats: Int, limitSec: Int, pauseSec: Int) {
        if (job?.isActive == true) return
        if (!phone.hasPermission()) return message("Test potřebuje oprávnění k poloze")
        val on = online()
        if (variant == TtffVariant.COLD_OFFLINE && on) {
            return message("Studený start bez internetu: zapni režim letadlo (Bluetooth k roveru nech zapnutý)")
        }
        if (variant == TtffVariant.COLD_AGNSS && !on) return message("Studený start s A-GNSS potřebuje internet")
        if (PhoneSource.GNSS !in phone.enabled.value) phone.setEnabled(PhoneSource.GNSS, true)
        // Fused (Google Play služby) a Network drží čip v chodu – u studeného startu se během pokusu vypnou
        val cold = variant != TtffVariant.HOT
        val hadFused = PhoneSource.FUSED in phone.enabled.value
        val hadNetwork = PhoneSource.NETWORK in phone.enabled.value
        withFused = !cold && hadFused
        job = scope.launch {
            try {
                for (k in 1..repeats) {
                    _state.value = TtffState(true, variant, k, repeats, "příprava")
                    val online = online()
                    var del: Boolean? = null
                    var inj: Boolean? = null
                    withContext(Dispatchers.Main) {
                        phone.stopSource(PhoneSource.GNSS)
                        if (hadFused) phone.stopSource(PhoneSource.FUSED)
                        if (cold && hadNetwork) phone.stopSource(PhoneSource.NETWORK)
                        if (variant != TtffVariant.HOT) del = phone.extraCommand("delete_aiding_data")
                        if (variant == TtffVariant.COLD_AGNSS) {
                            val psds = phone.extraCommand("force_psds_injection")
                            val xtra = phone.extraCommand("force_xtra_injection")
                            phone.extraCommand("force_time_injection")
                            inj = if (psds == null && xtra == null) null else (psds == true || xtra == true)
                        }
                    }
                    // čip potřebuje chvíli na zpracování příkazu (u A-GNSS i na stažení dat)
                    delay(if (variant == TtffVariant.COLD_AGNSS) 5000L else 2000L)
                    val r0 = TtffResult(
                        n = (_results.value.lastOrNull()?.n ?: 0) + 1, variant = variant, startMs = System.currentTimeMillis(),
                        deleteOk = del, injectOk = inj, online = online, firstAny = null, firstFix = null, accAtFix = null,
                        acc10 = null, acc5 = null, to10 = null, to5 = null,
                        fusedFirst = null, fusedTo10 = null, svAtFix = null, svMax = 0, timedOut = false,
                    )
                    cur = r0
                    attemptStartEl = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main) {
                        phone.startSource(PhoneSource.GNSS)
                        if (withFused) phone.startSource(PhoneSource.FUSED)
                    }
                    var timedOut = false
                    while (true) {
                        delay(200)
                        val el = (SystemClock.elapsedRealtime() - attemptStartEl) / 1000.0
                        val c = cur ?: break
                        val used = phone.svCount.value.first
                        if (used > c.svMax) cur = c.copy(svMax = used)
                        _state.value = TtffState(true, variant, k, repeats, "měřím", el, cur)
                        // s fixem roveru do chyby < 5 m, bez něj do hlášené přesnosti < 5 m
                        val gnssDone = if (roverFix()) c.to5 != null else c.acc5 != null
                        val done = gnssDone && (!withFused || c.fusedTo10 != null || (!roverFix() && c.fusedFirst != null))
                        if (done) break
                        if (el >= limitSec) {
                            timedOut = true
                            break
                        }
                    }
                    val res0 = cur ?: r0
                    val res = res0.copy(
                        timedOut = timedOut,
                        invalidCold = cold && res0.firstFix != null && res0.firstFix < INVALID_COLD_S,
                    )
                    cur = null
                    _results.value = _results.value + res
                    if (k < repeats) {
                        _state.value = TtffState(true, variant, k, repeats, "pauza")
                        delay(pauseSec * 1000L)
                    }
                }
            } finally {
                cur = null
                _state.value = TtffState()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    phone.startSource(PhoneSource.GNSS)
                    if (hadFused) phone.startSource(PhoneSource.FUSED)
                    if (hadNetwork) phone.startSource(PhoneSource.NETWORK)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        _results.value = emptyList()
    }

    /** Poloha telefonu během pokusu. */
    fun onFix(f: PhoneFix) {
        val c = cur ?: return
        if (f.elapsedMs < attemptStartEl) return
        val t = (f.elapsedMs - attemptStartEl) / 1000.0
        val err = errorOf(f)
        cur = when (f.source) {
            PhoneSource.GNSS -> {
                // poloha bez aspoň 4 družic v řešení je vložená (asistence, síť), ne fix z družic
                val sats = (f.numSv ?: 0) >= MIN_SV
                c.copy(
                    firstAny = c.firstAny ?: t,
                    firstFix = c.firstFix ?: if (sats) t else null,
                    accAtFix = if (c.firstFix == null && sats) f.acc else c.accAtFix,
                    svAtFix = if (c.firstFix == null && sats) f.numSv else c.svAtFix,
                    acc10 = c.acc10 ?: if (sats && f.acc != null && f.acc < 10) t else null,
                    acc5 = c.acc5 ?: if (sats && f.acc != null && f.acc < 5) t else null,
                    to10 = c.to10 ?: if (sats && err != null && err < 10) t else null,
                    to5 = c.to5 ?: if (sats && err != null && err < 5) t else null,
                )
            }
            PhoneSource.FUSED -> c.copy(
                fusedFirst = c.fusedFirst ?: t,
                fusedTo10 = c.fusedTo10 ?: if (err != null && err < 10) t else null,
            )
            PhoneSource.NETWORK -> c
        }
    }

    companion object {
        const val MIN_SV = 4
        const val INVALID_COLD_S = 5.0

        fun csv(list: List<TtffResult>, out: OutputStream): Int {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fun f(v: Double?) = if (v == null) "" else String.format(Locale.US, "%.1f", v)
            fun b(v: Boolean?) = when (v) { null -> ""; true -> "1"; false -> "0" }
            val w = out.bufferedWriter(Charsets.UTF_8)
            w.write("pokus;varianta;start;internet;delete_aiding_data;injekce;prvni_poloha_s;prvni_fix_4sv_s;presnost_fixu_m;" +
                "hlasena_pod_10m_s;hlasena_pod_5m_s;chyba_pod_10m_s;chyba_pod_5m_s;" +
                "fused_prvni_s;fused_pod_10m_s;sv_pri_fixu;sv_max;limit;neplatny_studeny\r\n")
            for (r in list) {
                w.write(
                    listOf(
                        r.n.toString(), r.variant.label, df.format(Date(r.startMs)), b(r.online), b(r.deleteOk), b(r.injectOk),
                        f(r.firstAny), f(r.firstFix), f(r.accAtFix), f(r.acc10), f(r.acc5), f(r.to10), f(r.to5),
                        f(r.fusedFirst), f(r.fusedTo10),
                        r.svAtFix?.toString() ?: "", r.svMax.toString(), if (r.timedOut) "1" else "0",
                        if (r.invalidCold) "1" else "0",
                    ).joinToString(";")
                )
                w.write("\r\n")
            }
            w.flush()
            return list.size
        }

        /** min / medián / max z hodnot (null = žádná hodnota). */
        fun summary(v: List<Double>): Triple<Double, Double, Double>? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
            return Triple(s.first(), med, s.last())
        }
    }
}
