package com.reeltrak.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reeltrak.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Query("SELECT * FROM scroll_sessions ORDER BY startTimeStamp DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>
    
    @Query("SELECT MIN(startTimeStamp) FROM scroll_sessions")
    fun getEarliestSessionTime(): Flow<Long?>

    @Query("SELECT SUM(reelsScrolled) FROM scroll_sessions WHERE startTimeStamp >= :startOfDay")
    fun getReelsScrolledToday(startOfDay: Long): Flow<Int?>

    @Query("SELECT SUM(reelsScrolled) FROM scroll_sessions WHERE startTimeStamp >= :startOfDay")
    suspend fun getReelsScrolledTodaySync(startOfDay: Long): Int?
}
