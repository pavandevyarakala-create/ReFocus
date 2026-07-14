package com.reeltrak.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_statistics")
data class DailyStatisticsEntity(
    @PrimaryKey
    val dateEpochDay: Long,
    val totalReelsScrolled: Int,
    val dailyGoal: Int = 0,
    val completionPercentage: Float = 0f, // NEW
    val colorLevel: Int = 0,              // NEW: 0=Grey, 1=D.Green, 2=M.Green, 3=S.Red, 4=M.Red, 5=DM.Red
    val lastUpdated: Long
)
