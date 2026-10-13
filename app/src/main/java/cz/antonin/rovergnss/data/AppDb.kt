package cz.antonin.rovergnss.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
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
}

// Verze 5: kontrolní body a měření. Starší databáze se smaže (vývojová fáze).
@Database(
    entities = [Job::class, Track::class, TrackPoint::class, TrackDiag::class, Point::class, Measurement::class],
    version = 5, exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun tracks(): TrackDao
    abstract fun points(): PointDao
    abstract fun measurements(): MeasurementDao

    companion object {
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "rovergnss.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
