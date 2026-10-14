package cz.antonin.rovergnss.link

import java.util.Calendar
import java.util.TimeZone

/**
 * Protokol roveru verze 1 (firmware 0.8.0+): věty $PRVR, odpovědi JSON.
 * Popis polí: docs/rover-app-specifikace.md, kap. 5.
 */

/** Kvalita polohy v $PRVR,POS. */
enum class Quality(val code: Int, val label: String) {
    NONE(0, "bez fixu"),
    GNSS(1, "3D"),
    DGNSS(2, "SBAS/DGNSS"),
    HAS(3, "HAS"),
    FIX(4, "RTK fix"),
    FLOAT(5, "RTK float"),
    DR(6, "dopočet");

    companion object {
        fun of(code: Int): Quality = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** Jedna epocha polohy z roveru. Souřadnice v rámci, ve kterém je počítá X20 (viz [itrf]). */
data class Pos(
    val timeMs: Long?,        // UTC [ms], null = čas neplatný
    val lat: Double?,         // [°]
    val lon: Double?,
    val hEll: Double?,        // [m] nad elipsoidem
    val hMsl: Double?,        // [m] nad geoidem u-blox (orientačně)
    val sN: Double?,          // směrodatné odchylky z NAV-COV [m]
    val sE: Double?,
    val sU: Double?,
    val hAcc: Double,         // [m]
    val vAcc: Double,
    val quality: Quality,
    val carrSoln: Int,        // 0 bez fáze, 1 float, 2 fix (u HAS konvergence)
    val rtcm: Boolean,        // rover dostává RTCM z mobilu
    val corrAge: Double?,     // [s] horní mez
    val numSv: Int,
    val pdop: Double,
    val speed: Double,        // [m/s]
    val heading: Double,      // [°]
    val moving: Boolean,      // IMU: přístroj v pohybu
    val highPrecision: Boolean, // poloha z NAV-HPPOSLLH
    val rxMs: Long = System.currentTimeMillis(),
) {
    val hasFix: Boolean get() = quality != Quality.NONE && lat != null && lon != null

    /** Bez RTCM ze sítě je řešení HAS/SBAS v ITRF2020 (nutný přepočet do ETRS89). */
    val itrf: Boolean get() = !rtcm
}

data class Sta(
    val fw: String,
    val batV: Double?,
    val batPct: Int?,
    val profile: Int,
    val profileCount: Int,
    val profileName: String,
    val rtcmOk: Long,
    val rtcmBad: Long,
    val rtcmAge: Double?,
    val rtcmType: Int?,
    val ubxErr: Long,
    val freeHeap: Long,
    val measPhase: Int,       // 0 nic, 1 odpočet, 2 ustálení, 3 sběr, 4 hotovo, 5 selhalo
    val measSeconds: Long,
    val measSamples: Int,
    val btDropped: Long,
    // FW 0.8.1+ (starší firmware: null)
    val btBacklog: Long? = null,  // nepotvrzená data v zásobníku BT roveru [B]
    val appMode: Boolean = false, // rover je v režimu aplikace (NMEA jen GGA, RMC)
    val eoe: Boolean = false,     // X20 posílá konec epochy (HPPOSLLH u každé epochy)
    val hpCount: Long? = null,    // epochy s polohou z HPPOSLLH (od startu roveru)
    // FW 0.11.0+ (asistence X20 z telefonu; starší firmware: null)
    val ubxInOk: Long? = null,    // rámce UBX z mobilu se správným součtem
    val ubxInBad: Long? = null,   // rámce UBX z mobilu vadné
    val mgaAccepted: Long? = null, // UBX-MGA-ACK: přijato
    val mgaRejected: Long? = null, // UBX-MGA-ACK: odmítnuto
    val mgaDbd: Long? = null,     // UBX-MGA-DBD vyslané X20 (výpis databáze)
    val ackAiding: Int? = null,   // CFG-NAVSPG-ACKAIDING: 1 OK, 0 NAK, -1 bez odpovědi
    val rxMs: Long = System.currentTimeMillis(),
)

data class Sat(
    val gnssId: Int,          // 0 GPS, 1 SBAS, 2 Galileo, 3 BeiDou, 5 QZSS, 6 GLONASS, 7 NavIC
    val svId: Int,
    val cno: Int,             // [dBHz]
    val elev: Int,            // [°]
    val azim: Int,            // [°]
    val used: Boolean,
    val band: String? = null, // pásmo (jen satelity telefonu: L1, L5, E1, E5a, …)
) {
    val system: String
        get() = when (gnssId) {
            0 -> "G"; 1 -> "S"; 2 -> "E"; 3 -> "C"; 5 -> "J"; 6 -> "R"; 7 -> "I"; else -> "?"
        }
}

object RoverProtocol {

    /** Kontrolní součet NMEA (XOR mezi $ a *). Vrací tělo věty bez $ a *CS, nebo null. */
    fun checkedBody(line: String): String? {
        val star = line.lastIndexOf('*')
        if (!line.startsWith("$") || star < 0 || star + 3 > line.length) return null
        var cs = 0
        // součet přes bajty UTF-8 (rover posílá názvy profilů s diakritikou)
        for (b in line.substring(1, star).toByteArray(Charsets.UTF_8)) cs = cs xor (b.toInt() and 0xFF)
        val given = line.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
        return if (given == cs) line.substring(1, star) else null
    }

    private fun d(s: String): Double? = if (s.isEmpty()) null else s.toDoubleOrNull()
    private fun i(s: String): Int? = if (s.isEmpty()) null else s.toIntOrNull()
    private fun l(s: String): Long? = if (s.isEmpty()) null else s.toLongOrNull()

    /** hhmmss.sss + ddmmyy -> ms UTC */
    private fun utcMs(time: String, date: String): Long? {
        if (time.length < 6 || date.length != 6) return null
        return try {
            val hh = time.substring(0, 2).toInt()
            val mm = time.substring(2, 4).toInt()
            val ss = time.substring(4, 6).toInt()
            val ms = if (time.length > 7) time.substring(7).padEnd(3, '0').take(3).toInt() else 0
            val dd = date.substring(0, 2).toInt()
            val mo = date.substring(2, 4).toInt()
            val yy = 2000 + date.substring(4, 6).toInt()
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(yy, mo - 1, dd, hh, mm, ss)
            c.timeInMillis + ms
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** $PRVR,POS,čas,datum,lat,lon,hEll,hMSL,σN,σE,σU,hAcc,vAcc,kvalita,carrSoln,rtcm,stáříKor,SV,PDOP,rychlost,kurz,pohyb,hp */
    fun parsePos(f: List<String>): Pos? {
        if (f.size < 23) return null
        return Pos(
            timeMs = utcMs(f[2], f[3]),
            lat = d(f[4]), lon = d(f[5]), hEll = d(f[6]), hMsl = d(f[7]),
            sN = d(f[8]), sE = d(f[9]), sU = d(f[10]),
            hAcc = d(f[11]) ?: 99.0, vAcc = d(f[12]) ?: 99.0,
            quality = Quality.of(i(f[13]) ?: 0),
            carrSoln = i(f[14]) ?: 0,
            rtcm = f[15] == "1",
            corrAge = d(f[16]),
            numSv = i(f[17]) ?: 0,
            pdop = d(f[18]) ?: 99.0,
            speed = d(f[19]) ?: 0.0,
            heading = d(f[20]) ?: 0.0,
            moving = f[21] == "1",
            highPrecision = f[22] == "1",
        )
    }

    /** $PRVR,STA,FW,V,%,profil,profilů,název,rtcmOk,rtcmBad,stáříRtcm,typ,ubxErr,heap,fáze,čas,epochy,zahozeno */
    fun parseSta(f: List<String>): Sta? {
        if (f.size < 18) return null
        return Sta(
            fw = f[2], batV = d(f[3]), batPct = i(f[4]),
            profile = i(f[5]) ?: 0, profileCount = i(f[6]) ?: 0, profileName = f[7],
            rtcmOk = l(f[8]) ?: 0, rtcmBad = l(f[9]) ?: 0, rtcmAge = d(f[10]), rtcmType = i(f[11]),
            ubxErr = l(f[12]) ?: 0, freeHeap = l(f[13]) ?: 0,
            measPhase = i(f[14]) ?: 0, measSeconds = l(f[15]) ?: 0, measSamples = i(f[16]) ?: 0,
            btDropped = l(f[17]) ?: 0,
            btBacklog = f.getOrNull(18)?.let { l(it) },
            appMode = f.getOrNull(19) == "1",
            eoe = f.getOrNull(20) == "1",
            hpCount = f.getOrNull(21)?.let { l(it) },
            ubxInOk = f.getOrNull(22)?.let { l(it) },
            ubxInBad = f.getOrNull(23)?.let { l(it) },
            mgaAccepted = f.getOrNull(24)?.let { l(it) },
            mgaRejected = f.getOrNull(25)?.let { l(it) },
            mgaDbd = f.getOrNull(26)?.let { l(it) },
            ackAiding = f.getOrNull(27)?.let { i(it) },
        )
    }

    data class SatPart(val part: Int, val parts: Int, val total: Int, val sats: List<Sat>)

    /** $PRVR,SAT,část,částí,celkem,{gnssId,svId,cno,elev,azim,used}× */
    fun parseSat(f: List<String>): SatPart? {
        if (f.size < 5) return null
        val part = i(f[2]) ?: return null
        val parts = i(f[3]) ?: return null
        val total = i(f[4]) ?: 0
        val list = ArrayList<Sat>()
        var k = 5
        while (k + 6 <= f.size) {
            list.add(
                Sat(
                    gnssId = i(f[k]) ?: 0, svId = i(f[k + 1]) ?: 0, cno = i(f[k + 2]) ?: 0,
                    elev = i(f[k + 3]) ?: 0, azim = i(f[k + 4]) ?: 0, used = f[k + 5] == "1",
                )
            )
            k += 6
        }
        return SatPart(part, parts, total, list)
    }
}
