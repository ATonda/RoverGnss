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

    @Query("DELETE FROM track WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertPoints(p: List<TrackPoint>)

    @Query("SELECT * FROM track_point WHERE trackId = :trackId ORDER BY timeMs")
    suspend fun points(trackId: Long): List<TrackPoint>

    @Query("SELECT COUNT(*) FROM track_point WHERE trackId = :trackId")
    suspend fun pointCount(trackId: Long): Int
}

@Database(entities = [Job::class, Track::class, TrackPoint::class], version = 1, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun tracks(): TrackDao

    companion object {
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "rovergnss.db").build()
    }
}
