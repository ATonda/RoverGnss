package cz.antonin.rovergnss.service

import cz.antonin.rovergnss.link.Pos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * IMU roveru v autě (0.9.0): orientace krabičky na palubní desce, podélné a příčné zrychlení, rychlost otáčení.
 * Rover leží pokaždé jinak, orientace se určuje automaticky z jízdy (stejně jako v rozboru 22.–25. 10.):
 *  - svislice = průměr zrychlení za jízdy (rychlost GNSS > 2 m/s),
 *  - směr jízdy = regrese vodorovného zrychlení proti změně rychlosti z GNSS (rozjezdy a brzdění),
 *  - posun podélného zrychlení (sklon silnice + bias) = průměr rozdílu IMU − GNSS za posledních ~30 s.
 * Do určení směru jízdy je k dispozici jen svislice a rychlost otáčení, podélné zrychlení se bere z GNSS.
 */
class VehicleImu {
    private val up = DoubleArray(3)
    private var upN = 0
    private val sAcc = DoubleArray(3)          // Σ dv · f_vodorovně
    private var sN = 0
    private var fwd: DoubleArray? = null

    // průměr vodorovného zrychlení mezi epochami POS (pro regresi a posun)
    private val win = DoubleArray(3)
    private var winN = 0
    private var lastPos: Pos? = null
    private var offset = 0.0                   // [m/s²] IMU − GNSS podélně (EMA ~30 s)
    private var offsetOk = false

    /** Poslední hodnoty (vyhlazené ~0,1 s). */
    @Volatile var longAcc: Double? = null      // [m/s²] + zrychlení, − brzdění
        private set
    @Volatile var latAcc: Double? = null       // [m/s²] + doprava
        private set
    @Volatile var yawRate: Double? = null      // [°/s] + doprava (ve směru hodinových ručiček)
        private set
    @Volatile var lastSampleMs: Double? = null
        private set

    val calibrated: Boolean get() = fwd != null && upN >= 200
    val upReady: Boolean get() = upN >= 200

    private var lpLong = 0.0
    private var lpYaw = 0.0
    private var speedNow = 0.0

    fun reset() {
        up.fill(0.0); upN = 0; sAcc.fill(0.0); sN = 0; fwd = null
        win.fill(0.0); winN = 0; lastPos = null; offset = 0.0; offsetOk = false
        longAcc = null; latAcc = null; yawRate = null; lpLong = 0.0; lpYaw = 0.0
    }

    /** Vzorek IMU (zrychlení [g], gyro [°/s]). */
    fun onImu(s: ImuSample) {
        val f = doubleArrayOf(s.ax * G, s.ay * G, s.az * G)
        if (speedNow > 2.0) {
            for (i in 0..2) up[i] += f[i]
            upN++
        }
        if (!upReady) return
        val u = unit(up)
        val fv = dot(f, u)
        val fh = doubleArrayOf(f[0] - fv * u[0], f[1] - fv * u[1], f[2] - fv * u[2])
        for (i in 0..2) win[i] += fh[i]
        winN++
        // rychlost otáčení kolem svislice: kladná = doprava (kurz roste)
        val w = -(s.gx * u[0] + s.gy * u[1] + s.gz * u[2])
        lpYaw += 0.2 * (w - lpYaw)
        yawRate = lpYaw
        lastSampleMs = s.gnssMs
        val fw = fwd ?: return
        val a = dot(fh, fw) - (if (offsetOk) offset else 0.0)
        lpLong += 0.2 * (a - lpLong)
        longAcc = lpLong
        // příčně z rychlosti a otáčení (bez vlivu náklonu krabičky): a = v · ω
        latAcc = speedNow * Math.toRadians(lpYaw)
    }

    /** Epocha POS s fixem (rychlost, čas). */
    fun onPos(p: Pos) {
        if (!p.hasFix || p.deadReckoned) return
        val t = p.timeMs ?: return
        speedNow = p.speed
        val prev = lastPos
        lastPos = p
        if (prev?.timeMs == null || winN == 0) {
            win.fill(0.0); winN = 0
            return
        }
        val dt = (t - prev.timeMs) / 1000.0
        if (dt <= 0 || dt > 0.6) {
            win.fill(0.0); winN = 0
            return
        }
        val dv = (p.speed - prev.speed) / dt
        val fh = doubleArrayOf(win[0] / winN, win[1] / winN, win[2] / winN)
        win.fill(0.0); winN = 0
        if (p.speed > 1.0 && prev.speed > 1.0 || abs(dv) > 0.3) {
            for (i in 0..2) sAcc[i] += dv * fh[i]
            if (abs(dv) > 0.5) sN++
            if (sN >= MIN_REG) {
                val u = unit(up)
                val v = sAcc.copyOf()
                val k = dot(v, u)
                for (i in 0..2) v[i] -= k * u[i]
                if (norm(v) > 1e-6) fwd = unit(v)
            }
        }
        val fw = fwd ?: return
        if (p.speed > 1.0) {
            val e = dot(fh, fw) - dv
            if (!offsetOk) {
                offset = e; offsetOk = true
            } else offset += OFFSET_K * (e - offset)
        }
    }

    /** Podélné zrychlení bez posunu (pro dopočet: posun se odečítá zvlášť). */
    fun currentOffset(): Double = if (offsetOk) offset else 0.0

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
    private fun unit(a: DoubleArray): DoubleArray {
        val n = norm(a).takeIf { it > 0 } ?: 1.0
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    companion object {
        const val G = 9.80665
        private const val MIN_REG = 20       // epoch se změnou rychlosti > 0,5 m/s² pro určení směru jízdy
        private const val OFFSET_K = 0.007   // EMA posunu ~150 epoch (30 s při 5 Hz, 15 s při 10 Hz)
    }
}
