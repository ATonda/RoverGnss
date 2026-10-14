package cz.antonin.rovergnss.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import cz.antonin.rovergnss.link.Sat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Zdroj polohy telefonu. [color] = ARGB pro grafy a mapu. */
enum class PhoneSource(val code: Int, val label: String, val color: Long) {
    GNSS(0, "GNSS čip", 0xFFD81B60),
    FUSED(1, "Fused", 0xFF00897B),
    NETWORK(2, "Network", 0xFF8D6E63);

    companion object {
        fun of(code: Int): PhoneSource? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Poloha telefonu (WGS84 ≈ ITRF2020).
 * @param wallMs čas polohy na hodinách telefonu (UTC) – z elapsedRealtime, i u Fused/Network
 * @param gnssMs čas GNSS (UTC) – jen GNSS čip ([Location.getTime] u GPS_PROVIDER)
 * @param elapsedMs čas polohy v SystemClock.elapsedRealtime [ms] (test doby do fixu)
 */
data class PhoneFix(
    val source: PhoneSource,
    val wallMs: Long,
    val gnssMs: Long?,
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    val hEll: Double?,
    val acc: Double?,
    val vAcc: Double?,
    val speed: Double?,
    val bearing: Double?,
    val numSv: Int?,          // družice v řešení (dvoupásmová = 1; jen GNSS čip)
    val numSignals: Int?,     // signály v řešení (dvoupásmová družice = 2)
    val bands: String?,       // pásma družic v řešení (jen GNSS čip), např. „L1+L5“
    val rxMs: Long = System.currentTimeMillis(),
)

/** Stav GNSS telefonu pro diagnostiku. */
data class PhoneInfo(
    val permission: Boolean = false,
    val gpsProvider: Boolean = false,
    val networkProvider: Boolean = false,
    val gms: Boolean = false,             // Google Play služby (Fused)
    val measurements: String = "neověřeno", // surová měření GnssMeasurement
    val hardware: String? = null,         // model GNSS čipu (Android 9+)
    val lastError: String? = null,
)

/**
 * GNSS telefonu: čistá poloha čipu (GPS_PROVIDER), Fused (Google Play služby), Network (Wi-Fi/BTS) a satelity (GnssStatus).
 * Každý zdroj se zapíná zvlášť. Zpětná volání běží v hlavním vlákně.
 */
class PhoneGnss(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var fused: FusedLocationProviderClient? = null

    private val _enabled = MutableStateFlow<Set<PhoneSource>>(emptySet())
    val enabled: StateFlow<Set<PhoneSource>> = _enabled.asStateFlow()

    private val _last = MutableStateFlow<Map<PhoneSource, PhoneFix>>(emptyMap())
    val last: StateFlow<Map<PhoneSource, PhoneFix>> = _last.asStateFlow()

    private val _sats = MutableStateFlow<List<Sat>>(emptyList())
    val sats: StateFlow<List<Sat>> = _sats.asStateFlow()

    private val _info = MutableStateFlow(PhoneInfo())
    val info: StateFlow<PhoneInfo> = _info.asStateFlow()

    /** Nová poloha libovolného zdroje (porovnání, test doby do fixu). */
    @Volatile var onFix: ((PhoneFix) -> Unit)? = null

    // aktuálně zaregistrované posluchače (zdroj může být zapnutý bez oprávnění – pak se nic neregistruje)
    private val active = HashSet<PhoneSource>()
    @Volatile private var usedCount: Int? = null
    @Volatile private var usedSignalCount: Int? = null

    /** Družice v řešení a sledované (různé družice, ne signály) – test doby do fixu, satelity. */
    private val _svCount = MutableStateFlow(0 to 0)
    val svCount: StateFlow<Pair<Int, Int>> = _svCount.asStateFlow()
    @Volatile private var usedBands: String? = null
    private var statusRegistered = false
    private var measRegistered = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun gmsAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Throwable) {
        false
    }

    /** Zapne / vypne zdroj. Bez oprávnění k poloze se jen zapamatuje a spustí po [refresh]. */
    fun setEnabled(src: PhoneSource, on: Boolean) {
        _enabled.value = if (on) _enabled.value + src else _enabled.value - src
        main.post { applyState() }
    }

    fun setAll(set: Set<PhoneSource>) {
        _enabled.value = set
        main.post { applyState() }
    }

    /** Po udělení oprávnění: znovu zaregistrovat zapnuté zdroje. */
    fun refresh() = main.post { applyState() }

    fun stopAll() {
        _enabled.value = emptySet()
        main.post { applyState() }
    }

    /** Dočasně zastaví / znovu spustí jeden zdroj (test doby do fixu) – volat z hlavního vlákna. */
    fun stopSource(src: PhoneSource) {
        unregister(src)
        _last.value = _last.value - src
    }

    fun startSource(src: PhoneSource) {
        if (src in _enabled.value) register(src)
    }

    /** Příkaz čipu přes sendExtraCommand (delete_aiding_data, force_psds_injection, …). null = výjimka. */
    fun extraCommand(cmd: String): Boolean? = try {
        lm.sendExtraCommand(LocationManager.GPS_PROVIDER, cmd, null)
    } catch (e: Exception) {
        Log.w(TAG, "sendExtraCommand $cmd: $e")
        null
    }

    /** Poslední známá poloha telefonu (pro asistenci X20), libovolný zdroj, nejnovější. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): PhoneFix? {
        _last.value.values.maxByOrNull { it.wallMs }?.let { return it }
        if (!hasPermission()) return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { it in lm.allProviders }
                .mapNotNull { p -> lm.getLastKnownLocation(p)?.let { toFix(if (p == LocationManager.GPS_PROVIDER) PhoneSource.GNSS else PhoneSource.NETWORK, it) } }
                .maxByOrNull { it.wallMs }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ registrace
    private fun applyState() {
        val perm = hasPermission()
        val want = if (perm) _enabled.value else emptySet()
        for (s in PhoneSource.entries) {
            if (s in want && s !in active) register(s)
            if (s !in want && s in active) unregister(s)
        }
        val wantStatus = perm && PhoneSource.GNSS in want
        if (wantStatus && !statusRegistered) registerStatus()
        if (!wantStatus && statusRegistered) unregisterStatus()
        if (!wantStatus) _sats.value = emptyList()
        _info.value = _info.value.copy(
            permission = perm,
            gpsProvider = LocationManager.GPS_PROVIDER in lm.allProviders,
            networkProvider = LocationManager.NETWORK_PROVIDER in lm.allProviders,
            gms = gmsAvailable(),
            hardware = if (android.os.Build.VERSION.SDK_INT >= 28) lm.gnssHardwareModelName else null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun register(s: PhoneSource) {
        if (!hasPermission()) return
        try {
            when (s) {
                PhoneSource.GNSS -> {
                    if (LocationManager.GPS_PROVIDER !in lm.allProviders) return fail("telefon nemá GPS_PROVIDER")
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
                }
                PhoneSource.NETWORK -> {
                    if (LocationManager.NETWORK_PROVIDER !in lm.allProviders) return fail("telefon nemá NETWORK_PROVIDER")
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, netListener, Looper.getMainLooper())
                }
                PhoneSource.FUSED -> {
                    if (!gmsAvailable()) return fail("Fused vyžaduje Google Play služby")
                    val c = fused ?: LocationServices.getFusedLocationProviderClient(context).also { fused = it }
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                        .setMinUpdateIntervalMillis(1000L)
                        .build()
                    c.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
                }
            }
            active += s
        } catch (e: Exception) {
            fail("${s.label}: ${e.message}")
        }
    }

    private fun unregister(s: PhoneSource) {
        try {
            when (s) {
                PhoneSource.GNSS -> lm.removeUpdates(gpsListener)
                PhoneSource.NETWORK -> lm.removeUpdates(netListener)
                PhoneSource.FUSED -> fused?.removeLocationUpdates(fusedCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeUpdates ${s.label}: $e")
        }
        active -= s
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _info.value = _info.value.copy(lastError = msg)
    }

    @SuppressLint("MissingPermission")
    private fun registerStatus() {
        try {
            lm.registerGnssStatusCallback(statusCallback, main)
            statusRegistered = true
        } catch (e: Exception) {
            fail("GnssStatus: ${e.message}")
        }
        // jednorázově zjistit, zda telefon dává surová měření (pak se odhlásí)
        if (!measRegistered && _info.value.measurements == "neověřeno") try {
            measRegistered = lm.registerGnssMeasurementsCallback(measCallback, main)
            if (!measRegistered) _info.value = _info.value.copy(measurements = "nepodporováno")
        } catch (e: Exception) {
            _info.value = _info.value.copy(measurements = "chyba: ${e.message}")
        }
    }

    private fun unregisterStatus() {
        try {
            lm.unregisterGnssStatusCallback(statusCallback)
        } catch (_: Exception) {
        }
        statusRegistered = false
        stopMeasCheck()
    }

    private fun stopMeasCheck() {
        if (!measRegistered) return
        try {
            lm.unregisterGnssMeasurementsCallback(measCallback)
        } catch (_: Exception) {
        }
        measRegistered = false
    }

    // ------------------------------------------------------------------ zpětná volání
    private fun toFix(s: PhoneSource, l: Location): PhoneFix {
        val nowEl = SystemClock.elapsedRealtimeNanos()
        val elMs = l.elapsedRealtimeNanos / 1_000_000
        val wall = System.currentTimeMillis() - (nowEl - l.elapsedRealtimeNanos) / 1_000_000
        return PhoneFix(
            source = s,
            wallMs = wall,
            gnssMs = if (s == PhoneSource.GNSS) l.time else null,
            elapsedMs = elMs,
            lat = l.latitude,
            lon = l.longitude,
            hEll = if (l.hasAltitude()) l.altitude else null,
            acc = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
            vAcc = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else null,
            speed = if (l.hasSpeed()) l.speed.toDouble() else null,
            bearing = if (l.hasBearing()) l.bearing.toDouble() else null,
            numSv = if (s == PhoneSource.GNSS) usedCount else null,
            numSignals = if (s == PhoneSource.GNSS) usedSignalCount else null,
            bands = if (s == PhoneSource.GNSS) usedBands else null,
        )
    }

    private fun deliver(s: PhoneSource, l: Location) {
        val f = toFix(s, l)
        _last.value = _last.value + (s to f)
        try {
            onFix?.invoke(f)
        } catch (e: Exception) {
            Log.e(TAG, "onFix: $e")
        }
    }

    // LocationListener s výchozími metodami jen od Androidu 11 – starší systém by volal neimplementované metody
    private open inner class Listener(private val src: PhoneSource) : LocationListener {
        override fun onLocationChanged(location: Location) = deliver(src, location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gpsListener: LocationListener = Listener(PhoneSource.GNSS)
    private val netListener: LocationListener = Listener(PhoneSource.NETWORK)

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { deliver(PhoneSource.FUSED, it) }
        }
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = ArrayList<Sat>(status.satelliteCount)
            val bands = sortedSetOf<String>()
            var used = 0
            val usedSv = HashSet<Long>()
            val trackedSv = HashSet<Long>()
            for (i in 0 until status.satelliteCount) {
                val c = status.getConstellationType(i)
                val band = if (status.hasCarrierFrequencyHz(i)) band(c, status.getCarrierFrequencyHz(i)) else null
                val u = status.usedInFix(i)
                val key = c.toLong() * 10_000 + status.getSvid(i)
                if (status.getCn0DbHz(i) > 0) trackedSv += key
                if (u) {
                    used++
                    usedSv += key
                    band?.let { bands += it }
                }
                list += Sat(
                    gnssId = gnssId(c), svId = status.getSvid(i), cno = status.getCn0DbHz(i).toInt(),
                    elev = status.getElevationDegrees(i).toInt(), azim = status.getAzimuthDegrees(i).toInt(),
                    used = u, band = band,
                )
            }
            usedCount = usedSv.size
            usedSignalCount = used
            _svCount.value = usedSv.size to trackedSv.size
            usedBands = if (bands.isEmpty()) null else bands.joinToString("+")
            _sats.value = list.filter { it.cno > 0 }
        }
    }

    private val measCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            _info.value = _info.value.copy(measurements = "podporováno (${eventArgs.measurements.size} měření)")
            main.post { stopMeasCheck() }
        }

        @Deprecated("Android API")
        override fun onStatusChanged(status: Int) {
            val t = when (status) {
                GnssMeasurementsEvent.Callback.STATUS_READY -> "podporováno"
                GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED -> "nepodporováno"
                GnssMeasurementsEvent.Callback.STATUS_LOCATION_DISABLED -> "poloha vypnutá"
                else -> null
            } ?: return
            if (_info.value.measurements.startsWith("podporováno (")) return
            _info.value = _info.value.copy(measurements = t)
        }
    }

    companion object {
        private const val TAG = "PhoneGnss"

        /** Typ konstelace Androidu → gnssId u-blox (barvy a názvy jako u roveru). */
        fun gnssId(c: Int): Int = when (c) {
            GnssStatus.CONSTELLATION_GPS -> 0
            GnssStatus.CONSTELLATION_SBAS -> 1
            GnssStatus.CONSTELLATION_GALILEO -> 2
            GnssStatus.CONSTELLATION_BEIDOU -> 3
            GnssStatus.CONSTELLATION_QZSS -> 5
            GnssStatus.CONSTELLATION_GLONASS -> 6
            GnssStatus.CONSTELLATION_IRNSS -> 7
            else -> 9
        }

        /** Pásmo z nosné frekvence. */
        fun band(constellation: Int, hz: Float): String {
            val mhz = hz / 1e6
            val gal = constellation == GnssStatus.CONSTELLATION_GALILEO
            val bds = constellation == GnssStatus.CONSTELLATION_BEIDOU
            return when {
                abs(mhz - 1575.42) < 2 -> if (gal) "E1" else if (bds) "B1C" else "L1"
                abs(mhz - 1561.098) < 2 -> "B1I"
                mhz in 1597.0..1607.0 -> "G1"
                abs(mhz - 1176.45) < 2 -> if (gal) "E5a" else if (bds) "B2a" else "L5"
                abs(mhz - 1207.14) < 2 -> if (gal) "E5b" else "B2I"
                abs(mhz - 1227.60) < 2 -> "L2"
                abs(mhz - 1278.75) < 2 -> "E6"
                abs(mhz - 1268.52) < 2 -> "B3I"
                else -> "${mhz.toInt()} MHz"
            }
        }
    }
}
