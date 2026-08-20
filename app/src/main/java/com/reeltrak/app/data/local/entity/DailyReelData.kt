package com.reeltrak.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_reel_data")
data class DailyReelData(
    @PrimaryKey
    val dateString: String = "", // Format: YYYY-MM-DD
    val totalReelsCount: Int = 0,
    val totalTimeSpentMillis: Long = 0L,
    val dailyGoal: Int = 0,
    val isSkullDay: Boolean = false,
    val isStrictModeOn: Boolean = false,
    val isStrictModeLocked: Boolean = false,
    val isLimitLocked: Boolean = false,
    val sessions: List<SessionData> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)
