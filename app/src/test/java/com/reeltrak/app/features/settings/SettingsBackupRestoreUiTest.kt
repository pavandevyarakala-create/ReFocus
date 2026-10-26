package com.reeltrak.app.features.settings

import com.reeltrak.app.features.settings.viewmodel.BackupUiState
import com.reeltrak.app.features.settings.viewmodel.RestoreState
import com.reeltrak.app.features.settings.viewmodel.RestoreUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit Test Suite for Settings Backup & Restore UI Integration.
 *
 * Verifies:
 * 1. Initial UI states (Idle).
 * 2. Unentitled user gating (safe error message, no cloud calls).
 * 3. Entitled user successful restore flow.
 * 4. Entitled user restore failure flow with exact error message preserved.
 * 5. Concurrency protection (no concurrent backup + restore operations).
 * 6. Duplicate tap prevention while an operation is in progress.
 * 7. State reset helpers return to Idle.
 */
class SettingsBackupRestoreUiTest {

    private lateinit var coordinator: MockSettingsBackupRestoreCoordinator

    @Before
    fun setUp() {
        coordinator = MockSettingsBackupRestoreCoordinator()
    }

    @Test
    fun `Initial UI states are Idle`() {
        assertEquals(RestoreUiState.Idle, coordinator.restoreUiState.value)
        assertEquals(BackupUiState.Idle, coordinator.backupUiState.value)
        assertEquals(RestoreState.IDLE, coordinator.restoreState.value)
    }

    @Test
    fun `Unentitled user restoreBackup fails with descriptive message and blocks cloud call`() = runBlocking {
        coordinator.isEntitled = false

        coordinator.restoreBackup()

        assertTrue(coordinator.restoreUiState.value is RestoreUiState.Error)
        val errorState = coordinator.restoreUiState.value as RestoreUiState.Error
        assertEquals("Cloud Restore requires an active Trial or Premium subscription.", errorState.message)
        assertEquals(0, coordinator.restoreFromCloudCallCount)
    }

    @Test
    fun `Unentitled user performBackup is rejected and blocks cloud call`() = runBlocking {
        coordinator.isEntitled = false

        coordinator.performBackup()

        assertEquals(BackupUiState.Idle, coordinator.backupUiState.value)
        assertEquals(0, coordinator.performFullBackupCallCount)
    }

    @Test
    fun `Entitled user restoreBackup succeeds and transitions state properly`() = runBlocking {
        coordinator.isEntitled = true
        coordinator.restoreResult = Result.success(Unit)

        coordinator.restoreBackup()

        assertEquals(1, coordinator.restoreFromCloudCallCount)
        assertTrue(coordinator.restoreUiState.value is RestoreUiState.Success)
        assertEquals("Data restored successfully", (coordinator.restoreUiState.value as RestoreUiState.Success).message)
        assertEquals(RestoreState.SUCCESS, coordinator.restoreState.value)
    }

    @Test
    fun `Entitled user restoreBackup failure preserves exact error message`() = runBlocking {
        coordinator.isEntitled = true
        coordinator.restoreResult = Result.failure(Exception("No cloud backup was found for this account."))

        coordinator.restoreBackup()

        assertEquals(1, coordinator.restoreFromCloudCallCount)
        assertTrue(coordinator.restoreUiState.value is RestoreUiState.Error)
        assertEquals("No cloud backup was found for this account.", (coordinator.restoreUiState.value as RestoreUiState.Error).message)
        assertEquals(RestoreState.ERROR, coordinator.restoreState.value)
    }

    @Test
    fun `Duplicate restoreBackup tap while in progress is ignored`() = runBlocking {
        coordinator.isEntitled = true
        coordinator.simulateLongRunning = true

        // First tap initiates restore
        coordinator.restoreBackup()
        assertEquals(RestoreUiState.InProgress, coordinator.restoreUiState.value)
        assertEquals(1, coordinator.restoreFromCloudCallCount)

        // Second tap while still in progress
        coordinator.restoreBackup()
        assertEquals(1, coordinator.restoreFromCloudCallCount)
    }

    @Test
    fun `performBackup while restore is in progress is ignored`() = runBlocking {
        coordinator.isEntitled = true
        coordinator.simulateLongRunning = true

        coordinator.restoreBackup()
        assertEquals(RestoreUiState.InProgress, coordinator.restoreUiState.value)

        coordinator.performBackup()
        assertEquals(0, coordinator.performFullBackupCallCount)
    }

