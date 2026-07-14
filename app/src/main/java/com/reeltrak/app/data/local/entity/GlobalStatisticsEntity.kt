package com.reeltrak.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "global_statistics")
data class GlobalStatisticsEntity(
    @PrimaryKey
    val id: Int = 0,
    val lifetimeReels: Long = 0,
    val completedDays: Int = 0
)
