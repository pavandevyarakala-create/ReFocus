package com.reeltrak.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.compose.runtime.Immutable

@Immutable
@Entity(tableName = "scroll_sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val appName: String,
    val startTimeStamp: Long,
    val endTimeStamp: Long,
    val reelsScrolled: Int
)
