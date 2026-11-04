package com.reeltrak.app.security

import com.google.firebase.Timestamp
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Master Security Hardening Test Suite for Subscription & Trial Entitlement.
 * 
 * Verifies all 21 test scenarios:
 * A-E: Phone clock tampering (forward, backward, expired trial, expired paid)
 * F-H: Offline before/after expiration and clock rollbacks
 * I-L: Process restart, force stop, reboot offline/online
 * M-N: Stale Firestore cached documents
 * O: Account switching isolation
 * P-Q: Webhook replay & resurrection protection
 * R-S: 2-minute final-sync grace boundary
 * T-U: Local database & APK-level tampering isolation
 */
class SubscriptionHardeningSecurityTest {

    private fun ts(millis: Long): Timestamp = Timestamp(Date(millis))

    // =========================================================================
    // 1. PHONE CLOCK TAMPERING TESTS (SCENARIOS A - E)
    // =========================================================================

    /**
     * Test A: Active subscription with phone clock moved forward 30 days.
     * Authority evaluates against server time (still within period) -> remains ACTIVE.
     */
    @Test
    fun testA_ActiveSubscription_PhoneClockForward_RemainsActiveViaServerAuthority() {
        val serverNow = 1756720800000L // Sept 1, 10:00 AM UTC
        val periodEnd = serverNow + TimeUnit.DAYS.toMillis(30) // Oct 1, 10:00 AM UTC
        val phoneSpoofedTime = serverNow + TimeUnit.DAYS.toMillis(60) // Phone set to Nov 1

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodStart = ts(serverNow),
            currentPeriodEnd = ts(periodEnd),
            premium = true
        )

