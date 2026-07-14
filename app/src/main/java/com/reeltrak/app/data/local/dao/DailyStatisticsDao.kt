package com.reeltrak.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reeltrak.app.data.local.entity.DailyStatisticsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyStatisticsDao {
    @Query("SELECT * FROM daily_statistics WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    suspend fun getStatisticsForDate(dateEpochDay: Long): DailyStatisticsEntity?

    @Query("SELECT * FROM daily_statistics WHERE dateEpochDay = :dateEpochDay LIMIT 1")
    fun getStatisticsForDateFlow(dateEpochDay: Long): Flow<DailyStatisticsEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DailyStatisticsEntity): Long

    @Query("UPDATE daily_statistics SET totalReelsScrolled = totalReelsScrolled + :amount, lastUpdated = :timestamp WHERE dateEpochDay = :dateEpochDay")
    suspend fun incrementCount(dateEpochDay: Long, amount: Int, timestamp: Long)

    @Query("UPDATE daily_statistics SET dailyGoal = :goal WHERE dateEpochDay = :dateEpochDay")
    suspend fun updateGoal(dateEpochDay: Long, goal: Int)

    @Query("UPDATE daily_statistics SET completionPercentage = :percentage, colorLevel = :level WHERE dateEpochDay = :dateEpochDay")
    suspend fun finalizeDay(dateEpochDay: Long, percentage: Float, level: Int)

    @Query("SELECT * FROM daily_statistics WHERE dateEpochDay >= :startDay AND dateEpochDay <= :endDay ORDER BY dateEpochDay ASC")
    fun getStatisticsRangeFlow(startDay: Long, endDay: Long): Flow<List<DailyStatisticsEntity>>
}