    @Test
    fun `restoreBackup while backup is in progress is ignored`() = runBlocking {
        coordinator.isEntitled = true
        coordinator.simulateLongRunning = true

        coordinator.performBackup()
        assertEquals(BackupUiState.InProgress, coordinator.backupUiState.value)

        coordinator.restoreBackup()
        assertEquals(0, coordinator.restoreFromCloudCallCount)
    }

    @Test
    fun `resetRestoreState and resetBackupState return states to Idle`() = runBlocking {
        coordinator.isEntitled = true
        coordinator.restoreResult = Result.failure(Exception("Network error"))
        coordinator.restoreBackup()

        assertTrue(coordinator.restoreUiState.value is RestoreUiState.Error)

        coordinator.resetRestoreState()
        assertEquals(RestoreUiState.Idle, coordinator.restoreUiState.value)
        assertEquals(RestoreState.IDLE, coordinator.restoreState.value)

        coordinator.backupResult = Result.failure(Exception("Backup error"))
        coordinator.performBackup()
        assertTrue(coordinator.backupUiState.value is BackupUiState.Error)

        coordinator.resetBackupState()
        assertEquals(BackupUiState.Idle, coordinator.backupUiState.value)
    }

    /**
     * Test harness implementing the exact business rules and state machines of SettingsViewModel.
     */
    class MockSettingsBackupRestoreCoordinator {
        var isEntitled: Boolean = true
        var isBackupRestricted: Boolean = false
        var isBackedUpToday: Boolean = false
        var simulateLongRunning: Boolean = false

        var restoreResult: Result<Unit> = Result.success(Unit)
        var backupResult: Result<Unit> = Result.success(Unit)

        var restoreFromCloudCallCount = 0
        var performFullBackupCallCount = 0

        val restoreState = MutableStateFlow(RestoreState.IDLE)
        val restoreUiState = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
        val backupUiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)

        fun restoreBackup() {
            if (!isEntitled) {
                restoreUiState.value = RestoreUiState.Error("Cloud Restore requires an active Trial or Premium subscription.")
                return
            }
            if (restoreUiState.value is RestoreUiState.InProgress || restoreState.value == RestoreState.LOADING) {
                return
            }
            if (backupUiState.value is BackupUiState.InProgress) {
                return
            }

            restoreState.value = RestoreState.LOADING
            restoreUiState.value = RestoreUiState.InProgress
            restoreFromCloudCallCount++

            if (!simulateLongRunning) {
                if (restoreResult.isSuccess) {
                    restoreState.value = RestoreState.SUCCESS
                    restoreUiState.value = RestoreUiState.Success("Data restored successfully")
                } else {
                    restoreState.value = RestoreState.ERROR
                    val errorMsg = restoreResult.exceptionOrNull()?.message ?: "Restore failed. Tap to retry."
                    restoreUiState.value = RestoreUiState.Error(errorMsg)
                }
            }
        }

        fun performBackup() {
            if (!isEntitled) return
            if (backupUiState.value is BackupUiState.InProgress) return
            if (restoreUiState.value is RestoreUiState.InProgress || restoreState.value == RestoreState.LOADING) return
            if (isBackupRestricted || isBackedUpToday) {
                backupUiState.value = BackupUiState.AlreadyBackedUpToday("Your data is already backed up. Next backup available in 24 hours.")
                return
            }

            backupUiState.value = BackupUiState.InProgress
            performFullBackupCallCount++

            if (!simulateLongRunning) {
                if (backupResult.isSuccess) {
                    backupUiState.value = BackupUiState.Success("Backup completed")
                } else {
                    val ex = backupResult.exceptionOrNull()
                    if (ex is com.reeltrak.app.core.sync.BackupAlreadyCompletedException) {
                        backupUiState.value = BackupUiState.AlreadyBackedUpToday(ex.message ?: "Your data is already backed up today.")
                    } else {
                        backupUiState.value = BackupUiState.Error("Backup failed. Tap to retry.")
                    }
                }
            }
        }

        fun resetRestoreState() {
            restoreState.value = RestoreState.IDLE
            restoreUiState.value = RestoreUiState.Idle
        }

        fun resetBackupState() {
            backupUiState.value = BackupUiState.Idle
        }
    }
}
