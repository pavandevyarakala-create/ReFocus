package com.reeltrak.app.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo

import com.reeltrak.app.domain.models.StrictModeDailyState

@Entity(tableName = "daily_reel_data", primaryKeys = ["account_scope", "dateString"])
data class DailyReelData(
    val dateString: String = "", // Format: YYYY-MM-DD
    val totalReelsCount: Int = 0,
    val totalTimeSpentMillis: Long = 0L,
    val dailyGoal: Int = 0,
    val isSkullDay: Boolean = false,
    val isStrictModeOn: Boolean = true,
    val isStrictModeLocked: Boolean = false,
    val isLimitLocked: Boolean = false,
    val sessions: List<SessionData> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: Int = 0, // 0 = SYNCED, 1 = PENDING
    @ColumnInfo(name = "account_scope")
    val accountScope: String = ReelCountAccountScope.current
)

val DailyReelData.strictModeDailyState: StrictModeDailyState
    get() = when {
        isStrictModeOn && isStrictModeLocked -> StrictModeDailyState.REACTIVATED_LOCKED
        !isStrictModeOn -> StrictModeDailyState.DISABLED_TODAY
        else -> StrictModeDailyState.ACTIVE_INTERACTIVE
    }

val DailyReelData.isDriftedDay: Boolean
    get() = isSkullDay

val DailyReelData.isUnrestrictedDay: Boolean
    get() = totalReelsCount > 0 && dailyGoal <= 0
