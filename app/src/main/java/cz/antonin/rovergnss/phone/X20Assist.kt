package cz.antonin.rovergnss.phone

import android.content.Context
import android.util.Log
import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.Sta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** Stav asistence X20 pro obrazovku. */
data class AssistState(
    val fwOk: Boolean? = null,        // rover s FW 0.11.0+ (null = rover nepřipojen / neznámý)
    val lastIniMs: Long = 0,
    val lastIni: String? = null,      // popis posledního odeslání MGA-INI
    val backupRunning: Boolean = false,
    val lastBackupMs: Long = 0,
    val lastBackup: String? = null,   // výsledek poslední zálohy MGA-DBD
    val lastRestoreMs: Long = 0,
    val restoredFrames: Int = 0,
    val ackAccepted: Long? = null,    // MGA-ACK od připojení
    val ackRejected: Long? = null,
    val ackAiding: Int? = null,
    val ubxInOk: Long? = null,
    val ubxInBad: Long? = null,
)

/**
 * Asistence X20 z telefonu bez internetu (FW roveru 0.11.0+):
 *  1. UBX-MGA-INI-TIME_UTC a MGA-INI-POS_LLH – čas a přibližná poloha po připojení, když X20 nemá fix,
 *  2. záloha navigační databáze (UBX-MGA-DBD) při fixu a její vrácení při studeném startu.
 * Rámce UBX jdou do roveru a ten je předá beze změny do UART1 X20.
 */
