package cz.antonin.rovergnss.service

import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.Quality
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dopočet polohy auta z IMU při výpadku GNSS (0.9.0), podle rozboru umělých výpadků 22.–25. 10.
 * (jízdy s RTK, 150 / 146 / 138 výpadků 10 / 30 / 60 s, medián chyby):
 *  - bez IMU 15 / 97 / 278 m, kurz z gyra + držená rychlost 6,9 / 42 / 103 m,
 *  - kurz z gyra + rychlost z akcelerometru prvních 10 s (posun odečtený), pak držet: 5,4 / 38 / 102 m – použito.
 *  - akcelerometr déle než 10 s chybu zvětšuje (sklon), bias gyra ze stání krátké výpadky nezlepšil.
 * Rychlost na začátku = poslední epocha s platnou rychlostí (i DGNSS po ztrátě fixu – krátký tunel 25. 10.: X20
 * hlásí rychlost z dopplera ještě 3 s, podélná chyba by klesla z 25 na ~5 m).
 * Jen ve stavu Jedu (nebo při měření rychlosti) a když chodí IMU (záznam trasy / test / měření rychlosti),
 * nejdéle [MAX_MS]. Výstup v ETRS89 s kvalitou 6 (dopočet) – na mapu a do záznamu, ne do měření bodu.
 */
class DeadReckoner(private val imu: VehicleImu) {
    private var lastFix: Pos? = null          // poslední epocha s fixem (ne dopočet)
    private var lastFixLat = 0.0
    private var lastFixLon = 0.0
    private var lastHeading: Double? = null   // [°] z epochy s rychlostí > 3 m/s
    private var lastSpeed = 0.0               // poslední platná rychlost [m/s]

    private var active = false
    private var startMs = 0.0
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var n = 0.0
    private var e = 0.0
    private var heading = 0.0                 // [rad]
    private var v = 0.0
    private var lastImuMs: Double? = null

    val isActive: Boolean get() = active

    /** Epocha POS z roveru. Vrací dopočtenou polohu, když běží dopočet (jinak null). */
    fun onPos(p: Pos, allowed: Boolean): Pos? {
        if (p.hasFix && p.timeMs != null && !p.deadReckoned) {
            // i DGNSS po ztrátě fixu: rychlost z dopplera ještě platná
            lastFix = p
            val g = if (p.itrf) Etrs.itrfToEtrs(p.lat!!, p.lon!!, p.hEll ?: 0.0, Etrs.epochYear(p.timeMs)) else null
            lastFixLat = g?.lat ?: p.lat!!
            lastFixLon = g?.lon ?: p.lon!!
            if (p.speed > 3.0) lastHeading = p.heading
            lastSpeed = p.speed
            active = false
            return null
        }
        val fix = lastFix ?: return null
        val t0 = fix.timeMs ?: return null
        if (!allowed || !imu.upReady || lastHeading == null) {
            active = false
            return null
        }
        if (!active) {
            active = true
            startMs = t0.toDouble()
            lat0 = lastFixLat; lon0 = lastFixLon
            n = 0.0; e = 0.0
            heading = Math.toRadians(lastHeading!!)
            v = lastSpeed
            // od poslední polohy do teď (první epocha bez fixu přijde až po 0,2–1 s) – držená rychlost a kurz
            val now0 = imu.lastSampleMs
            if (now0 != null && now0 > startMs) {
                val gap = (now0 - startMs) / 1000.0
                n = v * cos(heading) * gap
                e = v * sin(heading) * gap
            }
            lastImuMs = now0
        }
        val now = imu.lastSampleMs ?: return null
        if (now - startMs > MAX_MS) {
            active = false
            return null
        }
        val dLat = Math.toDegrees(n / R)
        val dLon = Math.toDegrees(e / (R * cos(Math.toRadians(lat0))))
        return Pos(
            timeMs = now.toLong(), lat = lat0 + dLat, lon = lon0 + dLon, hEll = fix.hEll, hMsl = fix.hMsl,
            sN = null, sE = null, sU = null,
            hAcc = 5.0 + 0.05 * v * (now - startMs) / 1000.0, vAcc = 99.0,
            quality = Quality.DR, carrSoln = 0, rtcm = fix.rtcm, corrAge = null, numSv = 0, pdop = 99.0,
            speed = v, heading = (Math.toDegrees(heading) % 360 + 360) % 360, moving = true, highPrecision = false,
            deadReckoned = true,
        )
    }

    /** Vzorek IMU – integrace kurzu a polohy během dopočtu. */
    fun onImu(s: ImuSample) {
        if (!active) return
        val t = s.gnssMs ?: return
        val prev = lastImuMs
        lastImuMs = t
        if (prev == null || t <= prev) return
        val dt = (t - prev) / 1000.0
        if (dt > 0.5) return
        val w = imu.yawRate ?: return
        heading += Math.toRadians(w) * dt
        if (t - startMs < ACC_MS) {
            imu.longAcc?.let { a -> v = (v + a * dt).coerceAtLeast(0.0) }  // longAcc už má posun odečtený
        }
        n += v * cos(heading) * dt
        e += v * sin(heading) * dt
    }

    fun reset() {
        active = false
        lastFix = null
        lastHeading = null
    }

    companion object {
        private const val R = 6_371_000.0
        private const val ACC_MS = 10_000.0
        const val MAX_MS = 120_000.0
    }
}
