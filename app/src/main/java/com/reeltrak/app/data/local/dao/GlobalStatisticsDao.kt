package com.reeltrak.app.data.local.dao

import androidx.room.*
import com.reeltrak.app.data.local.entity.GlobalStatisticsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlobalStatisticsDao {
    @Query("SELECT * FROM global_statistics WHERE id = 0")
    fun getGlobalStatistics(): Flow<GlobalStatisticsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(entity: GlobalStatisticsEntity)

    @Transaction
    suspend fun incrementCompletedDay(todayReels: Int) {
        val current = getGlobalStatisticsSync() ?: GlobalStatisticsEntity()
        val next = current.copy(
            lifetimeReels = current.lifetimeReels + todayReels,
            completedDays = current.completedDays + 1
        )
        update(next)
    }

    @Query("SELECT * FROM global_statistics WHERE id = 0")
    suspend fun getGlobalStatisticsSync(): GlobalStatisticsEntity?
}
