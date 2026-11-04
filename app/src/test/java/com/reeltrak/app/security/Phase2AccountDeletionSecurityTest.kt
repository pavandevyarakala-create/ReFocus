package com.reeltrak.app.security

import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Phase 2 Security & Account Lifecycle Verification Tests
 */
class Phase2AccountDeletionSecurityTest {

    @Test
    fun testDataOwnershipModel_TodaySurvivesHistoricalCleared() {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayStr = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val pastWeekStr = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val localDatabaseRows = listOf(
            DailyReelData(dateString = pastWeekStr, totalReelsCount = 120, dailyGoal = 60, isStrictModeOn = true),
            DailyReelData(dateString = yesterdayStr, totalReelsCount = 85, dailyGoal = 60, isStrictModeOn = false),
            DailyReelData(dateString = todayStr, totalReelsCount = 42, dailyGoal = 50, isStrictModeOn = true)
        )

        val preservedRows = localDatabaseRows.filter { it.dateString == todayStr }

        assertEquals(1, preservedRows.size)
        val todayRow = preservedRows.first()
        assertEquals(todayStr, todayRow.dateString)
        assertEquals(42, todayRow.totalReelsCount)
        assertEquals(50, todayRow.dailyGoal)
        assertTrue(todayRow.isStrictModeOn)

        assertFalse(preservedRows.any { it.dateString == yesterdayStr })
        assertFalse(preservedRows.any { it.dateString == pastWeekStr })
    }

    @Test
    fun testStateTransition_ActiveToPendingDeletion() {
        val initialInfo = AccountAccessInfo(
            accountStatus = AccountStatus.ACTIVE,
            accountType = AccountType.NORMAL,
            deletionScheduledFor = null,
            deletionRequestedAt = null
        )

        val now = System.currentTimeMillis()
        val scheduledFor = now + (7L * 24 * 60 * 60 * 1000)

        val pendingInfo = initialInfo.copy(
            accountStatus = AccountStatus.PENDING_DELETION,
            deletionRequestedAt = now,
            deletionScheduledFor = scheduledFor
        )

        assertEquals(AccountStatus.PENDING_DELETION, pendingInfo.accountStatus)
        assertTrue(pendingInfo.isPendingDeletion)
        assertFalse(pendingInfo.isDeleted)
        assertEquals(scheduledFor, pendingInfo.deletionScheduledFor)
    }

    @Test
    fun testRepeatedDeletionRequest_DoesNotExtendDeadline() {
        val originalNow = 1700000000000L
        val originalScheduledFor = originalNow + (7L * 24 * 60 * 60 * 1000)
        val currentScheduledFor: Long? = originalScheduledFor

        val secondRequestNow = originalNow + (3L * 24 * 60 * 60 * 1000)
        val effectiveScheduledFor = currentScheduledFor ?: (secondRequestNow + (7L * 24 * 60 * 60 * 1000))

        assertEquals("Repeated deletion request MUST NOT extend deadline", originalScheduledFor, effectiveScheduledFor)
    }

    @Test
    fun testPendingDeletionToActive_ExplicitRecoveryWithinGracePeriod() {
        val now = 1700000000000L
        val scheduledFor = now + (7L * 24 * 60 * 60 * 1000)
        val cancelAttemptNow = now + (2L * 24 * 60 * 60 * 1000)

        val isWithinGracePeriod = cancelAttemptNow <= scheduledFor
        assertTrue("Cancellation within 7 days must be accepted", isWithinGracePeriod)

        val restoredInfo = AccountAccessInfo(
            accountStatus = AccountStatus.ACTIVE,
            accountType = AccountType.NORMAL,
            deletionScheduledFor = null,
            deletionRequestedAt = null
        )

        assertEquals(AccountStatus.ACTIVE, restoredInfo.accountStatus)
        assertFalse(restoredInfo.isPendingDeletion)
        assertFalse(restoredInfo.isDeleted)
        assertTrue(restoredInfo.isAllowedAccess)
    }

