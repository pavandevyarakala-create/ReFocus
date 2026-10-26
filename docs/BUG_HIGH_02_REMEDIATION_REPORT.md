# BUG-HIGH-02 Remediation Report: Safe Cloud Restoration for Unsynced Offline History

**Issue ID:** BUG-HIGH-02  
**Date:** September 20, 2026  
**Status:** Remediated & Verified  

---

## 1. Executive Summary & Root Cause Analysis

### Root Cause
In `SyncManager.restoreFromCloud()`, the previous implementation executed:
```kotlin
dailyReelDataDao.clearHistoricalDataExcept(todayStr)
```
which invoked the Room SQL query:
```sql
DELETE FROM daily_reel_data WHERE dateString != :today
```
This blanket delete did not differentiate between synced (`syncStatus = 0`) and unsynced (`syncStatus = 1`) records. If a user had offline historical days (e.g. used the app offline for 2-3 days before connecting to Wi-Fi) and triggered "Restore Backup" from Settings or during sign-in, all local unsynced offline records were permanently destroyed before the cloud records were inserted.

---

## 2. Exact Files & Functions Changed

### A. Modified Component: `DailyReelDataDao.kt`
- **File:** `app/src/main/java/com/reeltrak/app/data/local/dao/DailyReelDataDao.kt`
- **Additions:**
  - Added `@Query("DELETE FROM daily_reel_data WHERE dateString = :dateStr") suspend fun deleteByDate(dateStr: String)` to support surgical deletion of specific obsolete records without wiping the entire table.
  - Added `@Query("DELETE FROM daily_reel_data WHERE syncStatus = 0 AND dateString != :todayStr") suspend fun deleteHistoricalSyncedExcept(todayStr: String)` for selective synced-only deletions.

### B. Modified Component: `SyncManager.kt`
- **File:** `app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt`
- **Function Modified:** `restoreFromCloud()`
  - Removed blanket `dailyReelDataDao.clearHistoricalDataExcept(todayStr)`.
  - Implemented a deterministic safe merge algorithm that respects `syncStatus`:
    1. **Today's record exclusion:** Any remote record with `dateString == todayStr` is excluded from cloud restore, preserving the local device enforcement row.
    2. **Cloud-only records (`cloudRecord != null && localRecord == null`):** Inserted into Room with `syncStatus = 0`.
    3. **Local-only records (`cloudRecord == null && localRecord != null`):**
       - If `syncStatus == 1` (unsynced offline data), the record is **strictly preserved** so `SyncWorker` can upload it later.
       - If `syncStatus == 0` (obsolete synced record removed remotely), the row is deleted via `dailyReelDataDao.deleteByDate(date)`.
    4. **Conflict resolution (`cloudRecord != null && localRecord != null`):**
       - If `localRecord.syncStatus == 1`, local offline usage is reconciled with cloud usage by taking `maxOf(localRecord.totalReelsCount, cloudRecord.totalReelsCount)`, preserving `isStrictModeOn`, and retaining `syncStatus = 1` so the updated merged state syncs upstream to Firestore.
       - If `localRecord.syncStatus == 0`, the authoritative cloud record replaces the local row with `syncStatus = 0`.
    5. **Transaction Batching:** All reconciled/new records are committed via `dailyReelDataDao.insertAll(recordsToInsertOrUpdate)`.

### C. New Test Suite: `BugHigh02RemediationTest.kt`
- **File:** `app/src/test/java/com/reeltrak/app/security/BugHigh02RemediationTest.kt`
- **Tests Added:**
  - `testSyncManager_DoesNotCallClearHistoricalDataExceptInRestore`
  - `testDailyReelDataDao_HasSafeDeleteQueries`
  - `testRequirementA_OfflineHistoryPreservation`
  - `testRequirementB_CloudRestorationWithoutDuplication`
  - `testRequirementC_LocalUnsyncedAndCloudConflict_DeterministicMaxCount`
  - `testRequirementD_LocalUnsyncedAbsentFromCloud_Survives`
  - `testRequirementE_TodayRowUntouchedThroughRestore`
  - `testRequirementF_AccountIsolation_UserAToUserB`
  - `testRequirementG_RepeatedRestoreIdempotency`
  - `testRequirementH_ExistingSyncedDataReplacedByCloud`

---

## 3. Merge Strategy & Status Matrix

| Local State | Cloud State | Merge Action | Resulting `syncStatus` | Rationale |
| :--- | :--- | :--- | :---: | :--- |
| None | Record exists | Insert cloud record | `0` | Clean sync from remote backup |
| `syncStatus = 1` (Unsynced) | None | **Preserve local row** | `1` | Offline history must never be discarded |
| `syncStatus = 0` (Synced) | None | Delete local row | N/A | Row was deleted or not present in user's cloud account |
| `syncStatus = 1` (Unsynced) | Record exists | Merge: `maxOf(local, cloud)` | `1` | Reconciles offline usage without loss; triggers upstream sync |
| `syncStatus = 0` (Synced) | Record exists | Overwrite with cloud record | `0` | Authoritative cloud update |
| Today's Row | Any | **Untouched** | Device State | Device-local enforcement invariant |

---

## 4. Failure & Resilience Analysis

- **Process Kill / Network Drop During Fetch:** `dailyDataSnapshot` fetch happens before any local database mutation. A failure during network fetch terminates cleanly in `catch (e: Exception)` without modifying or deleting local Room records.
- **Mid-Operation Auth Change:** The check `currentUid != uid` verifies that the active authenticated user hasn't changed before performing any Room updates.
- **Repeat Executions (Idempotency):** Executing `restoreFromCloud()` multiple times produces identical state without record duplication or corruption.

---

## 5. Verification & Test Results

- **Unit Tests (`testDebugUnitTest`):** `BUILD SUCCESSFUL` (All tests passing, including 10 new regression tests in `BugHigh02RemediationTest`).
- **Android Lint (`lintDebug`):** `BUILD SUCCESSFUL` (0 errors).
- **Kotlin Compilation (`compileDebugKotlin`):** `BUILD SUCCESSFUL`.
- **Debug APK Assembly (`assembleDebug`):** `BUILD SUCCESSFUL`.
- **Scoped Verification:**
  - `TrustedTimeManager.kt` was **NOT** modified.
  - `SubscriptionRepositoryImpl.kt` and payment code were **NOT** modified.
  - Detector engine and accessibility services were **NOT** modified.
  - BUG-CRIT-02 changes remain intact.

---

## 6. Final Assessment

BUG-HIGH-02 is fully remediated. Offline historical records with `syncStatus = 1` are guaranteed to survive cloud restoration while preserving today's device-local enforcement and account isolation invariants.
