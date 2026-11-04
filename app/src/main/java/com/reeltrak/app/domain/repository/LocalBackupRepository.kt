package com.reeltrak.app.domain.repository

import com.reeltrak.app.data.remote.dto.BackupResult
import com.reeltrak.app.data.remote.dto.BackupStatusResponse
import kotlinx.coroutines.flow.StateFlow

interface LocalBackupRepository {
    val isBackupRestricted: StateFlow<Boolean>
    val lastBackupServerTimeMs: StateFlow<Long>
    val nextEligibleBackupTimeMs: StateFlow<Long>

    suspend fun checkServerBackupStatus(force: Boolean = false): BackupStatusResponse
    suspend fun performBackup(): BackupResult
}
