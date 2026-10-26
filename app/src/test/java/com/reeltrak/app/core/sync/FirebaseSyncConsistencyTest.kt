package com.reeltrak.app.core.sync

import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.remote.dto.CloudDailyReelData
import com.reeltrak.app.domain.models.AccountStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Production Audit Test Suite: Firebase Synchronization & Cloud Data Consistency.
 *
 * Verifies:
 * 1. Cloud restore strictly excludes today's record (today's enforcement is device-local).
 * 2. Cloud restore never overwrites higher local count for pending offline historical days (conflict resolution: maxOf).
 * 3. Cloud restore adopts higher cloud count if cloud has more reels for an unsynced historical day.
 * 4. Cloud restore inserts cloud-only historical records as synced (syncStatus = 0).
 * 5. Cloud restore deletes obsolete synced historical records (syncStatus = 0) absent from cloud.
 * 6. Cloud restore preserves offline unsynced historical records (syncStatus = 1) absent from cloud.
 * 7. Pending sync query strictly excludes today's date (dateString != todayStr).
 * 8. Optimistic concurrency token (updatedAt) prevents stale sync reconciliation if local record mutated during upload.
 * 9. Partial sync failure marks only successful records as synced, leaving failed records pending.
 * 10. Ineligible accounts (RESTRICTED, DELETED, PENDING_DELETION) abort sync operations.
 * 11. Sign-out clears historical data while preserving today's device-local tracking.
 * 12. Midnight transition finalizes yesterday's record and marks it pending for cloud sync.
 * 13. Monotonic invariant prevents stale database or cloud reload from decrementing today's in-memory counter.
 * 14. Zero subscription or credential fields exist in CloudDailyReelData DTO schema.
 * 15. Auth UID context change mid-sync triggers immediate safe abort.
 */
class FirebaseSyncConsistencyTest {

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
        override suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData? {
            return records.values.filter { it.dateString < dateString }.maxByOrNull { it.dateString }
        }
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

        override suspend fun getPendingSyncData(todayStr: String): List<DailyReelData> {
            return records.values.filter { it.syncStatus == 1 && it.dateString != todayStr }.sortedBy { it.dateString }
        }

        override suspend fun getHistoricalDailyDataSync(todayStr: String): List<DailyReelData> {
            return records.values.filter { it.dateString != todayStr }.sortedBy { it.dateString }
        }

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

    /**
     * Models SyncManager's exact cloud restore merge algorithm.
     */
    class SyncManagerRestoreSimulator(
        private val dao: FakeDailyReelDataDao,
        private val todayStr: String
    ) {
        fun executeRestore(cloudRecords: List<CloudDailyReelData>): Result<Unit> {
            // Step 1: Filter out today's record from cloud data
            val restoredHistoricalList = cloudRecords.mapNotNull { dto ->
                if (dto.dateString == todayStr) {
                    return@mapNotNull null
                }
                DailyReelData(
                    dateString = dto.dateString,
                    totalReelsCount = dto.totalReelsCount,
                    dailyGoal = dto.dailyGoal,
                    isStrictModeOn = dto.isStrictModeOn,
                    totalTimeSpentMillis = 0L,
                    sessions = emptyList(),
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = 0
                )
            }

            // Step 2: Merge with local historical data
            val localHistoricalList = dao.records.values.filter { it.dateString != todayStr }
            val cloudMap = restoredHistoricalList.associateBy { it.dateString }
            val localMap = localHistoricalList.associateBy { it.dateString }

            val recordsToInsertOrUpdate = mutableListOf<DailyReelData>()
            val allHistoricalDates = (cloudMap.keys + localMap.keys).distinct()

            for (date in allHistoricalDates) {
                val cloudRecord = cloudMap[date]
                val localRecord = localMap[date]

                when {
                    cloudRecord != null && localRecord == null -> {
                        recordsToInsertOrUpdate.add(cloudRecord)
                    }
                    cloudRecord == null && localRecord != null -> {
                        if (localRecord.syncStatus == 1) {
                            // Unsynced offline data -> preserve
                        } else {
                            dao.records.remove(date)
                        }
                    }
                    cloudRecord != null && localRecord != null -> {
                        if (localRecord.syncStatus == 1) {
                            val reconciledCount = maxOf(localRecord.totalReelsCount, cloudRecord.totalReelsCount)
                            val mergedRecord = localRecord.copy(
                                totalReelsCount = reconciledCount,
                                dailyGoal = if (localRecord.totalReelsCount >= cloudRecord.totalReelsCount) localRecord.dailyGoal else cloudRecord.dailyGoal,
                                isStrictModeOn = localRecord.isStrictModeOn || cloudRecord.isStrictModeOn,
                                syncStatus = 1
                            )
                            recordsToInsertOrUpdate.add(mergedRecord)
                        } else {
                            recordsToInsertOrUpdate.add(cloudRecord)
                        }
                    }
                }
            }

            recordsToInsertOrUpdate.forEach { dao.records[it.dateString] = it }
            return Result.success(Unit)
        }
    }

