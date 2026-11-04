package com.reeltrak.app.security

import com.google.firebase.Timestamp
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.OperationalMode
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * End-to-End Regression Test Suite for Premium Entitlement System:
 *
 * 1. Single consistent entitlement decision path: future paid expiry authoritatively grants AccessState.ACTIVE.
 * 2. Status reconciliation handles contradictory server states (EXPIRED, NONE, PREMIUM, PRO, CANCELLED).
 * 3. Robust field alias extraction: currentPeriodEnd, expiryDate, expiresAt, expirationDate, premiumExpiry, etc.
 * 4. Robust timestamp deserialization: Timestamp, Date, Number, Map (seconds/nanoseconds), and ISO/date strings.
 * 5. Persistent server sync timestamp per UID preserves legitimate offline cache access within the 24-hour lease.
 * 6. Expired offline lease fails closed to AccessState.LOCKED.
 * 7. HALTED status revokes entitlement immediately.
 * 8. Settings page cleanly excises Daily Limit setting.
 */
class SubscriptionEntitlementEndToEndTest {

    private fun findSourceFile(relativePath: String): File {
        val currentDir = File(".").absoluteFile
        val found = currentDir.walkTopDown().firstOrNull { it.path.replace("\\", "/").endsWith(relativePath) }
        if (found != null && found.exists()) return found
        throw AssertionError("Could not locate source file: $relativePath across workspace")
    }

    // =========================================================================
    // 1. Core Entitlement Reconciliation Tests
    // =========================================================================

