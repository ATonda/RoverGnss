package cz.antonin.rovergnss.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Vzorek IMU z roveru: čas GNSS (UTC ms, null = čas neznámý), zrychlení [g], gyro [°/s] (bias odečtený roverem). */
data class ImuSample(
    val gnssMs: Double?,
    val ax: Double, val ay: Double, val az: Double,
    val gx: Double, val gy: Double, val gz: Double,
)

/**
 * Rámce IMU z roveru (UBX třída 0xF5, ID 0x01, formát `imustream.h` ve FW): čas esp_timer prvního vzorku,
 * iTOW a čas příjmu poslední NAV-PVT, vzorky dt [100 µs], zrychlení [mg], gyro [0,02 °/s].
 *
 * Čas GNSS vzorku = iTOW + (t − čas příjmu NAV-PVT) − 30 ms (zpoždění NAV-PVT za epochou, změřeno z PPS
 * 22. a 23. 10.: 28–35 ms). iTOW je čas týdne GPS – na UTC se převádí podle času z POS (GPS − UTC = 18 s).
 */
object ImuFrames {
    const val CLS = 0xF5
    const val ID = 0x01
    private const val PVT_LATENCY_MS = 30.0

    private const val GPS_EPOCH_MS = 315_964_800_000L   // 6. 1. 1980 UTC
    private const val WEEK_MS = 604_800_000L
    private const val LEAP_MS = 18_000L                 // GPS − UTC (od 2017)

    /** Rozdíl UTC − čas týdne GPS [ms] (začátek týdne GPS v UTC), z poslední epochy POS. */
    @Volatile var utcMinusTow: Double? = null

    /** Nastaví převod času týdne GPS na UTC podle času epochy POS (UTC ms). */
    fun syncFromUtc(utcMs: Long) {
        val tow = Math.floorMod(utcMs + LEAP_MS - GPS_EPOCH_MS, WEEK_MS)
        utcMinusTow = (utcMs - tow).toDouble()
    }

    /** Rozebere celý rámec UBX (včetně 0xB5 0x62 a součtu). Null = není rámec IMU nebo je poškozený. */
    fun parse(frame: ByteArray): List<ImuSample>? {
        if (frame.size < 8 + 48) return null
        if ((frame[2].toInt() and 0xFF) != CLS || (frame[3].toInt() and 0xFF) != ID) return null
        val b = ByteBuffer.wrap(frame, 6, frame.size - 8).order(ByteOrder.LITTLE_ENDIAN)
        val ver = b.get().toInt() and 0xFF
        val n = b.get().toInt() and 0xFF
        b.get()  // decimace
        b.get()  // příznaky
        val t0 = b.long
        val itow = b.int.toLong() and 0xFFFFFFFFL
        val pvtRx = b.long
        b.long  // PPS
        b.float; b.float; b.float  // bias
        if (ver >= 2) b.float      // teplota
        val off = utcMinusTow
        val out = ArrayList<ImuSample>(n)
        for (k in 0 until n) {
            if (b.remaining() < 14) break
            val dt = b.short.toInt() and 0xFFFF
            val ax = b.short / 1000.0
            val ay = b.short / 1000.0
            val az = b.short / 1000.0
            val gx = b.short * 0.02
            val gy = b.short * 0.02
            val gz = b.short * 0.02
            val tUs = t0 + dt * 100L
            val tow = itow + (tUs - pvtRx) / 1000.0 - PVT_LATENCY_MS
            out.add(ImuSample(off?.let { tow + it }, ax, ay, az, gx, gy, gz))
        }
        return out
    }
}