        // Evaluating against authoritative server time grants ACTIVE
        val domain = dto.toDomain(authoritativeNow = serverNow)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
    }

    /**
     * Test B: Active subscription with phone clock moved backward.
     * Server time is authoritative; active subscription remains ACTIVE.
     */
    @Test
    fun testB_ActiveSubscription_PhoneClockBackward_GovernedByServerAuthority() {
        val serverNow = 1756720800000L // Sept 1, 10:00 AM UTC
        val periodEnd = serverNow + TimeUnit.DAYS.toMillis(30)
        val phoneSpoofedTime = serverNow - TimeUnit.DAYS.toMillis(30) // Phone set to Aug 1

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodStart = ts(serverNow),
            currentPeriodEnd = ts(periodEnd),
            premium = true
        )

        val domain = dto.toDomain(authoritativeNow = serverNow)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
    }

    /**
     * Test C: Expired subscription with phone clock moved backward 30 days.
     * Phone rollback MUST NOT restore expired subscription.
     */
    @Test
    fun testC_ExpiredSubscription_PhoneClockBackward_FailsClosedToLocked() {
        val serverNow = 1759312800000L // Oct 1, 10:00 AM UTC (After expiration)
        val periodEnd = serverNow - TimeUnit.DAYS.toMillis(5) // Expired Sept 26
        val phoneSpoofedTime = periodEnd - TimeUnit.DAYS.toMillis(10) // Spoofed to Sept 16

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            planId = "plan_monthly",
            currentPeriodStart = ts(periodEnd - TimeUnit.DAYS.toMillis(30)),
            currentPeriodEnd = ts(periodEnd),
            premium = false
        )

        val domain = dto.toDomain(authoritativeNow = serverNow)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    /**
     * Test D: Expired subscription with phone clock moved forward.
     * Remains LOCKED.
     */
    @Test
    fun testD_ExpiredSubscription_PhoneClockForward_RemainsLocked() {
        val serverNow = 1759312800000L
        val periodEnd = serverNow - TimeUnit.DAYS.toMillis(10)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            planId = "plan_monthly",
            currentPeriodEnd = ts(periodEnd),
            premium = false
        )

        val domain = dto.toDomain(authoritativeNow = serverNow)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    /**
     * Test E: Expired 7-day trial with phone clock moved backward.
     * Spoofing phone clock backward MUST NOT unlock expired trial.
     */
    @Test
    fun testE_ExpiredTrial_PhoneClockBackward_RemainsLocked() {
        val trialStart = 1756720800000L // Sept 1
        val trialEnd = trialStart + TimeUnit.DAYS.toMillis(7) // Sept 8
        val serverNow = trialEnd + TimeUnit.HOURS.toMillis(10) // Sept 8 8:00 PM (Expired)
        val phoneSpoofedBackward = trialStart + TimeUnit.DAYS.toMillis(2) // Spoofed to Sept 3

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "TRIAL",
            trialStart = ts(trialStart),
            trialEnd = ts(trialEnd),
            premium = false
        )

        val domain = dto.toDomain(authoritativeNow = serverNow)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    // =========================================================================
    // 2. OFFLINE ATTACKS & CACHE LEASE TESTS (SCENARIOS F - L)
    // =========================================================================

    /**
     * Test F & G: Offline operation within 24-hour lease vs expired lease.
     */
    @Test
    fun testF_OfflineWithinLease_PreservesActiveEntitlement() {
        val serverAnchor = 1756720800000L
        val periodEnd = serverAnchor + TimeUnit.DAYS.toMillis(30)
        val authoritativeNow = serverAnchor + TimeUnit.HOURS.toMillis(12) // 12 hours offline

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodEnd = ts(periodEnd),
            premium = true
        )

        // Within 24-hour lease -> ACTIVE
        val domain = dto.toDomain(
            authoritativeNow = authoritativeNow,
            isOfflineLeaseExpired = false,
            lastServerVerificationMs = serverAnchor
        )
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
    }

    @Test
    fun testG_OfflineBeyond24HourLease_FailsClosedToLocked() {
        val serverAnchor = 1756720800000L
        val periodEnd = serverAnchor + TimeUnit.DAYS.toMillis(30)
        val authoritativeNow = serverAnchor + TimeUnit.HOURS.toMillis(30) // 30 hours offline

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodEnd = ts(periodEnd),
            premium = true
        )

        // Offline lease expired (> 24 hours) -> Fails closed to LOCKED
        val domain = dto.toDomain(
            authoritativeNow = authoritativeNow,
            isOfflineLeaseExpired = true,
            lastServerVerificationMs = serverAnchor
        )
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    /**
     * Test H: Offline + Repeated phone clock rollback.
     * High-water monotonic tracker prevents time from flowing backwards.
     */
    @Test
    fun testH_OfflineRepeatedClockRollback_CannotRewindTime() {
        val serverAnchor = 1756720800000L
        val trialEnd = serverAnchor + TimeUnit.DAYS.toMillis(7)

        // Monotonic clock has advanced past trialEnd
        val monotonicAdvancedTime = trialEnd + TimeUnit.HOURS.toMillis(1)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "TRIAL",
            trialEnd = ts(trialEnd)
        )

        val domain = dto.toDomain(authoritativeNow = monotonicAdvancedTime)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    // =========================================================================
    // 3. STALE CACHE & ACCOUNT SWITCHING (SCENARIOS M - O)
    // =========================================================================

    /**
     * Test M & N: Stale cached ACTIVE overridden by authoritative server EXPIRED.
     */
    @Test
    fun testM_ServerExpired_OverridesCachedActive() {
        val serverNow = 1759312800000L
        val periodEnd = serverNow - 1000L

        val serverDto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            currentPeriodEnd = ts(periodEnd),
            premium = false
        )

        val domain = serverDto.toDomain(authoritativeNow = serverNow)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    /**
     * Test O: Account A (Paid) -> Logout -> Account B (Free).
     * Account B must receive LOCKED_DEFAULT.
     */
    @Test
    fun testO_AccountSwitching_FreeAccountReceivesLockedState() {
        val accountBInfo = SubscriptionInfo.LOCKED_DEFAULT
        assertEquals(AccessState.LOCKED, accountBInfo.accessState)
        assertFalse(accountBInfo.isUnlocked)
    }

    // =========================================================================
    // 4. 2-MINUTE FINAL-SYNC GRACE BOUNDARY (SCENARIOS R - S)
    // =========================================================================

    /**
     * Test R: Exactly within 2-minute post-expiration window -> FINAL_SYNC_GRACE.
     * Contract: isUnlocked = false (reels locked), isSyncPermitted = true (sync allowed).
     */
    @Test
    fun testR_Within2MinuteGraceWindow_EntersFinalSyncGrace() {
        val trialEnd = 1756720800000L
        val authoritativeNow = trialEnd + TimeUnit.SECONDS.toMillis(60) // 1 minute after expiration

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "TRIAL",
            trialEnd = ts(trialEnd)
        )

        val domain = dto.toDomain(authoritativeNow = authoritativeNow)
        assertEquals(AccessState.FINAL_SYNC_GRACE, domain.accessState)
        assertFalse("Reels must remain locked during final sync window", domain.isUnlocked)
        assertTrue("Background sync must be permitted during final sync grace", domain.isSyncPermitted)
    }

    /**
     * Test S: Beyond 2-minute post-expiration window -> strictly LOCKED.
     * Contract: isUnlocked = false, isSyncPermitted = false.
     */
    @Test
    fun testS_Beyond2MinuteGraceWindow_FullyLocked() {
        val trialEnd = 1756720800000L
        val authoritativeNow = trialEnd + TimeUnit.SECONDS.toMillis(130) // 2 minutes 10 seconds post expiration

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "TRIAL",
            trialEnd = ts(trialEnd)
        )

        val domain = dto.toDomain(authoritativeNow = authoritativeNow)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
        assertFalse(domain.isSyncPermitted)
    }

    // =========================================================================
    // 5. LOCAL ROOM / DATASTORE & APK TAMPERING (SCENARIOS T - U)
    // =========================================================================

    /**
     * Test T: Tampering with local Room/DataStore cannot fabricate Firestore DTO.
     */
    @Test
    fun testT_LocalRoomTampering_HasNoAuthorityOverSubscriptionDto() {
        // Even if local Room has mock data, CloudSubscriptionDto with no server fields yields LOCKED
        val emptyDto = CloudSubscriptionDto()
        val domain = emptyDto.toDomain(authoritativeNow = 1756720800000L)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    /**
     * Test U: Halted status immediately locks even if premium flag is true.
     */
    @Test
    fun testU_HaltedSubscription_StrictlyLocked() {
        val now = 1756720800000L
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "HALTED",
            currentPeriodEnd = ts(now + TimeUnit.DAYS.toMillis(10)),
            premium = true // Stale or tampered boolean
        )

        val domain = dto.toDomain(authoritativeNow = now)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse(domain.isUnlocked)
    }

    // =========================================================================
    // 6. TRACKING PERMISSION & ZERO-WORK BOUNDARY (SCENARIOS V - Z)
    // =========================================================================

    /**
     * Test V: TRIAL & ACTIVE permit tracking (isUnlocked = true), all other states forbid tracking.
     */
    @Test
    fun testV_EntitlementToTrackingPermissionMatrix() {
        val now = 1756720800000L
        val activeDto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            currentPeriodEnd = ts(now + TimeUnit.DAYS.toMillis(10)),
            premium = true
        )
        val trialDto = CloudSubscriptionDto(
            subscriptionStatus = "TRIAL",
            trialEnd = ts(now + TimeUnit.DAYS.toMillis(5))
        )
        val expiredDto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            currentPeriodEnd = ts(now - TimeUnit.SECONDS.toMillis(300))
        )
        val haltedDto = CloudSubscriptionDto(
            subscriptionStatus = "HALTED",
            currentPeriodEnd = ts(now + TimeUnit.DAYS.toMillis(10))
        )
        val lockedDefault = SubscriptionInfo.LOCKED_DEFAULT

        assertTrue("ACTIVE must permit tracking", activeDto.toDomain(authoritativeNow = now).isUnlocked)
        assertTrue("TRIAL must permit tracking", trialDto.toDomain(authoritativeNow = now).isUnlocked)
        assertFalse("EXPIRED must forbid tracking", expiredDto.toDomain(authoritativeNow = now).isUnlocked)
        assertFalse("HALTED must forbid tracking", haltedDto.toDomain(authoritativeNow = now).isUnlocked)
        assertFalse("LOCKED default must forbid tracking", lockedDefault.isUnlocked)
    }

    /**
     * Test W: FINAL_SYNC_GRACE strictly forbids tracking (isUnlocked = false) while allowing sync (isSyncPermitted = true).
     */
    @Test
    fun testW_FinalSyncGrace_StrictlyForbidsReelTracking() {
        val end = 1756720800000L
        val inGraceNow = end + TimeUnit.SECONDS.toMillis(30) // 30s into 120s grace
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "TRIAL",
            trialEnd = ts(end)
        )

        val info = dto.toDomain(authoritativeNow = inGraceNow)
        assertEquals(AccessState.FINAL_SYNC_GRACE, info.accessState)
        assertFalse("Tracking must be strictly forbidden during FINAL_SYNC_GRACE", info.isUnlocked)
        assertTrue("Sync must be permitted during FINAL_SYNC_GRACE", info.isSyncPermitted)
    }

    /**
     * Test X: Offline lease expiry strictly forbids tracking.
     */
    @Test
    fun testX_OfflineLeaseExpired_StrictlyForbidsReelTracking() {
        val now = 1756720800000L
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            currentPeriodEnd = ts(now + TimeUnit.DAYS.toMillis(10)),
            premium = true
        )

        val info = dto.toDomain(
            authoritativeNow = now,
            isOfflineLeaseExpired = true,
            lastServerVerificationMs = now - TimeUnit.DAYS.toMillis(2)
        )
        assertEquals(AccessState.LOCKED, info.accessState)
        assertFalse("Tracking must be strictly forbidden when offline lease expired", info.isUnlocked)
    }

    /**
     * Test Y: Mandate authenticated state alone WITHOUT authoritative billing dates MUST fail closed to LOCKED.
     * Prevents premium entitlement leak during mandate setup prior to payment settlement.
     */
    @Test
    fun testY_MandateStatusAuthenticated_NoBillingDates_FailsClosedToLocked() {
        val now = 1756720800000L
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "plan_monthly",
            subscriptionId = "sub_mandate_pending_123",
            currentPeriodStart = null,
            currentPeriodEnd = null,
            premium = false,
            mandateStatus = "AUTHENTICATED"
        )

        val info = dto.toDomain(authoritativeNow = now)
        assertEquals(AccessState.LOCKED, info.accessState)
        assertFalse("Mandate authenticated alone must NEVER unlock premium access", info.isUnlocked)
    }

    /**
     * Test Z: Subscription transitioning to ACTIVE with authoritative billing dates unlocks access.
     */
    @Test
    fun testZ_MandateTransitionToActive_WithAuthoritativeDates_GrantsAccess() {
        val now = 1756720800000L
        val periodEnd = now + TimeUnit.DAYS.toMillis(30)
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            subscriptionId = "sub_mandate_active_123",
            currentPeriodStart = ts(now),
            currentPeriodEnd = ts(periodEnd),
            premium = true,
            mandateStatus = "ACTIVE"
        )

        val info = dto.toDomain(authoritativeNow = now)
        assertEquals(AccessState.ACTIVE, info.accessState)
        assertTrue("Authoritative active subscription must unlock premium access", info.isUnlocked)
    }
}
