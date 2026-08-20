package com.reeltrak.app.data.local.entity

data class SessionData(
    val appName: String,
    val startTimeStamp: Long,
    val endTimeStamp: Long,
    val reelsScrolled: Int
)
