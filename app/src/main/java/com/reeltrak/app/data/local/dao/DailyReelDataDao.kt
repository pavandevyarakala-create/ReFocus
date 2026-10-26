package com.reeltrak.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.reeltrak.app.data.local.entity.AcceptedReelTransitionState
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import kotlinx.coroutines.flow.Flow

enum class ReelTransitionWriteStatus { COUNTED, ALREADY_ACCEPTED, DAILY_ROW_MISSING }

data class ReelTransitionWriteResult(
    val status: ReelTransitionWriteStatus,
    val dailyData: DailyReelData?
)

@Dao
interface DailyReelDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(data: DailyReelData)

    @Query("SELECT * FROM accepted_reel_transition_state WHERE account_scope = :accountScope AND date_string = :dateString LIMIT 1")
    suspend fun getAcceptedTransitionState(accountScope: String, dateString: String): AcceptedReelTransitionState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAcceptedTransitionState(state: AcceptedReelTransitionState)

    @Query("UPDATE daily_reel_data SET totalReelsCount = totalReelsCount + 1, updatedAt = :acceptedAtMs, syncStatus = 1 WHERE dateString = :dateString")
    suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int

    /** Count and idempotency marker share one Room commit. */
    @Transaction
    suspend fun acceptReelTransition(
        dateString: String,
        accountScope: String,
        fingerprint: SemanticFingerprint,
        acceptedAtMs: Long
    ): ReelTransitionWriteResult {
        val daily = getDailyDataSync(dateString)
            ?: return ReelTransitionWriteResult(ReelTransitionWriteStatus.DAILY_ROW_MISSING, null)
        val prior = getAcceptedTransitionState(accountScope, dateString)
        if (prior?.matches(fingerprint) == true) {
            return ReelTransitionWriteResult(ReelTransitionWriteStatus.ALREADY_ACCEPTED, daily)
        }

        // Update only count-owned columns. Replacing the whole row from this earlier read
        // could overwrite a concurrent settings/session update with a stale snapshot.
        if (incrementDailyReelCount(dateString, acceptedAtMs) == 0) {
            return ReelTransitionWriteResult(ReelTransitionWriteStatus.DAILY_ROW_MISSING, null)
        }
        insertAcceptedTransitionState(
            AcceptedReelTransitionState.from(accountScope, dateString, fingerprint, acceptedAtMs)
        )
        val committed = getDailyDataSync(dateString)
            ?: return ReelTransitionWriteResult(ReelTransitionWriteStatus.DAILY_ROW_MISSING, null)
        return ReelTransitionWriteResult(ReelTransitionWriteStatus.COUNTED, committed)
    }

    @Query("SELECT * FROM daily_reel_data WHERE dateString = :dateString LIMIT 1")
    suspend fun getDailyDataSync(dateString: String): DailyReelData?

    @Query("SELECT * FROM daily_reel_data ORDER BY dateString DESC LIMIT 1")
    suspend fun getLatestDailyDataSync(): DailyReelData?

    @Query("SELECT * FROM daily_reel_data WHERE dateString < :dateString ORDER BY dateString DESC LIMIT 1")
    suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData?

    @Query("SELECT * FROM daily_reel_data WHERE dateString = :dateString LIMIT 1")
    fun getDailyDataFlow(dateString: String): Flow<DailyReelData?>

    @Query("SELECT * FROM daily_reel_data ORDER BY dateString ASC")
    fun getAllDailyDataFlow(): Flow<List<DailyReelData>>

    @Query("SELECT * FROM daily_reel_data WHERE dateString >= :startDate AND dateString <= :endDate ORDER BY dateString ASC")
    fun getDailyDataRangeFlow(startDate: String, endDate: String): Flow<List<DailyReelData>>

    @Query("SELECT * FROM daily_reel_data ORDER BY dateString ASC")
    suspend fun getAllDailyDataSync(): List<DailyReelData>

    @Query("SELECT * FROM daily_reel_data WHERE dateString >= :startDate AND dateString <= :endDate ORDER BY dateString ASC")
    suspend fun getDailyDataRangeSync(startDate: String, endDate: String): List<DailyReelData>

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

    @Query("SELECT * FROM daily_reel_data WHERE syncStatus = 1 AND dateString != :todayStr")
    suspend fun getPendingSyncData(todayStr: String): List<DailyReelData>

    @Query("SELECT * FROM daily_reel_data WHERE dateString != :todayStr ORDER BY dateString ASC")
    suspend fun getHistoricalDailyDataSync(todayStr: String): List<DailyReelData>

    @Query("DELETE FROM daily_reel_data WHERE dateString = :dateStr")
    suspend fun deleteByDate(dateStr: String)

    @Query("DELETE FROM daily_reel_data WHERE syncStatus = 0 AND dateString != :todayStr")
    suspend fun deleteHistoricalSyncedExcept(todayStr: String)

    @Query("UPDATE daily_reel_data SET syncStatus = 0 WHERE dateString = :dateStr AND updatedAt = :syncedTimestamp")
    suspend fun markAsSynced(dateStr: String, syncedTimestamp: Long)
}