    @Test
    fun testPremiumExpiryInFuture_WithContradictoryExpiredStatus_ResolvesToActive() {
        val nowMs = 1750000000000L
        val futureEndMs = nowMs + (30L * 24 * 3600 * 1000L) // 30 days in future

        // Contradictory server document: status is EXPIRED, but currentPeriodEnd is in future
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(futureEndMs)),
            premium = true
        )

        val info = dto.toDomain(authoritativeNow = nowMs, isOfflineLeaseExpired = false)

        assertEquals("Contradictory EXPIRED status must reconcile to ACTIVE when periodEnd is in future",
            SubscriptionStatus.ACTIVE, info.status)
        assertEquals(AccessState.ACTIVE, info.accessState)
        assertTrue(info.isUnlocked)
        assertTrue(info.isPremium)
        assertTrue(info.hasActiveEntitlement)
        assertEquals(SubscriptionPlan.MONTHLY, info.plan)
    }

    @Test
    fun testPremiumExpiryInFuture_WithStatusNoneOrUnset_ResolvesToActive() {
        val nowMs = 1750000000000L
        val futureEndMs = nowMs + (15L * 24 * 3600 * 1000L)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = null,
            currentPeriodEnd = Timestamp(Date(futureEndMs))
        )

        val info = dto.toDomain(authoritativeNow = nowMs, isOfflineLeaseExpired = false)

        assertEquals(SubscriptionStatus.ACTIVE, info.status)
        assertEquals(AccessState.ACTIVE, info.accessState)
        assertTrue(info.isUnlocked)
        assertEquals(SubscriptionPlan.MONTHLY, info.plan)
    }

    @Test
    fun testPremiumExpiryInFuture_WithStatusPremiumOrPro_ResolvesToActive() {
        val nowMs = 1750000000000L
        val futureEndMs = nowMs + (20L * 24 * 3600 * 1000L)

        for (statusName in listOf("PREMIUM", "premium", "PRO", "pro", "PAID", "paid")) {
            val dto = CloudSubscriptionDto(
                subscriptionStatus = statusName,
                planId = "plan_monthly",
                currentPeriodEnd = Timestamp(Date(futureEndMs))
            )
            val info = dto.toDomain(authoritativeNow = nowMs, isOfflineLeaseExpired = false)

            assertEquals("Status '$statusName' must resolve to ACTIVE", SubscriptionStatus.ACTIVE, info.status)
            assertEquals("Status '$statusName' must grant AccessState.ACTIVE", AccessState.ACTIVE, info.accessState)
            assertTrue(info.isUnlocked)
        }
    }

    @Test
    fun testPremiumExpiryInFuture_WithCancelledStatus_PreservesActiveAccessUntilPeriodEnd() {
        val nowMs = 1750000000000L
        val futureEndMs = nowMs + (10L * 24 * 3600 * 1000L)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "CANCELLED",
            cancelAtPeriodEnd = true,
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(futureEndMs))
        )

        val info = dto.toDomain(authoritativeNow = nowMs, isOfflineLeaseExpired = false)

        assertEquals("Cancelled subscription must retain CANCELLED status", SubscriptionStatus.CANCELLED, info.status)
        assertEquals("Cancelled subscription with future end must grant AccessState.ACTIVE", AccessState.ACTIVE, info.accessState)
        assertTrue(info.isUnlocked)
        assertTrue(info.cancelAtPeriodEnd)
    }

    @Test
    fun testPremiumExpiryInPast_ResolvesToLocked() {
        val nowMs = 1750000000000L
        val pastEndMs = nowMs - (10L * 24 * 3600 * 1000L) // 10 days ago

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(pastEndMs))
        )

        val info = dto.toDomain(authoritativeNow = nowMs, isOfflineLeaseExpired = false)

        assertEquals(AccessState.LOCKED, info.accessState)
        assertFalse(info.isUnlocked)
        assertFalse(info.isPremium)
    }

    @Test
    fun testStatusHalted_AlwaysResolvesToLocked_EvenWithFutureDate() {
        val nowMs = 1750000000000L
        val futureEndMs = nowMs + (30L * 24 * 3600 * 1000L)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "HALTED",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(futureEndMs))
        )

        val info = dto.toDomain(authoritativeNow = nowMs, isOfflineLeaseExpired = false)

        assertEquals(SubscriptionStatus.HALTED, info.status)
        assertEquals(AccessState.LOCKED, info.accessState)
        assertFalse(info.isUnlocked)
    }

    // =========================================================================
    // 2. Field Name Aliases and Format Parsing Tests
    // =========================================================================

    @Test
    fun testFieldAliases_ExpiryDate_ExpiresAt_CurrentPeriodEnd_ParsedEqually() {
        val futureMs = 1800000000000L

        val aliasKeys = listOf(
            "currentPeriodEnd",
            "currentPeriodEndDate",
            "current_period_end",
            "expiryDate",
            "expiry_date",
            "expiresAt",
            "expires_at",
            "expirationDate",
            "expirationTime",
            "premiumExpiry",
            "premiumUntil",
            "validUntil",
            "subscriptionEndDate",
            "periodEnd",
            "endDate"
        )

        for (key in aliasKeys) {
            val map = mapOf<String, Any?>(
                "subscriptionStatus" to "ACTIVE",
                key to Timestamp(Date(futureMs))
            )
            val dto = CloudSubscriptionDto.fromMap(map)
            assertNotNull("Field '$key' must be parsed as currentPeriodEnd", dto.currentPeriodEnd)
            assertEquals("Field '$key' must match timestamp milliseconds",
                futureMs, dto.currentPeriodEnd?.toDate()?.time)

            val info = dto.toDomain(authoritativeNow = 1750000000000L)
            assertEquals("Field '$key' must grant ACTIVE access", AccessState.ACTIVE, info.accessState)
        }
    }

    @Test
    fun testTimestampFormat_MapStructure_ParsedCorrectly() {
        val seconds = 1800000000L
        val expectedMs = seconds * 1000L

        // Standard Firestore REST map format: {"seconds": 1800000000, "nanoseconds": 0}
        val mapTimestamp = mapOf("seconds" to seconds, "nanoseconds" to 0)
        val docData = mapOf<String, Any?>(
            "subscriptionStatus" to "ACTIVE",
            "currentPeriodEnd" to mapTimestamp
        )

        val dto = CloudSubscriptionDto.fromMap(docData)
        assertNotNull(dto.currentPeriodEnd)
        assertEquals(expectedMs, dto.currentPeriodEnd?.toDate()?.time)

        // Admin SDK map format: {"_seconds": 1800000000, "_nanoseconds": 0}
        val adminMap = mapOf("_seconds" to seconds, "_nanoseconds" to 0)
        val adminDocData = mapOf<String, Any?>(
            "subscriptionStatus" to "ACTIVE",
            "expiresAt" to adminMap
        )

        val adminDto = CloudSubscriptionDto.fromMap(adminDocData)
        assertNotNull(adminDto.currentPeriodEnd)
        assertEquals(expectedMs, adminDto.currentPeriodEnd?.toDate()?.time)
    }

    @Test
    fun testTimestampFormat_IsoStringAndNumericEpoch_ParsedCorrectly() {
        // ISO-8601 string
        val isoData = mapOf<String, Any?>(
            "subscriptionStatus" to "ACTIVE",
            "expiryDate" to "2027-01-01T00:00:00Z"
        )
        val isoDto = CloudSubscriptionDto.fromMap(isoData)
        assertNotNull(isoDto.currentPeriodEnd)
        assertTrue(isoDto.currentPeriodEnd!!.toDate().time > 1700000000000L)

        // Numeric epoch milliseconds as Long
        val millisData = mapOf<String, Any?>(
            "subscriptionStatus" to "ACTIVE",
            "expiresAt" to 1800000000000L
        )
        val millisDto = CloudSubscriptionDto.fromMap(millisData)
        assertNotNull(millisDto.currentPeriodEnd)
        assertEquals(1800000000000L, millisDto.currentPeriodEnd?.toDate()?.time)

        // Numeric epoch seconds as Long
        val secData = mapOf<String, Any?>(
            "subscriptionStatus" to "ACTIVE",
            "expires_at" to 1800000000L
        )
        val secDto = CloudSubscriptionDto.fromMap(secData)
        assertNotNull(secDto.currentPeriodEnd)
        assertEquals(1800000000000L, secDto.currentPeriodEnd?.toDate()?.time)
    }

    // =========================================================================
    // 3. Offline Lease and Sync Persistence Tests
    // =========================================================================

    @Test
    fun testOfflineLease_Within24HoursPreservesActiveCachedAccess() {
        val authority = SubscriptionTimeAuthority()
        val anchorTimeMs = 1750000000000L
        authority.recordServerTimeAnchor(anchorTimeMs)

        // Synchronized 6 hours ago
        val lastSyncMs = anchorTimeMs - (6L * 3600 * 1000L)
        assertTrue("Sync within 6 hours must be within offline lease",
            authority.isWithinOfflineLease(lastSyncMs))

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(anchorTimeMs + (20L * 24 * 3600 * 1000L)))
        )

        val info = dto.toDomain(
            authoritativeNow = anchorTimeMs,
            isOfflineLeaseExpired = !authority.isWithinOfflineLease(lastSyncMs),
            lastServerVerificationMs = lastSyncMs
        )

        assertEquals("Valid cached access within 24h lease must remain ACTIVE",
            AccessState.ACTIVE, info.accessState)
        assertTrue(info.isUnlocked)
    }

    @Test
    fun testOfflineLease_Beyond24HoursFailsClosedToLocked() {
        val authority = SubscriptionTimeAuthority()
        val anchorTimeMs = 1750000000000L
        authority.recordServerTimeAnchor(anchorTimeMs)

        // Synchronized 25 hours ago
        val lastSyncMs = anchorTimeMs - (25L * 3600 * 1000L)
        assertFalse("Sync older than 24 hours must be marked expired",
            authority.isWithinOfflineLease(lastSyncMs))

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(anchorTimeMs + (20L * 24 * 3600 * 1000L)))
        )

        val info = dto.toDomain(
            authoritativeNow = anchorTimeMs,
            isOfflineLeaseExpired = !authority.isWithinOfflineLease(lastSyncMs),
            lastServerVerificationMs = lastSyncMs
        )

        assertEquals("Expired offline lease must fail closed to AccessState.LOCKED",
            AccessState.LOCKED, info.accessState)
        assertFalse(info.isUnlocked)
    }

    // =========================================================================
    // 4. Settings Page Daily Limit Removal Verification
    // =========================================================================

    @Test
    fun testSettingsScreen_DoesNotContainDailyLimitRowOrDialogs() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/settings/ui/SettingsScreen.kt")
        val content = file.readText()

        assertFalse("SettingsScreen must not render Daily Reel Limit row",
            content.contains("\"Daily Reel Limit\""))
        assertFalse("SettingsScreen must not render DailyLimitSelectionDialog",
            content.contains("DailyLimitSelectionDialog("))
        assertFalse("SettingsScreen must not render DailyLimitEntitlementDialog",
            content.contains("DailyLimitEntitlementDialog("))
    }

    @Test
    fun testDashboardAndTrackingEngine_PreserveDailyLimitAndGoalGating() {
        // Ensure Daily Goal on Dashboard is preserved
        val dashboardFile = findSourceFile("src/main/java/com/reeltrak/app/features/dashboard/ui/DashboardScreen.kt")
        val dashboardContent = dashboardFile.readText()
        assertTrue("DashboardScreen must render DailyLimitEntitlementDialog",
            dashboardContent.contains("DailyLimitEntitlementDialog("))

        // Ensure DayManager preserves daily goal tracking and gating
        val dayManagerFile = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val dayManagerContent = dayManagerFile.readText()
        assertTrue("DayManager must support setDailyGoal",
            dayManagerContent.contains("fun setDailyGoal"))
    }
}
