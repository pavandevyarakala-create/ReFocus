package com.reeltrak.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reeltrak.app.data.local.entity.DailyReelData
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyReelDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(data: DailyReelData)

    @Query("SELECT * FROM daily_reel_data WHERE dateString = :dateString LIMIT 1")
    suspend fun getDailyDataSync(dateString: String): DailyReelData?

    @Query("SELECT * FROM daily_reel_data ORDER BY dateString DESC LIMIT 1")
    suspend fun getLatestDailyDataSync(): DailyReelData?

    @Query("SELECT * FROM daily_reel_data WHERE dateString = :dateString LIMIT 1")
    fun getDailyDataFlow(dateString: String): Flow<DailyReelData?>

    @Query("SELECT * FROM daily_reel_data ORDER BY dateString ASC")
    fun getAllDailyDataFlow(): Flow<List<DailyReelData>>

    @Query("SELECT * FROM daily_reel_data ORDER BY dateString ASC")
    suspend fun getAllDailyDataSync(): List<DailyReelData>

    @Query("DELETE FROM daily_reel_data")
    suspend fun clearAll()

    @Query("DELETE FROM daily_reel_data WHERE dateString != :today")
    suspend fun clearHistoricalDataExcept(today: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<DailyReelData>)

    @androidx.room.Transaction
    suspend fun wipeAndInsertAll(data: List<DailyReelData>) {
        clearAll()
        insertAll(data)
    }
}
