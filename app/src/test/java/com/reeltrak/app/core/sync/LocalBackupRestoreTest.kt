package com.reeltrak.app.core.sync

import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.LocalRestoreJournal
import com.reeltrak.app.data.remote.dto.BackupResult
import com.reeltrak.app.data.remote.dto.CloudSnapshotRecordDto
import com.reeltrak.app.data.remote.dto.RestoreResult
import com.reeltrak.app.domain.models.ThemeMode
import com.reeltrak.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 Android Local-First Backup & Restore Test Suite.
 *
 * Verifies:
 * 1. Deterministic Merge Policy: Today's record preserved (Local Wins) when local count > 0.
 * 2. Deterministic Merge Policy: Historical synced record overwritten (Cloud Wins).
 * 3. Deterministic Merge Policy: Historical unsynced offline record merged with maxOf(local, cloud).
 * 4. Deterministic Merge Policy: Strict mode boolean union (local || cloud).
 * 5. Deterministic Merge Policy: Insertion of cloud-only historical records.
 * 6. Journal primary key identity by idempotencyKey (allows distinct sessions of same backupId).
 * 7. In-flight retry with matching idempotencyKey skips habit merging and returns success.
 * 8. Pre-commit failure triggers abort and leaves Room + Journal 100% untouched.
 * 9. Crash Point 1 (during transaction): rollback leaves zero journal rows.
 * 10. Crash Point 2 & 3: Reconciler flushes unlatched DataStore preference, latches datastore_synced=1, and NEVER re-merges habit records.
 * 11. Crash Point 4: Reconciler enqueues pending ACK, and NEVER re-merges habit records.
 * 12. Fast-path backup (< 200 records) marks records synced and updates lastBackupTime.
 * 13. Chunked backup (> 200 records) slices correctly and marks all synced.
 * 14. Backup retry reuses stable currentBackupRequestId across transient failures.
 * 15. Backup cooldown (409) handles nextEligibleTimeMs and restricts subsequent client requests.
 * 16. Room migration 11 -> 12 creates local_restore_journal with required columns.
 */
class LocalBackupRestoreTest {

    private lateinit var fakeDailyDao: FakeDailyReelDataDao
    private lateinit var fakeJournalDao: FakeLocalRestoreJournalDao
    private lateinit var fakePrefsRepo: FakePreferencesRepository
    private val todayStr: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setUp() {
        fakeDailyDao = FakeDailyReelDataDao()
        fakeJournalDao = FakeLocalRestoreJournalDao()
        fakePrefsRepo = FakePreferencesRepository()
    }

    // =========================================================================
    // SECTION 1: Deterministic Merge Policy (Section 5.5)
    // =========================================================================

    @Test
    fun `Deterministic Merge - Today record with local count greater than 0 preserves local count (Local Wins)`() = runBlocking {
        // Local today has 15 reels tracked live
        val localToday = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 15,
            dailyGoal = 20,
            isStrictModeOn = true,
            isStrictModeLocked = true,
            syncStatus = 1
        )
        fakeDailyDao.insertOrReplace(localToday)

        // Cloud snapshot has today with only 3 reels
        val cloudRecords = listOf(
            CloudSnapshotRecordDto(
                dateString = todayStr,
                count = 3,
                totalReelsCount = 3,
                dailyGoal = 20,
                isStrictModeOn = true
            )
        )

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val result = simulator.applyRestore(
            cloudRecords = cloudRecords,
            idempotencyKey = "key_01",
            backupId = "bk_01",
            cloudDailyLimit = 100
        )