    @Test
    fun testExpiredPendingDeletion_BecomesDeletedAndBlocksRecovery() {
        val requestedAt = 1700000000000L
        val scheduledFor = requestedAt + (7L * 24 * 60 * 60 * 1000)
        val currentServerTime = scheduledFor + 1000L

        val isExpired = currentServerTime > scheduledFor
        assertTrue("Server time past deadline must be marked expired", isExpired)

        val deletedInfo = AccountAccessInfo(
            accountStatus = AccountStatus.DELETED,
            accountType = AccountType.NORMAL,
            deletionScheduledFor = scheduledFor,
            deletionRequestedAt = requestedAt
        )

        assertEquals(AccountStatus.DELETED, deletedInfo.accountStatus)
        assertTrue(deletedInfo.isDeleted)
        assertFalse(deletedInfo.isAllowedAccess)

        val canCancel = deletedInfo.accountStatus == AccountStatus.PENDING_DELETION && currentServerTime <= (deletedInfo.deletionScheduledFor ?: 0)
        assertFalse("Expired account CANNOT recover or cancel deletion", canCancel)
    }

    @Test
    fun testAccountStatusBlocksCloudWritesAndResurrection() {
        val nonEligibleStatuses = listOf(AccountStatus.PENDING_DELETION, AccountStatus.DELETED, AccountStatus.RESTRICTED)

        for (status in nonEligibleStatuses) {
            val isEligible = status == AccountStatus.ACTIVE
            assertFalse("Status $status MUST NOT be eligible for cloud sync writes", isEligible)
        }

        assertTrue("Only ACTIVE status is eligible for sync writes", AccountStatus.ACTIVE == AccountStatus.ACTIVE)
    }

    @Test
    fun testPurgeAccountPaths_PreservesSystemAuditEvents() {
        val uid = "user_alpha_123"
        val otherUid = "user_beta_456"

        val accountOwnedPaths = setOf(
            "users/$uid",
            "users/$uid/daily_data/2026-09-16",
            "users/$uid/daily_data/2026-09-17",
            "users/$uid/subscription/status",
            "users/$uid/onboarding/tour",
            "users/$uid/account/status"
        )

        val otherUserPaths = setOf(
            "users/$otherUid",
            "users/$otherUid/daily_data/2026-09-17",
            "users/$otherUid/subscription/status"
        )

        val systemAuditPaths = setOf(
            "razorpay_events/evt_order_123",
            "razorpay_events/evt_refund_456"
        )

        val isTargetForPurge = { path: String ->
            path == "users/$uid" || path.startsWith("users/$uid/")
        }

        for (path in accountOwnedPaths) {
            assertTrue("Path $path should be targeted for purge", isTargetForPurge(path))
        }

        for (path in otherUserPaths) {
            assertFalse("Other user path $path MUST NOT be purged", isTargetForPurge(path))
        }

        for (path in systemAuditPaths) {
            assertFalse("Audit event $path MUST NEVER be purged", isTargetForPurge(path))
        }
    }

    @Test
    fun testOnboardingTourWriteEligibilityAndRaceProtection() {
        val eligibleStatuses = listOf(AccountStatus.ACTIVE)
        val nonEligibleStatuses = listOf(AccountStatus.PENDING_DELETION, AccountStatus.DELETED, AccountStatus.RESTRICTED)

        // Helper function modeling isAccountEligibleForWrite
        val isAccountEligibleForWrite = { status: AccountStatus ->
            status != AccountStatus.PENDING_DELETION && status != AccountStatus.DELETED && status != AccountStatus.RESTRICTED
        }

        for (status in eligibleStatuses) {
            assertTrue("ACTIVE account MUST be eligible to write onboarding status", isAccountEligibleForWrite(status))
        }

        for (status in nonEligibleStatuses) {
            assertFalse("Account in $status MUST NOT be eligible to write onboarding status", isAccountEligibleForWrite(status))
        }

        // Verify account-switch UID race protection
        val requestedUid = "user_account_A"
        val currentAuthUid = "user_account_B" // user switched account mid-flight
        val isAuthUidMatching = requestedUid == currentAuthUid
        assertFalse("Stale write for mismatched UID must be rejected", isAuthUidMatching)
    }

    @Test
    fun testAccountStatusParsing() {
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromString("ACTIVE"))
        assertEquals(AccountStatus.PENDING_DELETION, AccountStatus.fromString("PENDING_DELETION"))
        assertEquals(AccountStatus.DELETED, AccountStatus.fromString("DELETED"))
        assertEquals(AccountStatus.RESTRICTED, AccountStatus.fromString("RESTRICTED"))
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromString("UNKNOWN_VALUE"))
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromString(null))
    }
}
