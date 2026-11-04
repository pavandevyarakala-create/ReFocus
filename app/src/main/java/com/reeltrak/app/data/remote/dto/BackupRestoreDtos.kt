package com.reeltrak.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Snapshot record format exchanged between Android and Cloudflare Worker Phase 1/Phase 2.
 */
data class CloudSnapshotRecordDto(
    @SerializedName("dateString") val dateString: String = "",
    @SerializedName("count") val count: Int = 0,
    @SerializedName("totalReelsCount") val totalReelsCount: Int = count,
    @SerializedName("dailyGoal") val dailyGoal: Int = 0,
    @SerializedName("isSkullDay") val isSkullDay: Boolean = false,
    @SerializedName("isStrictModeOn") val isStrictModeOn: Boolean = true,
    @SerializedName("isStrictModeLocked") val isStrictModeLocked: Boolean = false,
    @SerializedName("isLimitLocked") val isLimitLocked: Boolean = false,
    @SerializedName("totalTimeSpentMillis") val totalTimeSpentMillis: Long = 0L
) {
    val effectiveCount: Int
        get() = if (totalReelsCount > 0) totalReelsCount else count
}

/**
 * Normalizes reel counts when parsing records that may contain both 'count' and 'totalReelsCount'.
 * Positive values always take precedence over stale zeroes. Legitimate zero counts are preserved.
 */
fun normalizeReelCount(rawTotal: Int?, rawCount: Int?): Int {
    return when {
        rawTotal != null && rawTotal > 0 -> rawTotal
        rawCount != null && rawCount > 0 -> rawCount
        rawTotal != null && rawTotal == 0 -> 0
        rawCount != null && rawCount == 0 -> 0
        else -> 0
    }.coerceAtLeast(0)
}

/**
 * Server response from GET /api/backup/status
 */
data class BackupStatusResponse(
    val success: Boolean = false,
    val isRestricted: Boolean = false,
    val backedUpToday: Boolean = false,
    val lastBackupTimeMs: Long = 0L,
    val nextEligibleTimeMs: Long = 0L,
    val serverTimeMs: Long = 0L,
    val nextIstMidnightMs: Long = 0L,
    val activeBackupId: String? = null,
    val totalRecords: Int = 0,
    val error: String? = null
) {
    val hasBackup: Boolean
        get() = !activeBackupId.isNullOrBlank() || lastBackupTimeMs > 0L || totalRecords > 0

    val recordCount: Int
        get() = totalRecords
}

/**
 * Domain outcome for Backup operations
 */
sealed class BackupResult {
    data class Success(
        val backupId: String,
        val lastBackupTimeMs: Long,
        val nextEligibleTimeMs: Long,
        val isIdempotent: Boolean = false
    ) : BackupResult()

    data class Cooldown(
        val nextEligibleTimeMs: Long,
        val message: String = "You can back up your data once every 24 hours."
    ) : BackupResult()

    data class Ineligible(val reason: String) : BackupResult()

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val isNetworkError: Boolean = false
    ) : BackupResult()
}

/**
 * Domain outcome for Restore operations
 */
sealed class RestoreResult {
    data class Success(
        val backupId: String,
        val recordsRestored: Int,
        val restoredDailyLimit: Int? = null,
        val isIdempotent: Boolean = false
    ) : RestoreResult()

    data class InProgress(
        val message: String = "A restore operation is currently in progress for this account."
    ) : RestoreResult()

    data class LimitExceeded(
        val message: String = "You can restore your data once every 24 hours."
    ) : RestoreResult()

    data class AttemptsExhausted(
        val message: String = "Daily restore attempt limit reached (3 failed attempts)."
    ) : RestoreResult()

    data class NotFound(
        val message: String = "No cloud backup was found for this account."
    ) : RestoreResult()

    data class Ineligible(val reason: String) : RestoreResult()

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val isNetworkError: Boolean = false
    ) : RestoreResult()
}