class X20Assist(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val send: (ByteArray) -> Unit,
    private val phoneFix: () -> PhoneFix?,
    private val busy: () -> Boolean,          // probíhá měření – výpis databáze by zatížil spojení
    private val message: (String) -> Unit,
) {
    private val _state = MutableStateFlow(AssistState())
    val state: StateFlow<AssistState> = _state.asStateFlow()

    @Volatile private var connected = false
    @Volatile private var connectedMs = 0L
    @Volatile private var lastPos: Pos? = null
    @Volatile private var lastSta: Sta? = null
    private var staAtConnect: Sta? = null
    private var lastFixSaveMs = 0L
    private var lastBackupTryMs = 0L
    private var startJob: Job? = null

    // výpis databáze
    @Volatile private var collecting = false
    private val dbdFrames = ArrayList<ByteArray>()
    @Volatile private var lastDbdRxMs = 0L

    private fun dbdFile() = File(context.filesDir, "x20_dbd.ubx")

    /** Rover odpověděl na get info. */
    fun onInfo(fw: String) {
        val ok = fwAtLeast(fw, 0, 11)
        connected = true
        connectedMs = System.currentTimeMillis()
        staAtConnect = null
        lastBackupTryMs = 0
        _state.value = AssistState(fwOk = ok, lastBackupMs = prefs.dbdSavedMs,
            lastBackup = if (prefs.dbdSavedMs > 0) "uloženo ${prefs.dbdFrames} zpráv" else null)
        if (!ok) return
        startJob?.cancel()
        startJob = scope.launch {
            delay(START_WAIT_MS)
            val p = lastPos
            if (prefs.assistIni && (p == null || !p.hasFix)) inject(auto = true)
        }
    }

    fun onDisconnected() {
        connected = false
        startJob?.cancel()
        collecting = false
        _state.value = _state.value.copy(fwOk = null, backupRunning = false)
    }

    fun onPos(p: Pos) {
        lastPos = p
        if (!p.hasFix) return
        val now = System.currentTimeMillis()
        if (now - lastFixSaveMs > 60_000 && p.timeMs != null) {
            lastFixSaveMs = now
            prefs.lastRoverFix = doubleArrayOf(p.lat!!, p.lon!!, p.hEll ?: 0.0, p.timeMs.toDouble())
        }
        // záloha databáze: rover chvíli s fixem, nic neměří, dlouho nezálohováno
        if (connected && _state.value.fwOk == true && prefs.assistDbd && !collecting && !busy() &&
            now - connectedMs > 60_000 && now - lastBackupTryMs > BACKUP_EVERY_MS && now - prefs.dbdSavedMs > BACKUP_EVERY_MS
        ) {
            lastBackupTryMs = now
            backup()
        }
    }

    fun onSta(s: Sta) {
        lastSta = s
        if (staAtConnect == null && s.mgaAccepted != null) staAtConnect = s
        val base = staAtConnect
        _state.value = _state.value.copy(
            ackAccepted = s.mgaAccepted?.let { it - (base?.mgaAccepted ?: 0) },
            ackRejected = s.mgaRejected?.let { it - (base?.mgaRejected ?: 0) },
            ackAiding = s.ackAiding, ubxInOk = s.ubxInOk, ubxInBad = s.ubxInBad,
        )
    }

    /** Rámec UBX třídy MGA z roveru (MGA-ACK, MGA-DBD). */
    fun onUbx(frame: ByteArray) {
        if (frame.size < 8) return
        val id = frame[3].toInt() and 0xFF
        if (id == ID_DBD && collecting) {
            synchronized(dbdFrames) { dbdFrames.add(frame) }
            lastDbdRxMs = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------------ MGA-INI
    /** Pošle čas, polohu a (při studeném startu) uloženou databázi. [auto] = po připojení bez fixu. */
    fun inject(auto: Boolean = false) = scope.launch {
        if (!connected || _state.value.fwOk != true) {
            if (!auto) message("Asistence vyžaduje připojený rover s FW 0.11.0+")
            return@launch
        }
        val f = phoneFix()
        // čas: z polohy GNSS telefonu (přesnost ~0,1 s), jinak hodiny telefonu (síť)
        val gnssTime = f?.gnssMs != null && System.currentTimeMillis() - f.rxMs < 60_000
        val utcNow = if (gnssTime) f!!.gnssMs!! + (System.currentTimeMillis() - f.rxMs) else System.currentTimeMillis()
        val tAccMs = if (gnssTime) 500L else 3000L
        send(iniTime(utcNow, tAccMs))
        val parts = arrayListOf("čas (±${tAccMs / 1000.0} s)")
        // poloha: telefon (do 30 min), jinak poslední poloha roveru
        val pos: DoubleArray?
        val posAccM: Double
        val posFrom: String
        val rf = prefs.lastRoverFix
        if (f != null && System.currentTimeMillis() - f.wallMs < 30 * 60_000L) {
            pos = doubleArrayOf(f.lat, f.lon, f.hEll ?: rf?.get(2) ?: 0.0)
            posAccM = maxOf(f.acc ?: 1000.0, 100.0) + (if (f.hEll == null) 500.0 else 0.0)
            posFrom = "telefon ${f.source.label}"
        } else if (rf != null) {
            pos = doubleArrayOf(rf[0], rf[1], rf[2])
            posAccM = 50_000.0   // rover mohl být odvezen
            posFrom = "poslední poloha roveru"
        } else {
            pos = null
            posAccM = 0.0
            posFrom = ""
        }
        if (pos != null) {
            delay(50)
            send(iniPosLlh(pos[0], pos[1], pos[2], posAccM))
            parts += "poloha – $posFrom (±${formatKm(posAccM)})"
        }
        // databáze: jen když je mladší než 7 dní (almanach); efemeridy platí ~4 h
        var restored = 0
        val file = dbdFile()
        val age = System.currentTimeMillis() - prefs.dbdSavedMs
        if (prefs.assistDbd && prefs.dbdSavedMs > 0 && age < DBD_MAX_AGE_MS && file.exists()) {
            val frames = withContext(Dispatchers.IO) { splitFrames(file.readBytes()) }
            delay(100)
            for (fr in frames) {
                send(fr)
                restored++
                delay(FRAME_PACE_MS)
            }
            parts += "databáze $restored zpráv (stáří ${age / 3_600_000} h" + (if (age > 4 * 3_600_000L) ", efemeridy prošlé)" else ")")
        }
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(
            lastIniMs = now, lastIni = (if (auto) "automaticky: " else "") + parts.joinToString(", "),
            lastRestoreMs = if (restored > 0) now else _state.value.lastRestoreMs, restoredFrames = restored,
        )
        if (!auto) message("X20: odesláno – " + parts.joinToString(", "))
    }

    // ------------------------------------------------------------------ MGA-DBD
    /** Vyžádá výpis databáze a uloží ho, když počet zpráv souhlasí s počítadlem roveru. */
    fun backup() = scope.launch {
        if (!connected || _state.value.fwOk != true || collecting) return@launch
        val before = lastSta?.mgaDbd
        synchronized(dbdFrames) { dbdFrames.clear() }
        collecting = true
        _state.value = _state.value.copy(backupRunning = true)
        val t0 = System.currentTimeMillis()
        lastDbdRxMs = 0
        send(frame(CLS_MGA, ID_DBD, ByteArray(0)))
        // čekat na konec výpisu: 2 s bez nové zprávy (nejdéle 20 s)
        while (true) {
            delay(250)
            val now = System.currentTimeMillis()
            if (lastDbdRxMs > 0 && now - lastDbdRxMs > 2000) break
            if (lastDbdRxMs == 0L && now - t0 > 5000) break
            if (now - t0 > 20_000) break
        }
        collecting = false
        delay(2500)  // nová věta STA s počítadlem MGA-DBD
        val frames = synchronized(dbdFrames) { ArrayList(dbdFrames) }
        val after = lastSta?.mgaDbd
        val expected = if (before != null && after != null) (after - before).toInt() else null
        val result: String
        if (frames.isEmpty()) {
            result = "X20 neposlala databázi (nepodporuje MGA-DBD?)"
        } else if (expected != null && expected != frames.size) {
            result = "neúplný výpis: přijato ${frames.size} z $expected zpráv – ponechána předchozí záloha"
        } else {
            val bytes = ByteArrayOutputStream().also { o -> frames.forEach { o.write(it) } }.toByteArray()
            val ok = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(context.filesDir, "x20_dbd.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(dbdFile())
                } catch (e: Exception) {
                    Log.w(TAG, "záloha DBD: $e")
                    false
                }
            }
            result = if (ok) {
                prefs.dbdSavedMs = System.currentTimeMillis()
                prefs.dbdFrames = frames.size
                "uloženo ${frames.size} zpráv (${bytes.size / 1024} kB)" + if (expected == null) ", úplnost neověřena" else ""
            } else "zápis souboru selhal"
        }
        _state.value = _state.value.copy(backupRunning = false, lastBackupMs = prefs.dbdSavedMs, lastBackup = result)
    }

    fun deleteBackup() {
        dbdFile().delete()
        prefs.dbdSavedMs = 0
        prefs.dbdFrames = 0
        _state.value = _state.value.copy(lastBackupMs = 0, lastBackup = null)
    }

    companion object {
        private const val TAG = "X20Assist"
        const val CLS_MGA = 0x13
        const val ID_INI = 0x40
        const val ID_DBD = 0x80
        private const val START_WAIT_MS = 3000L
        private const val BACKUP_EVERY_MS = 30 * 60_000L
        private const val DBD_MAX_AGE_MS = 7 * 24 * 3_600_000L
        private const val FRAME_PACE_MS = 20L

        fun fwAtLeast(fw: String, major: Int, minor: Int): Boolean {
            val v = fw.split('.').mapNotNull { it.toIntOrNull() }
            val a = v.getOrElse(0) { 0 }
            val b = v.getOrElse(1) { 0 }
            return a > major || (a == major && b >= minor)
        }

        private fun formatKm(m: Double) = if (m >= 1000) "${(m / 1000).toInt()} km" else "${m.toInt()} m"

        /** Rámec UBX: B5 62 třída ID délka data součet. */
        fun frame(cls: Int, id: Int, payload: ByteArray): ByteArray {
            val f = ByteArray(payload.size + 8)
            f[0] = 0xB5.toByte()
            f[1] = 0x62
            f[2] = cls.toByte()
            f[3] = id.toByte()
            f[4] = (payload.size and 0xFF).toByte()
            f[5] = (payload.size shr 8).toByte()
            payload.copyInto(f, 6)
            var a = 0
            var b = 0
            for (i in 2 until f.size - 2) {
                a = (a + (f[i].toInt() and 0xFF)) and 0xFF
                b = (b + a) and 0xFF
            }
            f[f.size - 2] = a.toByte()
            f[f.size - 1] = b.toByte()
            return f
        }

        private fun putU2(p: ByteArray, o: Int, v: Int) {
            p[o] = (v and 0xFF).toByte()
            p[o + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun putU4(p: ByteArray, o: Int, v: Long) {
            for (i in 0 until 4) p[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }

        /** UBX-MGA-INI-TIME_UTC: čas UTC, ref = při příjmu zprávy, přestupné sekundy neznámé. */
        fun iniTime(utcMs: Long, tAccMs: Long): ByteArray {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
            val p = ByteArray(24)
            p[0] = 0x10                 // type TIME_UTC
            p[1] = 0                    // version
            p[2] = 0                    // ref: při příjmu zprávy
            p[3] = (-128).toByte()      // leapSecs neznámé
            putU2(p, 4, c.get(Calendar.YEAR))
            p[6] = (c.get(Calendar.MONTH) + 1).toByte()
            p[7] = c.get(Calendar.DAY_OF_MONTH).toByte()
            p[8] = c.get(Calendar.HOUR_OF_DAY).toByte()
            p[9] = c.get(Calendar.MINUTE).toByte()
            p[10] = c.get(Calendar.SECOND).toByte()
            putU4(p, 12, c.get(Calendar.MILLISECOND) * 1_000_000L)
            putU2(p, 16, (tAccMs / 1000).toInt())
            putU4(p, 20, (tAccMs % 1000) * 1_000_000L)
            return frame(CLS_MGA, ID_INI, p)
        }

        /** UBX-MGA-INI-POS_LLH: [°], výška nad elipsoidem WGS84 [m], přesnost 3D [m]. */
        fun iniPosLlh(lat: Double, lon: Double, h: Double, accM: Double): ByteArray {
            val p = ByteArray(20)
            p[0] = 0x01                 // type POS_LLH
            p[1] = 0
            putU4(p, 4, Math.round(lat * 1e7))
            putU4(p, 8, Math.round(lon * 1e7))
            putU4(p, 12, Math.round(h * 100))
            putU4(p, 16, Math.round(accM * 100).coerceIn(1L, 0xFFFFFFFFL))
            return frame(CLS_MGA, ID_INI, p)
        }

        /** Rozdělí soubor na rámce UBX (uložené zprávy MGA-DBD). */
        fun splitFrames(b: ByteArray): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i + 8 <= b.size) {
                if ((b[i].toInt() and 0xFF) != 0xB5 || (b[i + 1].toInt() and 0xFF) != 0x62) {
                    i++
                    continue
                }
                val len = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8)
                if (i + 8 + len > b.size) break
                out += b.copyOfRange(i, i + 8 + len)
                i += 8 + len
            }
            return out
        }
    }
}