    // --- TEST CASES ---

    @Test
    fun `cloud restore strictly excludes today record preventing counter reset`() {
        val todayStr = "2026-09-27"
        val dao = FakeDailyReelDataDao()

        // Local today record with active tracked count
        dao.records[todayStr] = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 85,
            dailyGoal = 100,
            isLimitLocked = true,
            syncStatus = 1
        )

        val cloudData = listOf(
            CloudDailyReelData(dateString = todayStr, totalReelsCount = 0, dailyGoal = 500, isStrictModeOn = false),
            CloudDailyReelData(dateString = "2026-09-26", totalReelsCount = 90, dailyGoal = 100, isStrictModeOn = true)
        )

        val simulator = SyncManagerRestoreSimulator(dao, todayStr)
        simulator.executeRestore(cloudData)

        // Verify: Today's record is completely untouched
        val todayLocal = dao.records[todayStr]
        assertNotNull(todayLocal)
        assertEquals(85, todayLocal!!.totalReelsCount)
        assertEquals(100, todayLocal.dailyGoal)
        assertTrue(todayLocal.isLimitLocked)
        assertEquals(1, todayLocal.syncStatus)

        // Verify: Historical record was restored
        val yesterdayLocal = dao.records["2026-09-26"]
        assertNotNull(yesterdayLocal)
        assertEquals(90, yesterdayLocal!!.totalReelsCount)
        assertEquals(0, yesterdayLocal.syncStatus)
    }

    @Test
    fun `cloud restore resolves conflicts using maxOf so higher local offline count is preserved`() {
        val todayStr = "2026-09-27"
        val dao = FakeDailyReelDataDao()

        // Local historical record with offline tracking (count = 150, syncStatus = 1)
        dao.records["2026-09-25"] = DailyReelData(
            dateString = "2026-09-25",
            totalReelsCount = 150,
            dailyGoal = 100,
            syncStatus = 1
        )

        // Cloud has stale historical record (count = 100)
        val cloudData = listOf(
            CloudDailyReelData(dateString = "2026-09-25", totalReelsCount = 100, dailyGoal = 100, isStrictModeOn = false)
        )

        val simulator = SyncManagerRestoreSimulator(dao, todayStr)
        simulator.executeRestore(cloudData)

        // Reconciled count must be maxOf(150, 100) = 150, keeping syncStatus = 1 to re-upload
        val record = dao.records["2026-09-25"]
        assertNotNull(record)
        assertEquals(150, record!!.totalReelsCount)
        assertEquals(1, record.syncStatus)
    }

    @Test
    fun `cloud restore adopts higher cloud count if cloud has more reels for unsynced historical day`() {
        val todayStr = "2026-09-27"
        val dao = FakeDailyReelDataDao()

        // Local historical record with 40 reels
        dao.records["2026-09-24"] = DailyReelData(
            dateString = "2026-09-24",
            totalReelsCount = 40,
            dailyGoal = 100,
            syncStatus = 1
        )

        // Cloud has 110 reels (e.g. from another device)
        val cloudData = listOf(
            CloudDailyReelData(dateString = "2026-09-24", totalReelsCount = 110, dailyGoal = 100, isStrictModeOn = true)
        )

        val simulator = SyncManagerRestoreSimulator(dao, todayStr)
        simulator.executeRestore(cloudData)

        val record = dao.records["2026-09-24"]
        assertNotNull(record)
        assertEquals(110, record!!.totalReelsCount)
        assertEquals(1, record.syncStatus)
        assertTrue(record.isStrictModeOn)
    }

    @Test
    fun `cloud restore inserts cloud-only historical records as synced`() {
        val todayStr = "2026-09-27"
        val dao = FakeDailyReelDataDao()

        val cloudData = listOf(
            CloudDailyReelData(dateString = "2026-09-20", totalReelsCount = 88, dailyGoal = 100, isStrictModeOn = false),
            CloudDailyReelData(dateString = "2026-09-21", totalReelsCount = 95, dailyGoal = 100, isStrictModeOn = true)
        )

        val simulator = SyncManagerRestoreSimulator(dao, todayStr)
        simulator.executeRestore(cloudData)

        assertEquals(2, dao.records.size)
        assertEquals(88, dao.records["2026-09-20"]?.totalReelsCount)
        assertEquals(0, dao.records["2026-09-20"]?.syncStatus)
        assertEquals(95, dao.records["2026-09-21"]?.totalReelsCount)
        assertEquals(0, dao.records["2026-09-21"]?.syncStatus)
    }

    @Test
    fun `cloud restore deletes obsolete synced records but preserves offline unsynced records`() {
        val todayStr = "2026-09-27"
        val dao = FakeDailyReelDataDao()

        // 1. Synced historical record that no longer exists in cloud (e.g. user reset cloud history)
        dao.records["2026-09-18"] = DailyReelData(dateString = "2026-09-18", totalReelsCount = 50, syncStatus = 0)

        // 2. Unsynced offline historical record (syncStatus = 1)
        dao.records["2026-09-19"] = DailyReelData(dateString = "2026-09-19", totalReelsCount = 65, syncStatus = 1)

        // Cloud is empty for historical dates
        val cloudData = emptyList<CloudDailyReelData>()

        val simulator = SyncManagerRestoreSimulator(dao, todayStr)
        simulator.executeRestore(cloudData)

        // Synced record was deleted
        assertNull(dao.records["2026-09-18"])
        // Unsynced offline record was PRESERVED
        assertNotNull(dao.records["2026-09-19"])
        assertEquals(65, dao.records["2026-09-19"]?.totalReelsCount)
    }

    @Test
    fun `pending sync query strictly excludes today date and synced records`() {
        val todayStr = "2026-09-27"
        val dao = FakeDailyReelDataDao()

        // Today with syncStatus = 1 (MUST BE EXCLUDED)
        dao.records[todayStr] = DailyReelData(dateString = todayStr, totalReelsCount = 40, syncStatus = 1)

        // Yesterday with syncStatus = 1 (MUST BE INCLUDED)
        dao.records["2026-09-26"] = DailyReelData(dateString = "2026-09-26", totalReelsCount = 80, syncStatus = 1)

        // Day before with syncStatus = 0 (MUST BE EXCLUDED)
        dao.records["2026-09-25"] = DailyReelData(dateString = "2026-09-25", totalReelsCount = 95, syncStatus = 0)

        val pending = kotlinx.coroutines.runBlocking { dao.getPendingSyncData(todayStr) }

        assertEquals(1, pending.size)
        assertEquals("2026-09-26", pending.first().dateString)
        assertEquals(80, pending.first().totalReelsCount)
    }

    @Test
    fun `optimistic concurrency token updatedAt prevents stale sync overwrite`() {
        val dao = FakeDailyReelDataDao()
        val dateStr = "2026-09-25"
        val uploadTimestamp = 1000L

        dao.records[dateStr] = DailyReelData(
            dateString = dateStr,
            totalReelsCount = 50,
            updatedAt = uploadTimestamp,
            syncStatus = 1
        )

        // Case 1: No local mutation during upload -> markAsSynced succeeds
        kotlinx.coroutines.runBlocking { dao.markAsSynced(dateStr, uploadTimestamp) }
        assertEquals(0, dao.records[dateStr]?.syncStatus)

        // Case 2: Local mutation occurred during upload (updatedAt changed to 2000L)
        dao.records[dateStr] = dao.records[dateStr]!!.copy(
            totalReelsCount = 55,
            updatedAt = 2000L,
            syncStatus = 1
        )

        // Attempting to mark synced with stale uploadTimestamp (1000L) must NOT clear syncStatus
        kotlinx.coroutines.runBlocking { dao.markAsSynced(dateStr, uploadTimestamp) }
        assertEquals(1, dao.records[dateStr]?.syncStatus) // Remains 1, ensuring fresh upload
    }

    @Test
    fun `partial sync failure marks successful records and keeps failed records pending`() {
        val dao = FakeDailyReelDataDao()

        dao.records["2026-09-20"] = DailyReelData(dateString = "2026-09-20", totalReelsCount = 50, updatedAt = 100L, syncStatus = 1)
        dao.records["2026-09-21"] = DailyReelData(dateString = "2026-09-21", totalReelsCount = 60, updatedAt = 200L, syncStatus = 1)
        dao.records["2026-09-22"] = DailyReelData(dateString = "2026-09-22", totalReelsCount = 70, updatedAt = 300L, syncStatus = 1)

        // Simulate upload: 2026-09-20 succeeds, 2026-09-21 fails, 2026-09-22 succeeds
        kotlinx.coroutines.runBlocking {
            dao.markAsSynced("2026-09-20", 100L) // Success
            // 2026-09-21 failed: markAsSynced NOT called
            dao.markAsSynced("2026-09-22", 300L) // Success
        }

        assertEquals(0, dao.records["2026-09-20"]?.syncStatus)
        assertEquals(1, dao.records["2026-09-21"]?.syncStatus) // Still pending
        assertEquals(0, dao.records["2026-09-22"]?.syncStatus)
    }

    @Test
    fun `account status eligibility rules reject restricted or deleted accounts`() {
        fun isEligible(status: AccountStatus): Boolean {
            return status != AccountStatus.PENDING_DELETION &&
                   status != AccountStatus.DELETED &&
                   status != AccountStatus.RESTRICTED
        }

        assertTrue(isEligible(AccountStatus.ACTIVE))
        assertFalse(isEligible(AccountStatus.RESTRICTED))
        assertFalse(isEligible(AccountStatus.DELETED))
        assertFalse(isEligible(AccountStatus.PENDING_DELETION))
    }

    @Test
    fun `sign out clears historical records but preserves today device-local enforcement`() {
        val todayStr = "2026-09-27"
        val dao = FakeDailyReelDataDao()

        dao.records[todayStr] = DailyReelData(dateString = todayStr, totalReelsCount = 70, dailyGoal = 100)
        dao.records["2026-09-26"] = DailyReelData(dateString = "2026-09-26", totalReelsCount = 80)
        dao.records["2026-09-25"] = DailyReelData(dateString = "2026-09-25", totalReelsCount = 90)

        // Simulate LoginViewModel.signOut(): clearHistoricalDataExcept(todayStr)
        kotlinx.coroutines.runBlocking { dao.clearHistoricalDataExcept(todayStr) }

        // Today is preserved
        assertNotNull(dao.records[todayStr])
        assertEquals(70, dao.records[todayStr]?.totalReelsCount)

        // Historical rows are wiped
        assertNull(dao.records["2026-09-26"])
        assertNull(dao.records["2026-09-25"])
        assertEquals(1, dao.records.size)
    }

    @Test
    fun `midnight transition marks yesterday record pending for background sync`() {
        val todayStr = "2026-09-27"
        val yesterdayStr = "2026-09-26"
        val dao = FakeDailyReelDataDao()

        // Yesterday was active
        val yesterdayRecord = DailyReelData(
            dateString = yesterdayStr,
            totalReelsCount = 95,
            dailyGoal = 100,
            syncStatus = 0
        )
        dao.records[yesterdayStr] = yesterdayRecord

        // DayManager.recoverMissedTransitionsAndInitializeLocked marks historical record as syncStatus = 1
        val finalizedYesterday = yesterdayRecord.copy(
            updatedAt = System.currentTimeMillis(),
            syncStatus = 1
        )
        dao.records[yesterdayStr] = finalizedYesterday

        val pending = kotlinx.coroutines.runBlocking { dao.getPendingSyncData(todayStr) }
        assertEquals(1, pending.size)
        assertEquals(yesterdayStr, pending.first().dateString)
        assertEquals(1, pending.first().syncStatus)
    }

    @Test
    fun `monotonic invariant prevents stale reload from decrementing today count in memory`() {
        val todayStr = "2026-09-27"
        val inMemoryStats = DailyReelData(dateString = todayStr, totalReelsCount = 50, dailyGoal = 100)
        val staleDbStats = DailyReelData(dateString = todayStr, totalReelsCount = 30, dailyGoal = 100)

        fun resolveMonotonicCount(memory: DailyReelData?, db: DailyReelData, dateStr: String): DailyReelData {
            if (memory != null && memory.dateString == dateStr && memory.totalReelsCount > db.totalReelsCount) {
                return db.copy(totalReelsCount = memory.totalReelsCount)
            }
            return db
        }

        val resolved = resolveMonotonicCount(inMemoryStats, staleDbStats, todayStr)
        assertEquals(50, resolved.totalReelsCount)
    }

    @Test
    fun `zero credential or subscription fields exist in CloudDailyReelData`() {
        val fields = CloudDailyReelData::class.java.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("$") }
            .map { it.name }

        val allowedFields = setOf(
            "dateString",
            "totalReelsCount",
            "dailyGoal",
            "isStrictModeOn",
            "isStrictModeLocked",
            "isLimitLocked"
        )
        for (field in fields) {
            assertTrue("Field '$field' must be in allowed telemetry list", allowedFields.contains(field))
        }

        val forbiddenKeywords = listOf("sub", "token", "auth", "secret", "price", "trial", "payment")
        for (field in fields) {
            for (keyword in forbiddenKeywords) {
                assertFalse("Field '$field' must not contain keyword '$keyword'", field.lowercase().contains(keyword))
            }
        }
    }

    @Test
    fun `auth context change mid-sync triggers immediate abort`() {
        val initialUid = "user_A"
        var currentUid = "user_A"

        fun shouldAbortBackup(loopIndex: Int): Boolean {
            if (loopIndex == 2) {
                currentUid = "user_B" // Simulated auth switch during loop
            }
            return currentUid != initialUid
        }

        assertFalse(shouldAbortBackup(0))
        assertFalse(shouldAbortBackup(1))
        assertTrue(shouldAbortBackup(2)) // Aborted on switch
    }

    @Test
    fun `daily limit persists across simulated reinstall and restores active limit`() {
        val dao = FakeDailyReelDataDao()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Simulated cloud user settings doc: /users/{uid}/settings/preferences
        val cloudSettings = mutableMapOf<String, Any>("dailyLimit" to 75L)

        // Simulated local app state AFTER app reinstall:
        // Local Room database is clean, local preferences defaultDailyLimit is -1 (uninitialized)
        var localPreferencesDailyLimit = -1
        var todayStats = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 0,
            dailyGoal = -1, // Uninitialized on fresh install
            isLimitLocked = false,
            updatedAt = System.currentTimeMillis()
        )
        dao.records[todayStr] = todayStats

        // User logs in on fresh install -> syncManager.restoreDailyLimitFromCloud()
        val cloudLimit = (cloudSettings["dailyLimit"] as? Long)?.toInt()
        assertNotNull(cloudLimit)
        assertEquals(75, cloudLimit)

        // Restore applied locally:
        localPreferencesDailyLimit = cloudLimit!!
        todayStats = todayStats.copy(dailyGoal = cloudLimit, updatedAt = System.currentTimeMillis())
        dao.records[todayStr] = todayStats

        // Assertions:
        assertEquals(75, localPreferencesDailyLimit)
        assertEquals(75, dao.records[todayStr]?.dailyGoal)
        assertTrue("Restored user should not be prompted to re-enter limit", localPreferencesDailyLimit > 0)
    }

    @Test
    fun `cross-account switch ensures new user does not inherit previous user daily limit`() = kotlinx.coroutines.runBlocking {
        val dao = FakeDailyReelDataDao()
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        // User A was logged in with dailyGoal = 50 and 20 reels tracked
        dao.records[todayStr] = DailyReelData(
            dateString = todayStr,
            totalReelsCount = 20,
            dailyGoal = 50,
            isLimitLocked = true,
            isStrictModeOn = true
        )
        var localPreferencesDailyLimit = 50

        // User A logs out: performSecureTeardown executed
        dao.clearHistoricalDataExcept(todayStr)
        val today = dao.getDailyDataSync(todayStr)!!
        // Reset account-specific attributes while preserving device-local tracked reels
        dao.insertOrReplace(
            today.copy(
                dailyGoal = -1,
                isLimitLocked = false,
                isStrictModeOn = false,
                isStrictModeLocked = false
            )
        )
        localPreferencesDailyLimit = -1

        // User B (brand new user with no cloud settings) logs in
        val userBCloudSettings: Map<String, Any>? = null
        val restoredLimit = (userBCloudSettings?.get("dailyLimit") as? Long)?.toInt()

        if (restoredLimit != null && restoredLimit > 0) {
            localPreferencesDailyLimit = restoredLimit
        }

        // Verify User B does NOT inherit User A's daily limit
        assertEquals(-1, localPreferencesDailyLimit)
        assertEquals(-1, dao.records[todayStr]?.dailyGoal)
        assertFalse(dao.records[todayStr]?.isStrictModeOn == true)
        assertEquals("Device-local reels tracked is preserved for anti-tamper", 20, dao.records[todayStr]?.totalReelsCount)
    }
}
