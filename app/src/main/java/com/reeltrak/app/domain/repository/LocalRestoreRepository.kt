package com.reeltrak.app.domain.repository

import com.reeltrak.app.data.remote.dto.RestoreResult

interface LocalRestoreRepository {
    suspend fun performRestore(targetBackupId: String? = null): RestoreResult
    suspend fun abortPendingRestore(idempotencyKey: String, backupId: String, reason: String): Boolean
}
