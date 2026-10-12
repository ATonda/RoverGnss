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
)
