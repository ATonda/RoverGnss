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
)
