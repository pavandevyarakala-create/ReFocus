package com.reeltrak.app.data.remote.dto

/**
 * A lightweight Data Transfer Object (DTO) used exclusively for uploading and downloading
 * data to/from Firestore. It strips away heavy local elements like session tracking arrays
 * to ensure we only sync the absolute minimum raw data required to calculate the UI.
 */
data class CloudDailyReelData(
    val dateString: String = "",
    val totalReelsCount: Int = 0,
    val dailyGoal: Int = 0,
    val isStrictModeOn: Boolean = false,
    val isStrictModeLocked: Boolean = false,
    val isLimitLocked: Boolean = false
)