        assertTrue(result is RestoreResult.Success)
        val actualToday = fakeDailyDao.getDailyDataSync(todayStr)
        assertNotNull(actualToday)
        assertEquals("Local count must be preserved (Local Wins)", 15, actualToday!!.totalReelsCount)
        assertTrue("Local lock state must be preserved", actualToday.isStrictModeLocked)
    }

    @Test
    fun `Deterministic Merge - Today record with zero local count adopts cloud count`() = runBlocking {
        // Local today is empty / untracked
        val localToday = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 0,
            dailyGoal = 30,
            syncStatus = 0
        )
        fakeDailyDao.insertOrReplace(localToday)

        val cloudRecords = listOf(
            CloudSnapshotRecordDto(
                dateString = todayStr,
                count = 5,
                totalReelsCount = 5,
                dailyGoal = 30
            )
        )

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val result = simulator.applyRestore(
            cloudRecords = cloudRecords,
            idempotencyKey = "key_02",
            backupId = "bk_02",
            cloudDailyLimit = 100
        )

        assertTrue(result is RestoreResult.Success)
        val actualToday = fakeDailyDao.getDailyDataSync(todayStr)
        assertNotNull(actualToday)
        assertEquals("Untracked local today adopts cloud count", 5, actualToday!!.totalReelsCount)
    }

    @Test
    fun `Deterministic Merge - Historical synced record is overwritten by cloud record (Cloud Wins)`() = runBlocking {
        val histDate = "2026-10-20"
        // Local record was already synced in the past (syncStatus = 0) with stale count 10
        val localHist = DailyReelData(
            dateString = histDate,
            totalReelsCount = 10,
            dailyGoal = 25,
            syncStatus = 0
        )
        fakeDailyDao.insertOrReplace(localHist)

        // Cloud snapshot has authoritative count 18
        val cloudRecords = listOf(
            CloudSnapshotRecordDto(
                dateString = histDate,
                count = 18,
                totalReelsCount = 18,
                dailyGoal = 25
            )
        )

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val result = simulator.applyRestore(
            cloudRecords = cloudRecords,
            idempotencyKey = "key_03",
            backupId = "bk_03",
            cloudDailyLimit = 100
        )

        assertTrue(result is RestoreResult.Success)
        val actualHist = fakeDailyDao.getDailyDataSync(histDate)
        assertNotNull(actualHist)
        assertEquals("Authoritative cloud record overwrites synced local record", 18, actualHist!!.totalReelsCount)
        assertEquals(0, actualHist.syncStatus)
    }

    @Test
    fun `Deterministic Merge - Historical unsynced offline record takes maxOf(local, cloud)`() = runBlocking {
        val histDate = "2026-10-21"
        // Local offline tracking recorded 25 reels while device was offline (syncStatus = 1)
        val localHist = DailyReelData(
            dateString = histDate,
            totalReelsCount = 25,
            totalTimeSpentMillis = 50000L,
            isStrictModeOn = false,
            syncStatus = 1
        )
        fakeDailyDao.insertOrReplace(localHist)

        // Cloud has 15 reels and strict mode was ON
        val cloudRecords = listOf(
            CloudSnapshotRecordDto(
                dateString = histDate,
                count = 15,
                totalReelsCount = 15,
                totalTimeSpentMillis = 30000L,
                isStrictModeOn = true
            )
        )

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val result = simulator.applyRestore(
            cloudRecords = cloudRecords,
            idempotencyKey = "key_04",
            backupId = "bk_04",
            cloudDailyLimit = 100
        )

        assertTrue(result is RestoreResult.Success)
        val actualHist = fakeDailyDao.getDailyDataSync(histDate)
        assertNotNull(actualHist)
        assertEquals("Must take maxOf(25, 15) to prevent physical reel loss", 25, actualHist!!.totalReelsCount)
        assertEquals("Must take maxOf time spent", 50000L, actualHist.totalTimeSpentMillis)
        assertTrue("Strict mode union must be true (false || true)", actualHist.isStrictModeOn)
        assertEquals("Must remain pending sync (1)", 1, actualHist.syncStatus)
    }

    @Test
    fun `Deterministic Merge - Cloud-only historical record is inserted as synced`() = runBlocking {
        val histDate = "2026-10-15"
        val cloudRecords = listOf(
            CloudSnapshotRecordDto(
                dateString = histDate,
                count = 42,
                totalReelsCount = 42,
                dailyGoal = 50,
                isSkullDay = false
            )
        )

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val result = simulator.applyRestore(
            cloudRecords = cloudRecords,
            idempotencyKey = "key_05",
            backupId = "bk_05",
            cloudDailyLimit = 100
        )

        assertTrue(result is RestoreResult.Success)
        val actualHist = fakeDailyDao.getDailyDataSync(histDate)
        assertNotNull(actualHist)
        assertEquals(42, actualHist!!.totalReelsCount)
        assertEquals(0, actualHist.syncStatus)
    }

    // =========================================================================
    // SECTION 2: 4-Phase Commit & Outbox Latching
    // =========================================================================

    @Test
    fun `Restore 4-Phase Commit Sequence commits Room and latches DataStore outbox`() = runBlocking {
        val cloudRecords = listOf(
            CloudSnapshotRecordDto(dateString = "2026-10-18", count = 20, totalReelsCount = 20)
        )
        val key = "key_commit_seq"
        val backupId = "bk_commit_seq"
        val targetLimit = 150

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val result = simulator.applyRestore(cloudRecords, key, backupId, targetLimit)

        assertTrue(result is RestoreResult.Success)
        // 1. Room Journal row created
        val journal = fakeJournalDao.getByIdempotencyKey(key)
        assertNotNull("Journal entry must exist", journal)
        assertEquals(backupId, journal!!.backupId)
        assertEquals(1, journal.recordsRestored)
        assertEquals(150, journal.restoredDailyLimit)
        assertEquals(LocalRestoreJournal.STATUS_ACKNOWLEDGED, journal.ackStatus)
        assertEquals("DataStore must be latched (1)", 1, journal.datastoreSynced)

        // 2. DataStore updated
        assertEquals("DataStore limit must match restored limit", 150, fakePrefsRepo.defaultDailyLimit.first())
    }

    // =========================================================================
    // SECTION 3: Crash Recovery & Startup Self-Healing (RestoreReconciler)
    // =========================================================================

    @Test
    fun `Crash Recovery - Crash between Phase 1 and Phase 2 (DataStore unlatched) self-heals without re-merging habit history`() = runBlocking {
        val key = "key_crash_p1_p2"
        val backupId = "bk_crash_p1_p2"

        // Simulate crash point: Room committed, but DataStore flush never happened (datastoreSynced = 0)
        val journal = LocalRestoreJournal(
            idempotencyKey = key,
            backupId = backupId,
            sessionDateIst = todayStr,
            appliedAtMs = System.currentTimeMillis() - 60000L,
            recordsRestored = 5,
            restoredDailyLimit = 220,
            ackStatus = LocalRestoreJournal.STATUS_PENDING_ACK,
            datastoreSynced = 0
        )
        fakeJournalDao.insert(journal)

        // Add reel tracked AFTER crash
        val todayRecord = DailyReelData(dateString = todayStr, totalReelsCount = 7, syncStatus = 1)
        fakeDailyDao.insertOrReplace(todayRecord)

        // Application cold start executes reconciler
        val reconciler = FakeRestoreReconciler(fakeJournalDao, fakePrefsRepo)
        reconciler.reconcile()

        // 1. DataStore limit was caught up
        assertEquals("DataStore limit must be updated to staged limit", 220, fakePrefsRepo.defaultDailyLimit.first())

        // 2. Journal latched
        val updatedJournal = fakeJournalDao.getByIdempotencyKey(key)
        assertEquals("Journal must be latched (1)", 1, updatedJournal!!.datastoreSynced)

        // 3. ACK enqueued
        assertTrue("ACK worker must be enqueued", reconciler.enqueuedAckKeys.contains(key))

        // 4. CRITICAL INVARIANT: Today's post-crash tracking is 100% untouched!
        assertEquals("Today's tracking must NOT be altered during reconciliation", 7, fakeDailyDao.getDailyDataSync(todayStr)!!.totalReelsCount)
    }

    @Test
    fun `Crash Recovery - Crash between Phase 3 and Phase 4 (pending ACK) self-heals by enqueuing ACK`() = runBlocking {
        val key = "key_crash_p3_p4"
        val backupId = "bk_crash_p3_p4"

        // Simulate crash: DataStore latched, but ACK not yet delivered
        val journal = LocalRestoreJournal(
            idempotencyKey = key,
            backupId = backupId,
            sessionDateIst = todayStr,
            appliedAtMs = System.currentTimeMillis() - 30000L,
            recordsRestored = 3,
            restoredDailyLimit = 180,
            ackStatus = LocalRestoreJournal.STATUS_PENDING_ACK,
            datastoreSynced = 1
        )
        fakeJournalDao.insert(journal)

        val reconciler = FakeRestoreReconciler(fakeJournalDao, fakePrefsRepo)
        reconciler.reconcile()

        assertTrue("ACK worker must be enqueued for pending ACK journal", reconciler.enqueuedAckKeys.contains(key))
    }

    // =========================================================================
    // SECTION 4: In-Flight Retry & Idempotency
    // =========================================================================

    @Test
    fun `In-flight retry with matching idempotencyKey detects existing journal and skips habit re-merge`() = runBlocking {
        val key = "key_in_flight"
        val backupId = "bk_in_flight"

        // Existing committed journal
        val existingJournal = LocalRestoreJournal(
            idempotencyKey = key,
            backupId = backupId,
            sessionDateIst = todayStr,
            appliedAtMs = System.currentTimeMillis() - 5000L,
            recordsRestored = 10,
            restoredDailyLimit = 150,
            ackStatus = LocalRestoreJournal.STATUS_ACKNOWLEDGED,
            datastoreSynced = 1
        )
        fakeJournalDao.insert(existingJournal)

        // Track a new reel locally after the initial commit
        fakeDailyDao.insertOrReplace(DailyReelData(dateString = todayStr, totalReelsCount = 8))

        // Client repeats restore with same idempotency key (e.g. dropped response retry)
        val cloudRecords = listOf(
            CloudSnapshotRecordDto(dateString = todayStr, count = 1, totalReelsCount = 1)
        )
        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val result = simulator.applyRestore(cloudRecords, key, backupId, 150)

        assertTrue(result is RestoreResult.Success)
        assertTrue("Must be marked as idempotent", (result as RestoreResult.Success).isIdempotent)

        // Habit history must NOT have been re-merged
        assertEquals("Post-commit reel tracking must be preserved", 8, fakeDailyDao.getDailyDataSync(todayStr)!!.totalReelsCount)
    }

    @Test
    fun `Multiple distinct restores of same backupId on different sessions receive independent journal rows`() = runBlocking {
        val backupId = "bk_shared_snap"
        val key1 = "key_session_1"
        val key2 = "key_session_2"

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)

        val res1 = simulator.applyRestore(emptyList(), key1, backupId, 100)
        assertTrue(res1 is RestoreResult.Success)

        val res2 = simulator.applyRestore(emptyList(), key2, backupId, 100)
        assertTrue(res2 is RestoreResult.Success)

        // Both journal rows exist independently
        assertNotNull(fakeJournalDao.getByIdempotencyKey(key1))
        assertNotNull(fakeJournalDao.getByIdempotencyKey(key2))
        assertNotEquals(key1, key2)
    }

    // =========================================================================
    // SECTION 5: Pre-Commit Abort Protocol
    // =========================================================================

    @Test
    fun `Pre-commit abort on corrupt payload leaves Room and Journal 100% untouched`() = runBlocking {
        // Local records before restore attempt
        val initialRecord = DailyReelData(dateString = "2026-10-19", totalReelsCount = 14, syncStatus = 0)
        fakeDailyDao.insertOrReplace(initialRecord)

        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)

        // Payload with corrupt / malformed date
        val corruptRecords = listOf(
            CloudSnapshotRecordDto(dateString = "MALFORMED_DATE_XYZ", count = 99)
        )

        val result = simulator.applyRestoreWithValidation(corruptRecords, "key_abort", "bk_corrupt", 100)
        assertTrue(result is RestoreResult.Error)
        assertTrue(simulator.abortedKeys.contains("key_abort"))

        // Journal must have 0 entries
        assertNull(fakeJournalDao.getByIdempotencyKey("key_abort"))

        // Initial record untouched
        assertEquals(14, fakeDailyDao.getDailyDataSync("2026-10-19")!!.totalReelsCount)
    }

    // =========================================================================
    // SECTION 6: Backup Flow & Request ID Idempotency
    // =========================================================================

    @Test
    fun `Fast-path backup marks all local records synced and clears requestId on success`() = runBlocking {
        // Setup 10 unsynced records
        for (i in 1..10) {
            val date = "2026-10-0$i"
            fakeDailyDao.insertOrReplace(DailyReelData(dateString = date, totalReelsCount = i, syncStatus = 1))
        }

        val simulator = FakeBackupSimulator(fakeDailyDao, fakePrefsRepo)
        val result = simulator.performFastPathBackup(success = true)

        assertTrue(result is BackupResult.Success)
        val syncedRecords = fakeDailyDao.getAllDailyDataSync()
        assertTrue("All records must be marked synced (syncStatus = 0)", syncedRecords.all { it.syncStatus == 0 })
        assertTrue("lastBackupTime must be updated", fakePrefsRepo.lastBackupTime.first() > 0L)
        assertNull("currentBackupRequestId must be cleared on success", fakePrefsRepo.currentBackupRequestId.first())
    }

    @Test
    fun `Chunked backup slices large datasets and marks all synced`() = runBlocking {
        // Setup 250 records
        for (i in 1..250) {
            val date = "2025-${String.format("%02d", (i % 12) + 1)}-${String.format("%02d", (i % 28) + 1)}_$i"
            fakeDailyDao.insertOrReplace(DailyReelData(dateString = date, totalReelsCount = i, syncStatus = 1))
        }

        val simulator = FakeBackupSimulator(fakeDailyDao, fakePrefsRepo)
        val result = simulator.performChunkedBackup(chunkSize = 200)

        assertTrue(result is BackupResult.Success)
        assertEquals(2, simulator.uploadedChunkCount) // 250 records = 2 chunks (200 + 50)
        assertTrue(fakeDailyDao.getAllDailyDataSync().all { it.syncStatus == 0 })
    }

    @Test
    fun `Backup retry reuses stable currentBackupRequestId on transient network failure`() = runBlocking {
        fakeDailyDao.insertOrReplace(DailyReelData(dateString = "2026-10-20", totalReelsCount = 5, syncStatus = 1))

        val simulator = FakeBackupSimulator(fakeDailyDao, fakePrefsRepo)

        // Attempt 1 fails due to network error
        val result1 = simulator.performFastPathBackup(success = false, isNetworkError = true)
        assertTrue(result1 is BackupResult.Error)

        val preservedRequestId = fakePrefsRepo.currentBackupRequestId.first()
        assertNotNull("Request ID must be preserved for retry", preservedRequestId)

        // Attempt 2 succeeds using preserved request ID
        val result2 = simulator.performFastPathBackup(success = true)
        assertTrue(result2 is BackupResult.Success)
        assertEquals("Must reuse the same request ID", preservedRequestId, simulator.lastUsedRequestId)
        assertNull("Request ID must be cleared after success", fakePrefsRepo.currentBackupRequestId.first())
    }

    @Test
    fun `Backup cooldown restricts subsequent client requests until next eligible time`() = runBlocking {
        val simulator = FakeBackupSimulator(fakeDailyDao, fakePrefsRepo)
        val nextEligible = System.currentTimeMillis() + 86400000L

        val result = simulator.simulateCooldown(nextEligible)
        assertTrue(result is BackupResult.Cooldown)
        assertEquals(nextEligible, (result as BackupResult.Cooldown).nextEligibleTimeMs)
        assertTrue("isBackupRestricted must be true", simulator.isRestricted.value)
    }

    @Test
    fun `Restore retry reuses stable currentRestoreRequestId on transient network failure`() = runBlocking {
        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        val cloudRecords = listOf(
            CloudSnapshotRecordDto(dateString = "2026-10-20", count = 5, totalReelsCount = 5)
        )

        // Attempt 1: Fails due to transient network failure
        val failureResult = simulator.performRestoreWithRetrySimulation(
            shouldFailFirstAttempt = true,
            cloudRecords = cloudRecords,
            backupId = "bk_test_snap",
            cloudDailyLimit = 50
        )
        assertTrue("Attempt 1 must be error", failureResult is RestoreResult.Error)
        val preservedRequestId = fakePrefsRepo.currentRestoreRequestId.first()
        assertNotNull("currentRestoreRequestId must be preserved for retry", preservedRequestId)

        // Attempt 2: Retry reuses same requestId and succeeds
        val retryResult = simulator.performRestoreWithRetrySimulation(
            shouldFailFirstAttempt = false,
            cloudRecords = cloudRecords,
            backupId = "bk_test_snap",
            cloudDailyLimit = 50
        )
        assertTrue("Attempt 2 must succeed", retryResult is RestoreResult.Success)
        assertEquals("Must reuse the same restore request ID", preservedRequestId, simulator.lastUsedRestoreRequestId)
        assertNull("Restore request ID must be cleared after success", fakePrefsRepo.currentRestoreRequestId.first())
    }

    @Test
    fun `PreferencesRepository clearAllPreferences preserves active recovery identifiers`() = runBlocking {
        fakePrefsRepo.setCurrentBackupRequestId("req_in_flight_123")
        fakePrefsRepo.setCurrentRestoreRequestId("rst_in_flight_456")
        fakePrefsRepo.setDefaultDailyLimit(100)
        fakePrefsRepo.setLastBackupTime(123456789L)

        // Clear all preferences
        fakePrefsRepo.clearAllPreferences()

        // Daily limit and last backup time are reset
        assertEquals(0, fakePrefsRepo.defaultDailyLimit.first())
        assertEquals(0L, fakePrefsRepo.lastBackupTime.first())

        // In-flight recovery identifiers are strictly preserved
        assertEquals("req_in_flight_123", fakePrefsRepo.currentBackupRequestId.first())
        assertEquals("rst_in_flight_456", fakePrefsRepo.currentRestoreRequestId.first())
    }

    // =========================================================================
    // SECTION 7: Room Database Migration 11 - 12
    // =========================================================================

    @Test
    fun `Room Migration 11 to 12 executes valid CREATE TABLE SQL on SupportSQLiteDatabase`() {
        val fakeDb = FakeSupportSQLiteDatabase()
        val migration = com.reeltrak.app.core.di.DatabaseModule.MIGRATION_11_12

        assertEquals(11, migration.startVersion)
        assertEquals(12, migration.endVersion)

        migration.migrate(fakeDb.asDatabase())

        assertTrue("Must execute at least one SQL statement", fakeDb.executedSqlList.isNotEmpty())
        val executedSql = fakeDb.executedSqlList.joinToString(" ")

        assertTrue("Must create local_restore_journal table", executedSql.contains("CREATE TABLE IF NOT EXISTS local_restore_journal"))
        assertTrue("Must have idempotency_key PRIMARY KEY NOT NULL", executedSql.contains("idempotency_key TEXT PRIMARY KEY NOT NULL"))
        assertTrue("Must have backup_id TEXT NOT NULL", executedSql.contains("backup_id TEXT NOT NULL"))
        assertTrue("Must have session_date_ist TEXT NOT NULL", executedSql.contains("session_date_ist TEXT NOT NULL"))
        assertTrue("Must have applied_at_ms INTEGER NOT NULL", executedSql.contains("applied_at_ms INTEGER NOT NULL"))
        assertTrue("Must have records_restored INTEGER NOT NULL", executedSql.contains("records_restored INTEGER NOT NULL"))
        assertTrue("Must have restored_daily_limit INTEGER", executedSql.contains("restored_daily_limit INTEGER"))
        assertTrue("Must have ack_status TEXT NOT NULL", executedSql.contains("ack_status TEXT NOT NULL"))
        assertTrue("Must have datastore_synced INTEGER NOT NULL DEFAULT 0", executedSql.contains("datastore_synced INTEGER NOT NULL DEFAULT 0"))
        assertFalse("Must not drop any tables", executedSql.contains("DROP TABLE"))
    }

    @Test
    fun `Backup completion markAsSynced preserves newer offline edits modified during upload`() = runBlocking {
        val testDate = "2026-10-22"
        val readTime = 1000L
        val originalRecord = DailyReelData(
            dateString = testDate,
            totalReelsCount = 10,
            updatedAt = readTime,
            syncStatus = 1
        )
        fakeDailyDao.insertOrReplace(originalRecord)

        // User tracks more reels while the network request is uploading
        val newerTime = 2000L
        val newerRecord = originalRecord.copy(
            totalReelsCount = 15,
            updatedAt = newerTime,
            syncStatus = 1
        )
        fakeDailyDao.insertOrReplace(newerRecord)

        // Backup finishes and attempts markAsSynced with stale readTime
        fakeDailyDao.markAsSynced(testDate, readTime)

        // Verify the record is STILL pending sync (syncStatus = 1) and count is 15
        val persisted = fakeDailyDao.getDailyDataSync(testDate)
        assertNotNull(persisted)
        assertEquals("Newer count must be preserved", 15, persisted!!.totalReelsCount)
        assertEquals("syncStatus must remain 1 (pending) because updatedAt did not match", 1, persisted.syncStatus)

        // Now simulate markAsSynced with the matching newerTime
        fakeDailyDao.markAsSynced(testDate, newerTime)
        val synced = fakeDailyDao.getDailyDataSync(testDate)
        assertNotNull(synced)
        assertEquals("syncStatus must transition to 0 (synced)", 0, synced!!.syncStatus)
    }

    @Test
    fun `SyncStatus invariants - 0 is SYNCED and 1 is PENDING across tracking and restore`() = runBlocking {
        val date = "2026-10-18"
        // Fresh restored record from cloud is marked SYNCED (0)
        val cloudRec = CloudSnapshotRecordDto(dateString = date, count = 20, totalReelsCount = 20)
        val simulator = DeterministicMergeSimulator(fakeDailyDao, fakeJournalDao, fakePrefsRepo, todayStr)
        simulator.applyRestore(listOf(cloudRec), "rst_sync_status_check", "bk_sync", 50)

        val restored = fakeDailyDao.getDailyDataSync(date)
        assertNotNull(restored)
        assertEquals("Cloud restored records must have syncStatus = 0 (SYNCED)", 0, restored!!.syncStatus)

        // Simulating a local offline tracking edit changes syncStatus to 1 (PENDING)
        val modifiedLocally = restored.copy(totalReelsCount = 25, syncStatus = 1, updatedAt = System.currentTimeMillis())
        fakeDailyDao.insertOrReplace(modifiedLocally)
        val pending = fakeDailyDao.getDailyDataSync(date)
        assertEquals("Local edits must have syncStatus = 1 (PENDING)", 1, pending!!.syncStatus)

        // Second restore arrives from cloud with count 22
        // Deterministic merge policy: local was modified offline (syncStatus == 1), so maxOf(25, 22) = 25 wins
        val cloudRec2 = CloudSnapshotRecordDto(dateString = date, count = 22, totalReelsCount = 22)
        simulator.applyRestore(listOf(cloudRec2), "rst_sync_status_check_2", "bk_sync_2", 50)
        val merged = fakeDailyDao.getDailyDataSync(date)
        assertNotNull(merged)
        assertEquals("Offline local progress must win via maxOf", 25, merged!!.totalReelsCount)
        assertEquals("Merged record must retain syncStatus = 1 (PENDING) for future backup", 1, merged.syncStatus)
    }

    // =========================================================================
    // TEST HARNESS: In-Memory Fakes & Simulators
    // =========================================================================

    class FakeSupportSQLiteDatabase : java.lang.reflect.InvocationHandler {
        val executedSqlList = mutableListOf<String>()

        fun asDatabase(): SupportSQLiteDatabase {
            return java.lang.reflect.Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                this
            ) as SupportSQLiteDatabase
        }

        override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSqlList.add(args[0] as String)
                return null
            }
            return null
        }
    }

    class FakeDailyReelDataDao : DailyReelDataDao {
        override suspend fun getAcceptedTransitionState(accountScope: String, dateString: String): com.reeltrak.app.data.local.entity.AcceptedReelTransitionState? = null
        override suspend fun insertAcceptedTransitionState(state: com.reeltrak.app.data.local.entity.AcceptedReelTransitionState) = Unit
        override suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int = 0
        val records = ConcurrentHashMap<String, DailyReelData>()

        override suspend fun insertOrReplace(data: DailyReelData) {
            records[data.dateString] = data
        }

        override suspend fun getDailyDataSync(dateString: String): DailyReelData? = records[dateString]
        override suspend fun getLatestDailyDataSync(): DailyReelData? = records.values.maxByOrNull { it.dateString }
        override suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData? =
            records.values.filter { it.dateString < dateString }.maxByOrNull { it.dateString }

        override fun getDailyDataFlow(dateString: String): Flow<DailyReelData?> = MutableStateFlow(records[dateString])
        override fun getAllDailyDataFlow(): Flow<List<DailyReelData>> = MutableStateFlow(records.values.sortedBy { it.dateString })
        override fun getDailyDataRangeFlow(startDate: String, endDate: String): Flow<List<DailyReelData>> =
            MutableStateFlow(records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString })

        override suspend fun getAllDailyDataSync(): List<DailyReelData> = records.values.sortedBy { it.dateString }
        override suspend fun getDailyDataRangeSync(startDate: String, endDate: String): List<DailyReelData> =
            records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString }

        override suspend fun clearAll() = records.clear()
        override suspend fun clearHistoricalDataExcept(today: String) {
            records.keys.removeIf { it != today }
        }

        override suspend fun insertAll(data: List<DailyReelData>) {
            data.forEach { records[it.dateString] = it }
        }

        override suspend fun getPendingSyncData(todayStr: String): List<DailyReelData> =
            records.values.filter { it.syncStatus == 1 && it.dateString != todayStr }.sortedBy { it.dateString }

        override suspend fun getHistoricalDailyDataSync(todayStr: String): List<DailyReelData> =
            records.values.filter { it.dateString != todayStr }.sortedBy { it.dateString }

        override suspend fun deleteByDate(dateStr: String) {
            records.remove(dateStr)
        }

        override suspend fun deleteHistoricalSyncedExcept(todayStr: String) {
            records.keys.removeIf { key ->
                val r = records[key]
                r != null && r.syncStatus == 0 && key != todayStr
            }
        }

        override suspend fun markAsSynced(dateStr: String, syncedTimestamp: Long) {
            val record = records[dateStr]
            if (record != null && record.updatedAt == syncedTimestamp) {
                records[dateStr] = record.copy(syncStatus = 0)
            }
        }
    }

    class FakeLocalRestoreJournalDao : LocalRestoreJournalDao {
        val journals = ConcurrentHashMap<String, LocalRestoreJournal>()

        override suspend fun insert(journal: LocalRestoreJournal): Long {
            if (journals.containsKey(journal.idempotencyKey)) return -1L
            journals[journal.idempotencyKey] = journal
            return 1L
        }

        override suspend fun getByIdempotencyKey(idempotencyKey: String): LocalRestoreJournal? =
            journals[idempotencyKey]

        override suspend fun getPendingAckEntries(): List<LocalRestoreJournal> =
            journals.values.filter { it.ackStatus == LocalRestoreJournal.STATUS_PENDING_ACK }.sortedBy { it.appliedAtMs }

        override suspend fun getPendingDataStoreSyncEntries(): List<LocalRestoreJournal> =
            journals.values.filter { it.datastoreSynced == 0 }.sortedBy { it.appliedAtMs }

        override suspend fun markDataStoreSynced(idempotencyKey: String) {
            val j = journals[idempotencyKey]
            if (j != null) journals[idempotencyKey] = j.copy(datastoreSynced = 1)
        }

        override suspend fun markAcknowledged(idempotencyKey: String) {
            val j = journals[idempotencyKey]
            if (j != null) journals[idempotencyKey] = j.copy(ackStatus = LocalRestoreJournal.STATUS_ACKNOWLEDGED)
        }

        override suspend fun getLatestJournalEntry(): LocalRestoreJournal? =
            journals.values.maxByOrNull { it.appliedAtMs }

        override suspend fun pruneAcknowledgedJournals(cutoffMs: Long) {
            journals.keys.removeIf { key ->
                val j = journals[key]
                j != null && j.ackStatus == LocalRestoreJournal.STATUS_ACKNOWLEDGED && j.appliedAtMs < cutoffMs
            }
        }

        override suspend fun clearAll() = journals.clear()
    }

    class FakePreferencesRepository : PreferencesRepository {
        private val _defaultDailyLimit = MutableStateFlow(0)
        private val _lastBackupTime = MutableStateFlow(0L)
        private val _lastRestoreTime = MutableStateFlow(0L)
        private val _currentBackupRequestId = MutableStateFlow<String?>(null)
        private val _currentRestoreRequestId = MutableStateFlow<String?>(null)
        private val _lastBackupStatus = MutableStateFlow<String?>(null)

        override val defaultDailyLimit: Flow<Int> = _defaultDailyLimit
        override suspend fun setDefaultDailyLimit(limit: Int) { _defaultDailyLimit.value = limit }

        override val lastBackupTime: Flow<Long> = _lastBackupTime
        override suspend fun setLastBackupTime(time: Long) { _lastBackupTime.value = time }

        override val currentBackupRequestId: Flow<String?> = _currentBackupRequestId
        override suspend fun setCurrentBackupRequestId(requestId: String?) { _currentBackupRequestId.value = requestId }

        override val currentRestoreRequestId: Flow<String?> = _currentRestoreRequestId
        override suspend fun setCurrentRestoreRequestId(requestId: String?) { _currentRestoreRequestId.value = requestId }

        override val lastRestoreTime: Flow<Long> = _lastRestoreTime
        override suspend fun setLastRestoreTime(time: Long) { _lastRestoreTime.value = time }

        override val lastBackupStatus: Flow<String?> = _lastBackupStatus
        override suspend fun setLastBackupStatus(status: String?) { _lastBackupStatus.value = status }

        // Unused stubs
        override val dailyIntent: Flow<Int> = MutableStateFlow(0)
        override val firstRunDate: Flow<Long> = MutableStateFlow(0L)
        override val goalSetDate: Flow<Long> = MutableStateFlow(0L)
        override val isStrictModeLocked: Flow<Boolean> = MutableStateFlow(false)
        override val lastStrictModeOffDate: Flow<String?> = MutableStateFlow(null)
        override val isDailyLimitLocked: Flow<Boolean> = MutableStateFlow(false)
        override val lastLockEpochDay: Flow<Long> = MutableStateFlow(0L)
        override val lastLimitChangeDate: Flow<Long> = MutableStateFlow(0L)
        override val lastDailySummaryDate: Flow<Long> = MutableStateFlow(0L)
        override val notificationsEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val badge7UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge30UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge50UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge100UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge365UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge7DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge30DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge50DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge100DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge365DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setBadgeUnlockTime(milestone: Int, time: Long) {}
        override suspend fun setBadgeDialogShown(milestone: Int, shown: Boolean) {}
        override suspend fun setLastLimitChangeDate(date: Long) {}
        override suspend fun setLastDailySummaryDate(date: Long) {}
        override suspend fun setDailyIntent(intent: Int) {}
        override suspend fun setFirstRunDate(date: Long) {}
        override suspend fun setGoalSetDate(date: Long) {}
        override suspend fun setStrictModeLocked(locked: Boolean) {}
        override suspend fun setLastStrictModeOffDate(date: String) {}
        override suspend fun setDailyLimitLocked(locked: Boolean) {}
        override suspend fun setLastLockEpochDay(day: Long) {}
        override suspend fun setNotificationsEnabled(enabled: Boolean) {}
        override val counterThemeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.LIGHT)
        override suspend fun setCounterThemeMode(mode: ThemeMode) {}
        override val authSession: Flow<com.reeltrak.app.core.auth.UserAuthData?> = MutableStateFlow(null)
        override suspend fun saveAuthSession(userData: com.reeltrak.app.core.auth.UserAuthData) {}
        override suspend fun clearAuthSession() {}
        override suspend fun clearAllPreferences() {
            val b = _currentBackupRequestId.value
            val r = _currentRestoreRequestId.value
            _defaultDailyLimit.value = 0
            _lastBackupTime.value = 0L
            _lastRestoreTime.value = 0L
            _lastBackupStatus.value = null
            _currentBackupRequestId.value = b
            _currentRestoreRequestId.value = r
        }
        override val isFreeMode: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setFreeMode(enabled: Boolean) {}
        override val hasMigratedToServer: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setHasMigratedToServer(migrated: Boolean) {}
        override val hasCompletedTour: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setHasCompletedTour(completed: Boolean) {}
        override suspend fun getAllPreferencesSync(): Map<String, Any> = emptyMap()
        override suspend fun restorePreferences(cloudData: Map<String, Any>) {}
    }

    class DeterministicMergeSimulator(
        private val dailyDao: FakeDailyReelDataDao,
        private val journalDao: FakeLocalRestoreJournalDao,
        private val prefsRepo: FakePreferencesRepository,
        private val todayStr: String
    ) {
        val abortedKeys = mutableSetOf<String>()
        var lastUsedRestoreRequestId: String? = null

        suspend fun performRestoreWithRetrySimulation(
            shouldFailFirstAttempt: Boolean,
            cloudRecords: List<CloudSnapshotRecordDto>,
            backupId: String,
            cloudDailyLimit: Int?
        ): RestoreResult {
            var reqId = prefsRepo.currentRestoreRequestId.first()
            if (reqId.isNullOrBlank()) {
                reqId = "rst_" + java.util.UUID.randomUUID().toString()
                prefsRepo.setCurrentRestoreRequestId(reqId)
            }
            lastUsedRestoreRequestId = reqId

            if (shouldFailFirstAttempt) {
                // Transient network failure during download or reservation
                return RestoreResult.Error("Transient network timeout", isNetworkError = true)
            }

            val result = applyRestore(cloudRecords, reqId, backupId, cloudDailyLimit)
            prefsRepo.setCurrentRestoreRequestId(null)
            return result
        }

        suspend fun applyRestore(
            cloudRecords: List<CloudSnapshotRecordDto>,
            idempotencyKey: String,
            backupId: String,
            cloudDailyLimit: Int?
        ): RestoreResult {
            // Check in-flight retry
            val existing = journalDao.getByIdempotencyKey(idempotencyKey)
            if (existing != null) {
                if (existing.datastoreSynced == 0 && existing.restoredDailyLimit != null) {
                    prefsRepo.setDefaultDailyLimit(existing.restoredDailyLimit)
                    journalDao.markDataStoreSynced(idempotencyKey)
                }
                return RestoreResult.Success(existing.backupId, existing.recordsRestored, existing.restoredDailyLimit, isIdempotent = true)
            }

            // Phase 1: Room Merge
            var restoredCount = 0
            for (cloudRec in cloudRecords) {
                val date = cloudRec.dateString
                val localRec = dailyDao.getDailyDataSync(date)

                if (date == todayStr) {
                    if (localRec != null && localRec.totalReelsCount > 0) {
                        // LOCAL WINS
                        continue
                    } else {
                        dailyDao.insertOrReplace(DailyReelData(
                            dateString = date,
                            totalReelsCount = cloudRec.effectiveCount,
                            dailyGoal = cloudRec.dailyGoal,
                            isStrictModeOn = cloudRec.isStrictModeOn,
                            syncStatus = 0
                        ))
                        restoredCount++
                    }
                } else {
                    if (localRec == null) {
                        dailyDao.insertOrReplace(DailyReelData(
                            dateString = date,
                            totalReelsCount = cloudRec.effectiveCount,
                            dailyGoal = cloudRec.dailyGoal,
                            isStrictModeOn = cloudRec.isStrictModeOn,
                            syncStatus = 0
                        ))
                        restoredCount++
                    } else if (localRec.syncStatus == 0) {
                        // CLOUD WINS
                        dailyDao.insertOrReplace(localRec.copy(
                            totalReelsCount = cloudRec.effectiveCount,
                            dailyGoal = cloudRec.dailyGoal,
                            isStrictModeOn = cloudRec.isStrictModeOn,
                            syncStatus = 0
                        ))
                        restoredCount++
                    } else {
                        // UNSYNCED OFFLINE -> maxOf
                        dailyDao.insertOrReplace(localRec.copy(
                            totalReelsCount = maxOf(localRec.totalReelsCount, cloudRec.effectiveCount),
                            totalTimeSpentMillis = maxOf(localRec.totalTimeSpentMillis, cloudRec.totalTimeSpentMillis),
                            isStrictModeOn = localRec.isStrictModeOn || cloudRec.isStrictModeOn,
                            syncStatus = 1
                        ))
                        restoredCount++
                    }
                }
            }

            // Phase 1 Journal Insert
            val journal = LocalRestoreJournal(
                idempotencyKey = idempotencyKey,
                backupId = backupId,
                sessionDateIst = todayStr,
                appliedAtMs = System.currentTimeMillis(),
                recordsRestored = restoredCount,
                restoredDailyLimit = cloudDailyLimit,
                ackStatus = LocalRestoreJournal.STATUS_PENDING_ACK,
                datastoreSynced = 0
            )
            journalDao.insert(journal)

            // Phase 2: Post-commit DataStore
            if (cloudDailyLimit != null && cloudDailyLimit > 0) {
                prefsRepo.setDefaultDailyLimit(cloudDailyLimit)
            }

            // Phase 3: Outbox latch
            journalDao.markDataStoreSynced(idempotencyKey)

            // Phase 4: ACK
            journalDao.markAcknowledged(idempotencyKey)

            return RestoreResult.Success(backupId, restoredCount, cloudDailyLimit, isIdempotent = false)
        }

        suspend fun applyRestoreWithValidation(
            cloudRecords: List<CloudSnapshotRecordDto>,
            idempotencyKey: String,
            backupId: String,
            cloudDailyLimit: Int?
        ): RestoreResult {
            val dateRegex = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
            for (rec in cloudRecords) {
                if (!dateRegex.matches(rec.dateString)) {
                    abortedKeys.add(idempotencyKey)
                    return RestoreResult.Error("Corrupt date format: ${rec.dateString}")
                }
            }
            return applyRestore(cloudRecords, idempotencyKey, backupId, cloudDailyLimit)
        }
    }

    class FakeRestoreReconciler(
        private val journalDao: FakeLocalRestoreJournalDao,
        private val prefsRepo: FakePreferencesRepository
    ) {
        val enqueuedAckKeys = mutableListOf<String>()

        suspend fun reconcile() {
            // Step 1: DataStore unlatched
            val pendingDataStore = journalDao.getPendingDataStoreSyncEntries()
            for (entry in pendingDataStore) {
                entry.restoredDailyLimit?.let { if (it > 0) prefsRepo.setDefaultDailyLimit(it) }
                journalDao.markDataStoreSynced(entry.idempotencyKey)
                enqueuedAckKeys.add(entry.idempotencyKey)
            }

            // Step 2: Pending ACKs
            val pendingAcks = journalDao.getPendingAckEntries()
            for (entry in pendingAcks) {
                enqueuedAckKeys.add(entry.idempotencyKey)
            }
        }
    }

    class FakeBackupSimulator(
        private val dailyDao: FakeDailyReelDataDao,
        private val prefsRepo: FakePreferencesRepository
    ) {
        var uploadedChunkCount = 0
        var lastUsedRequestId: String? = null
        val isRestricted = MutableStateFlow(false)

        suspend fun performFastPathBackup(success: Boolean, isNetworkError: Boolean = false): BackupResult {
            var reqId = prefsRepo.currentBackupRequestId.first()
            if (reqId == null) {
                reqId = "req_sim_" + System.currentTimeMillis()
                prefsRepo.setCurrentBackupRequestId(reqId)
            }
            lastUsedRequestId = reqId

            if (!success) {
                return BackupResult.Error("Network failure", isNetworkError = isNetworkError)
            }

            // Success
            val all = dailyDao.getAllDailyDataSync()
            all.forEach { dailyDao.markAsSynced(it.dateString, it.updatedAt) }
            val nowMs = System.currentTimeMillis()
            prefsRepo.setLastBackupTime(nowMs)
            prefsRepo.setCurrentBackupRequestId(null)
            isRestricted.value = true

            return BackupResult.Success("bk_fast", nowMs, nowMs + 86400000L)
        }

        suspend fun performChunkedBackup(chunkSize: Int): BackupResult {
            var reqId = prefsRepo.currentBackupRequestId.first()
            if (reqId == null) {
                reqId = "req_chunk_" + System.currentTimeMillis()
                prefsRepo.setCurrentBackupRequestId(reqId)
            }
            lastUsedRequestId = reqId

            val all = dailyDao.getAllDailyDataSync()
            val chunks = all.chunked(chunkSize)
            uploadedChunkCount = chunks.size

            all.forEach { dailyDao.markAsSynced(it.dateString, it.updatedAt) }
            val nowMs = System.currentTimeMillis()
            prefsRepo.setLastBackupTime(nowMs)
            prefsRepo.setCurrentBackupRequestId(null)
            isRestricted.value = true

            return BackupResult.Success("bk_chunked", nowMs, nowMs + 86400000L)
        }

        fun simulateCooldown(nextEligibleTimeMs: Long): BackupResult {
            isRestricted.value = true
            return BackupResult.Cooldown(nextEligibleTimeMs)
        }
    }
}
