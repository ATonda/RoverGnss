package cz.antonin.rovergnss.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert
    suspend fun insert(j: Job): Long

    @Query("SELECT * FROM job ORDER BY createdMs DESC")
    fun all(): Flow<List<Job>>

    @Query("SELECT * FROM job ORDER BY createdMs LIMIT 1")
    suspend fun first(): Job?

    @Query("SELECT * FROM job WHERE id = :id")
    suspend fun get(id: Long): Job?

    @Update
    suspend fun update(j: Job)

    @Query("DELETE FROM job WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PointDao {
    @Insert
    suspend fun insert(p: Point): Long

    @Insert
    suspend fun insertAll(p: List<Point>)

    @Update
    suspend fun update(p: Point)

    @Query("DELETE FROM point WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM point WHERE jobId = :jobId ORDER BY name")
    fun byJob(jobId: Long): Flow<List<Point>>

    @Query("SELECT * FROM point WHERE jobId = :jobId ORDER BY name")
    suspend fun listByJob(jobId: Long): List<Point>

    @Query("SELECT * FROM point WHERE id = :id")
    suspend fun get(id: Long): Point?
}

@Dao
interface TrackDao {
    @Insert
    suspend fun insert(t: Track): Long

    @Update
    suspend fun update(t: Track)

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun get(id: Long): Track?

    @Query("SELECT * FROM track ORDER BY startMs DESC")
    fun all(): Flow<List<Track>>

    @Query("SELECT * FROM track WHERE jobId = :jobId ORDER BY startMs DESC")
    fun byJob(jobId: Long): Flow<List<Track>>

    @Query("SELECT * FROM track WHERE jobId = :jobId")
    suspend fun listByJob(jobId: Long): List<Track>

    @Query("DELETE FROM track WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertPoints(p: List<TrackPoint>)

    @Query("SELECT * FROM track_point WHERE trackId = :trackId ORDER BY timeMs")
    suspend fun points(trackId: Long): List<TrackPoint>

    @Query("SELECT COUNT(*) FROM track_point WHERE trackId = :trackId")
    suspend fun pointCount(trackId: Long): Int

    @Insert
    suspend fun insertDiag(d: List<TrackDiag>)

    @Query("SELECT * FROM track_diag WHERE trackId = :trackId ORDER BY rxMs")
    suspend fun diag(trackId: Long): List<TrackDiag>
}

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(m: Measurement): Long

    @Update
    suspend fun update(m: Measurement)

    @Query("SELECT * FROM measurement WHERE id = :id")
    suspend fun get(id: Long): Measurement?

    @Query("DELETE FROM measurement WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM measurement WHERE pointId = :pointId ORDER BY startMs")
    fun byPoint(pointId: Long): Flow<List<Measurement>>

    @Query("SELECT * FROM measurement WHERE pointId = :pointId ORDER BY startMs")
    suspend fun byPointList(pointId: Long): List<Measurement>

    @Query("SELECT m.* FROM measurement m JOIN point p ON p.id = m.pointId WHERE p.jobId = :jobId ORDER BY p.name, m.startMs")
    suspend fun listByJob(jobId: Long): List<Measurement>

    @Query("SELECT m.* FROM measurement m JOIN point p ON p.id = m.pointId WHERE p.jobId = :jobId ORDER BY m.startMs")
    fun byJob(jobId: Long): Flow<List<Measurement>>
}

@Dao
interface PhoneDao {
    @Insert
    suspend fun insertSession(s: PhoneSession): Long

    @Update
    suspend fun updateSession(s: PhoneSession)

    @Query("SELECT * FROM phone_session WHERE id = :id")
    suspend fun session(id: Long): PhoneSession?

    @Query("SELECT * FROM phone_session WHERE jobId = :jobId ORDER BY startMs DESC")
    fun sessionsByJob(jobId: Long): Flow<List<PhoneSession>>

    @Query("DELETE FROM phone_session WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Insert
    suspend fun insertSamples(s: List<PhoneSample>)

    @Query("SELECT * FROM phone_sample WHERE sessionId = :sessionId ORDER BY timeMs, source")
    suspend fun samples(sessionId: Long): List<PhoneSample>
}

/** 5 → 6: u měření výška Bpv, statický model a surová data – data zůstávají. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE measurement ADD COLUMN dH REAL")
        db.execSQL("ALTER TABLE measurement ADD COLUMN staticModel INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE measurement ADD COLUMN rawUbx TEXT")
    }
}

/** 6 → 7: u bodů vytyčování – data zůstávají. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE point ADD COLUMN stakeSel INTEGER NOT NULL DEFAULT 0")
        for (c in listOf("stakeMs INTEGER", "stakeLat REAL", "stakeLon REAL", "stakeH REAL", "stakeDN REAL", "stakeDE REAL",
            "stakeDH REAL", "stakeDY REAL", "stakeDX REAL")) {
            db.execSQL("ALTER TABLE point ADD COLUMN $c")
        }
    }
}

/** 7 → 8: porovnání GNSS telefonu s roverem – data zůstávají. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `phone_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jobId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER, `offN` REAL NOT NULL, `offE` REAL NOT NULL, " +
                "`offU` REAL NOT NULL, `sources` TEXT NOT NULL, `samples` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                "FOREIGN KEY(`jobId`) REFERENCES `job`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_phone_session_jobId` ON `phone_session` (`jobId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `phone_sample` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`source` INTEGER NOT NULL, `timeMs` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `hEll` REAL, " +
                "`acc` REAL, `vAcc` REAL, `ageMs` INTEGER NOT NULL, `numSv` INTEGER, `dN` REAL NOT NULL, `dE` REAL NOT NULL, " +
                "`dU` REAL, `refLat` REAL NOT NULL, `refLon` REAL NOT NULL, `refH` REAL NOT NULL, `refQuality` INTEGER NOT NULL, " +
                "`refRtcm` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `phone_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_phone_sample_sessionId` ON `phone_sample` (`sessionId`)")
    }
}

// Verze 8. Od verze 5 se převádí (MIGRATION_5_6 … MIGRATION_7_8), starší databáze se smaže.
@Database(
    entities = [Job::class, Track::class, TrackPoint::class, TrackDiag::class, Point::class, Measurement::class,
        PhoneSession::class, PhoneSample::class],
    version = 8, exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun tracks(): TrackDao
    abstract fun points(): PointDao
    abstract fun measurements(): MeasurementDao
    abstract fun phone(): PhoneDao

    companion object {
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "rovergnss.db")
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
    }
}
