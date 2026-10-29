package cz.antonin.rovergnss.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Zakázka (projekt): sdružuje trasy, později body a měření. */
@Entity(tableName = "job")
data class Job(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdMs: Long = System.currentTimeMillis(),
    val note: String = "",
)

/** Záznam trasy. */
@Entity(
    tableName = "track",
    foreignKeys = [ForeignKey(entity = Job::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId")],
)
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val name: String,
    val startMs: Long,
    val endMs: Long? = null,
    val points: Int = 0,
    val antennaH: Double = 0.0,   // výška antény nad terénem [m] (odečítá se při exportu)
    val note: String = "",
    val rawFile: String? = null,  // surový záznam proudu z roveru (soubor v files/raw)
    val rateHz: Int = 0,          // frekvence epoch roveru při záznamu (pro výpočet mezer)
)

/**
 * Epocha trasy – souřadnice tak, jak je poslal rover (rámec podle [itrf]).
 * Převod do ETRS89 a S-JTSK až při zobrazení a exportu.
 */
@Entity(
    tableName = "track_point",
    foreignKeys = [ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val timeMs: Long,         // UTC z GNSS
    val lat: Double,
    val lon: Double,
    val hEll: Double,
    val hMsl: Double,
    val sN: Double?,
    val sE: Double?,
    val sU: Double?,
    val hAcc: Double,
    val vAcc: Double,
    val quality: Int,
    val carrSoln: Int,
    val itrf: Boolean,        // true = ITRF2020 (HAS/SBAS bez RTCM), false = ETRS89 (RTK ze sítě v ČR)
    val numSv: Int,
    val pdop: Double,
    val speed: Double,
    val heading: Double,
    val moving: Boolean,
    val corrAge: Double?,     // stáří korekcí [s] (horní mez), null = bez korekcí
    val rtcm: Boolean,        // rover dostával RTCM z mobilu
    val hp: Boolean,          // poloha z NAV-HPPOSLLH
    val rxMs: Long,           // čas příjmu v telefonu (zpoždění, výpadky BT)
)

/**
 * Diagnostika k trase, 1 Hz (při každé větě STA): stav roveru, souhrn satelitů, čítače telefonu.
 * Čítače jsou kumulativní od připojení / spuštění NTRIP.
 */
@Entity(
    tableName = "track_diag",
    foreignKeys = [ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId")],
)
data class TrackDiag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val rxMs: Long,
    val gnssMs: Long?,        // čas poslední epochy polohy (UTC)
    // rover (STA)
    val batV: Double?,
    val profile: Int,
    val rtcmOk: Long,
    val rtcmBad: Long,
    val rtcmAge: Double?,
    val rtcmType: Int?,
    val ubxErr: Long,
    val freeHeap: Long,
    val btDropped: Long,      // dávky zahozené v roveru (telefon nestíhá číst)
    // satelity (SAT)
    val satTracked: Int,
    val satUsed: Int,
    val usedGps: Int,
    val usedGal: Int,
    val usedBds: Int,
    val usedGlo: Int,
    val usedOther: Int,
    val cnoMeanUsed: Double?,
    val cnoMax: Int,
    // telefon
    val btRxBytes: Long,
    val btTxBytes: Long,
    val btTxDropped: Long,
    val btReconnects: Long,
    val badChecksum: Long,    // věty $PRVR se špatným součtem
    val droppedLines: Long,   // useknuté řádky v proudu
    val ntripState: String,
    val ntripBytes: Long,
    val ntripFrames: Long,
    val ntripBadCrc: Long,
    val ntripAge: Double?,    // [s] od posledních dat z casteru
    // spojení a telefon (verze 3)
    val foreground: Boolean,  // aplikace na obrazovce
    val network: String,      // wifi2.4 / wifi5 / wifi / mobil / zadna
    val netRxBytes: Long,     // příjem telefonu ze sítě celkem (všechny aplikace)
    val linkBars: Int,        // kvalita spojení 0–4
    val latencyMs: Long?,     // medián zpoždění epoch
    val lossPct: Double?,     // ztráty epoch za 30 s
    val lostTotal: Long,      // ztracené epochy od připojení
    val btBacklog: Long?,     // nepotvrzená data v roveru [B]
    val hpCount: Long?,       // epochy s HPPOSLLH (rover)
    val appMode: Boolean,     // rover v režimu aplikace
)

/** Kvalita polohy daného bodu (pro porovnání s měřením). */
object PointQuality {
    const val EXACT = 0      // přesná (geodetické údaje)
    const val APPROX = 1     // přibližná
    const val NONE = 2       // nevyhodnocovat
    val NAMES = listOf("přesná", "přibližná", "nevyhodnocovat")
}

/** Původ bodu. */
object PointSource {
    const val ENTERED = 0    // zadaný ručně / import
    const val MEASURED = 1   // změřený roverem
    const val CUZK = 2       // převzatý z bodových polí ČÚZK
    val NAMES = listOf("zadaný", "změřený", "ČÚZK")
}

/**
 * Bod zakázky. Souřadnice ETRS89 i S-JTSK; když je zadaný jen jeden systém, druhý se dopočte
 * a označí příznakem *Derived (převod ČÚZK s opravnou tabulkou a kvazigeoidem, ~1–2 cm).
 */
@Entity(
    tableName = "point",
    foreignKeys = [ForeignKey(entity = Job::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId")],
)
data class Point(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val name: String,
    val kind: String = "",        // druh (ZhB, PPBP, nivelační, …)
    val note: String = "",
    val lat: Double? = null,      // ETRS89 [°]
    val lon: Double? = null,
    val hEll: Double? = null,     // elipsoidická výška [m]
    val etrsDerived: Boolean = false,
    val y: Double? = null,        // S-JTSK [m]
    val x: Double? = null,
    val jtskDerived: Boolean = false,
    val bpv: Double? = null,      // normální výška Bpv [m]
    val quality: Int = PointQuality.EXACT,
    val source: Int = PointSource.ENTERED,
    val url: String? = null,      // geodetické údaje
    val createdMs: Long = System.currentTimeMillis(),
    val control: Boolean = false, // kontrolní (kalibrační) bod – opakovaná měření přijímače
    // vytyčování (verze 7): výběr do seznamu a výsledek – vytyčená poloha a odchylka vytyčeno − dané
    val stakeSel: Boolean = false,
    val stakeMs: Long? = null,       // čas vytyčení, null = nevytyčeno
    val stakeLat: Double? = null,
    val stakeLon: Double? = null,
    val stakeH: Double? = null,      // h elipsoidu (anténa odečtena)
    val stakeDN: Double? = null,
    val stakeDE: Double? = null,
    val stakeDH: Double? = null,     // výška: Bpv, když je dané Bpv, jinak elipsoid
    val stakeDY: Double? = null,
    val stakeDX: Double? = null,
)

/**
 * Měření na bodě (relace): průměr epoch, rozptyl a odchylka od daných souřadnic bodu.
 * Epochy a surový proud jsou v trase [trackId] (záznam „M …“), souhrn zůstává i po smazání trasy.
 * Odchylky = měřeno − dané; ETRS89 v metrech (sever, východ, výška), S-JTSK ΔY/ΔX.
 */
@Entity(
    tableName = "measurement",
    foreignKeys = [
        ForeignKey(entity = Point::class, parentColumns = ["id"], childColumns = ["pointId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("pointId"), Index("trackId")],
)
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointId: Long,
    val trackId: Long?,
    val startMs: Long,
    val endMs: Long? = null,
    val plannedSec: Int = 0,      // plánovaná délka [s], 0 = ukončeno ručně
    val antennaH: Double = 0.0,   // výška antény (odečtena od průměru)
    val epochs: Int = 0,          // epochy s polohou (bez pohybu)
    val used: Int = 0,            // epochy v průměru
    val usedQuality: Int = 0,     // kvalita epoch v průměru (kód Quality)
    val nFix: Int = 0,
    val nFloat: Int = 0,
    val nHas: Int = 0,
    val nDgnss: Int = 0,
    val nOther: Int = 0,
    val corr: String = "",        // korekce: RTCM (mountpoint) / HAS / bez korekcí
    val profile: String = "",
    val lat: Double? = null,      // průměr ETRS89 [°]
    val lon: Double? = null,
    val hEll: Double? = null,
    val sN: Double? = null,       // směrodatná odchylka epoch [m]
    val sE: Double? = null,
    val sU: Double? = null,
    val dN: Double? = null,       // měřeno − dané [m]
    val dE: Double? = null,
    val dU: Double? = null,
    val dY: Double? = null,
    val dX: Double? = null,
    val refDerived: Boolean = false, // dané ETRS89 dopočtené ze S-JTSK (převod ČÚZK)
    val note: String = "",
    // verze 6
    val dH: Double? = null,           // výška Bpv: měřeno (kvazigeoid CR-2005) − dané Bpv [m]
    val staticModel: Boolean = false, // X20 během měření ve statickém modelu (FW 0.9.0+)
    val rawUbx: String? = null,       // surová data RAWX/SFRBX měření (soubor v files/raw)
    // verze 9: měření bodu tlačítkem REC (0.10.0)
    val result: Int = 0,              // PointResult: 0 –, 1 změřen, 2 cíl nesplněn, 3 nejde změřit
    val fixSec: Double? = null,       // doba RTK fix [s]
    val fixPct: Double? = null,       // podíl epoch s fixem [%]
    val sMeanH: Double? = null,       // σ průměru vodorovně [m]
    val sMeanU: Double? = null,       // σ průměru výšky [m]
    val maxDev: Double? = null,       // největší vodorovná odchylka epochy od průměru [m]
    val segments: Int = 0,            // úseky fixu
    val jumps: Int = 0,               // skoky během fixu
    val clusters: Int = 0,            // shluky úseků (> 1 = podezřelý fix)
    val clusterDiffH: Double? = null, // rozdíl shluků vodorovně [m]
    val verify: Int = 0,              // ověření fixu: 0 ne, 1 OK, 2 nesouhlasí
)

/** Porovnání GNSS telefonu s roverem (verze 8). Odsazení telefonu od antény roveru [m]: telefon = anténa + odsazení. */
@Entity(
    tableName = "phone_session",
    foreignKeys = [ForeignKey(entity = Job::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId")],
)
data class PhoneSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val name: String,
    val startMs: Long,
    val endMs: Long? = null,
    val offN: Double = 0.0,
    val offE: Double = 0.0,
    val offU: Double = 0.0,
    val sources: String = "",     // zapnuté zdroje telefonu (např. „GNSS čip, Fused“)
    val samples: Int = 0,
    val note: String = "",
)

/**
 * Jedna poloha telefonu spárovaná s epochou roveru. Souřadnice ETRS89 (telefon přepočten z WGS84 ≈ ITRF2020).
 * Odchylka = telefon − (rover + odsazení). [source] = PhoneSource.code.
 */
@Entity(
    tableName = "phone_sample",
    foreignKeys = [ForeignKey(entity = PhoneSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class PhoneSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val source: Int,
    val timeMs: Long,         // čas polohy telefonu (UTC, hodiny telefonu)
    val lat: Double,
    val lon: Double,
    val hEll: Double?,
    val acc: Double?,         // hlášená přesnost vodorovně (68 %) [m]
    val vAcc: Double?,        // hlášená přesnost svisle [m]
    val ageMs: Long,          // stáří polohy při příjmu [ms]
    val numSv: Int?,          // družice v řešení (jen GNSS čip)
    val dN: Double,
    val dE: Double,
    val dU: Double?,
    val refLat: Double,       // rover ETRS89 (anténa)
    val refLon: Double,
    val refH: Double,
    val refQuality: Int,      // kvalita roveru (Quality.code)
    val refRtcm: Boolean,
)

/** 2D odchylka telefonu od roveru [m] (mimo entitu – Room ji neukládá). */
val PhoneSample.d2: Double get() = kotlin.math.sqrt(dN * dN + dE * dE)
