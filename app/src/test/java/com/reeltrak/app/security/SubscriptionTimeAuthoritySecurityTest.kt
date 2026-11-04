package com.reeltrak.app.security

import android.content.Context
import com.google.firebase.Timestamp
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionStatus
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionTimeAuthoritySecurityTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        // Clear preferences before each test to ensure fresh isolated state
        context.getSharedPreferences("subscription_security_time_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testRecordServerTimeAnchor_calibratesClockAndResetsOfflineReboots() {
        val authority = SubscriptionTimeAuthority(context, httpClient)
        assertFalse("Initially uncalibrated", authority.isCalibrated.value)

        val serverNow = 1756720800000L
        authority.recordServerTimeAnchor(serverNow)

        assertTrue("Calibrated after recording server anchor", authority.isCalibrated.value)
        val (currentTime, isCalibrated) = authority.getAuthoritativeSubscriptionTime()
        assertTrue(isCalibrated)
        assertTrue(currentTime >= serverNow)
        assertEquals(0, authority.getOfflineRebootCount())
    }

    @Test
    fun testOfflineLease_within24Hours_returnsTrue() {
        val authority = SubscriptionTimeAuthority(context, httpClient)
        val serverNow = 1756720800000L
        authority.recordServerTimeAnchor(serverNow)

        // 5 hours have passed since sync
        val syncTime = serverNow - TimeUnit.HOURS.toMillis(5)
        val isWithinLease = authority.isWithinOfflineLease(syncTime)
        assertTrue("5 hours offline is within 24h lease", isWithinLease)
    }

    @Test
    fun testOfflineLease_exceeding24Hours_failsClosed() {
        val authority = SubscriptionTimeAuthority(context, httpClient)
        val serverNow = 1756720800000L
        authority.recordServerTimeAnchor(serverNow)

        // 25 hours have passed since sync
        val syncTime = serverNow - TimeUnit.HOURS.toMillis(25)
        val isWithinLease = authority.isWithinOfflineLease(syncTime)
        assertFalse("25 hours offline must exceed 24h lease and fail closed", isWithinLease)
    }

    @Test
    fun testUncalibratedPostReboot_advancesMonotonically_neverFreezes() {
        val authority = SubscriptionTimeAuthority(context, httpClient)
        val serverNow = 1756720800000L
        authority.recordServerTimeAnchor(serverNow)

        // Simulate reboot by creating a new authority instance with stored anchor
        // and simulating elapsed realtime reset
        val prefs = context.getSharedPreferences("subscription_security_time_prefs", Context.MODE_PRIVATE)
        // Store anchor with a higher last known uptime to trigger reboot detection
        prefs.edit()
            .putLong("server_time_anchor", serverNow)
            .putLong("elapsed_realtime_anchor", 500000L)
            .putLong("high_water_time", serverNow)
            .putLong("last_known_uptime", 600000L)
            .putLong("last_system_time", System.currentTimeMillis())
            .commit()

        val rebootedAuthority = SubscriptionTimeAuthority(context, httpClient)
        assertFalse("Must be uncalibrated after reboot", rebootedAuthority.isCalibrated.value)
        assertEquals("Must detect offline reboot", 1, rebootedAuthority.getOfflineRebootCount())

        val (authoritativeTime, isCalibrated) = rebootedAuthority.getAuthoritativeSubscriptionTime()
        assertFalse(isCalibrated)
        assertTrue("Monotonic time post-reboot must be >= server high-water mark", authoritativeTime >= serverNow)
    }

    @Test
    fun testExcessiveOfflineReboots_exceedingThreshold_failsClosed() {
        val authority = SubscriptionTimeAuthority(context, httpClient)
        val serverNow = 1756720800000L
        authority.recordServerTimeAnchor(serverNow)

        val prefs = context.getSharedPreferences("subscription_security_time_prefs", Context.MODE_PRIVATE)
        // Simulate exceeding MAX_OFFLINE_REBOOTS_WITHOUT_SYNC (2)
        prefs.edit()
            .putInt("offline_reboot_count", 3)
            .putLong("high_water_time", serverNow)
            .commit()

        // Create uncalibrated authority
        prefs.edit()
            .putLong("elapsed_realtime_anchor", 999999999L)
            .putLong("last_known_uptime", 999999999L)
            .commit()

        val uncalibratedAuthority = SubscriptionTimeAuthority(context, httpClient)
        assertFalse(uncalibratedAuthority.isCalibrated.value)

        // Within 1 hour of sync, but excessive reboots have occurred without server calibration
        val syncTime = serverNow - TimeUnit.HOURS.toMillis(1)
        val isAllowed = uncalibratedAuthority.isWithinOfflineLease(syncTime)
        assertFalse("Excessive unverified offline reboots must fail closed", isAllowed)
    }

    @Test
    fun testClockRollback_failsClosedImmediately() {
        val authority = SubscriptionTimeAuthority(context, httpClient)
        // Anchor at a future server time
        val futureServerTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)
        authority.recordServerTimeAnchor(futureServerTime)

        // System wall clock is now 10 days behind high-water mark (simulates user rolling clock back)
        val syncTime = futureServerTime - TimeUnit.HOURS.toMillis(1)
        val isAllowed = authority.isWithinOfflineLease(syncTime)
        assertFalse("Clock rollback behind high-water mark must fail closed", isAllowed)
    }

    @Test
    fun testCloudSubscriptionDto_haltedStatus_locksImmediatelyEvenWithFuturePeriodEnd() {
        val now = 1756720800000L
        val futureEnd = now + TimeUnit.DAYS.toMillis(15)

        // Document has HALTED status with a future periodEnd (e.g. gateway payment halted/exhausted retries)
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "HALTED",
            planId = "plan_monthly",
            subscriptionId = "sub_halted_123",
            currentPeriodStart = Timestamp(Date(now - TimeUnit.DAYS.toMillis(15))),
            currentPeriodEnd = Timestamp(Date(futureEnd)),
            premium = false
        )

        val domain = dto.toDomain(authoritativeNow = now)
        assertEquals(SubscriptionStatus.HALTED, domain.status)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("HALTED status must immediately lock the app", domain.isUnlocked)
    }

    @Test
    fun testCloudSubscriptionDto_expiredPeriodEnd_locksImmediately() {
        val now = 1756720800000L
        val pastEnd = now - TimeUnit.HOURS.toMillis(5)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE", // Stale ACTIVE status in doc
            planId = "plan_monthly",
            subscriptionId = "sub_expired_123",
            currentPeriodStart = Timestamp(Date(now - TimeUnit.DAYS.toMillis(35))),
            currentPeriodEnd = Timestamp(Date(pastEnd)),
            premium = true // Stale boolean
        )

        val domain = dto.toDomain(authoritativeNow = now)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Past periodEnd must strictly lock access regardless of stale active status", domain.isUnlocked)
    }

    // =========================================================================
    // DETERMINISTIC TIME MODEL REGRESSION TESTS (Requirements 1 - 10)
    // =========================================================================

    class TestClock(var elapsed: Long = 1000L, var wall: Long = 1756720800000L)

    class TestableSubscriptionTimeAuthority(
        context: Context,
        httpClient: OkHttpClient,
        val clock: TestClock
    ) : SubscriptionTimeAuthority(
        context,
        httpClient,
        { clock.elapsed },
        { clock.wall }
    ) {
        var mockElapsedRealtime: Long
            get() = clock.elapsed
            set(v) { clock.elapsed = v }

        var mockSystemTime: Long
            get() = clock.wall
            set(v) { clock.wall = v }

        constructor(
            context: Context,
            httpClient: OkHttpClient,
            mockElapsedRealtime: Long = 1000L,
            mockSystemTime: Long = 1756720800000L
        ) : this(context, httpClient, TestClock(mockElapsedRealtime, mockSystemTime))
    }

    @Test
    fun testRegression_multipleCallsWithinOneBoot_doNotCompoundElapsedTime() {
        val serverTime = 1756720800000L
        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 10_000L,
            mockSystemTime = serverTime
        )
        authority.recordServerTimeAnchor(serverTime)

        // Advance elapsed by 5 seconds
        authority.mockElapsedRealtime = 15_000L
        val expectedTime = serverTime + 5_000L

        // Call getAuthoritativeSubscriptionTime 50 times in rapid succession
        for (i in 1..50) {
            val (calculatedTime, isCalibrated) = authority.getAuthoritativeSubscriptionTime()
            assertTrue("Must remain calibrated", isCalibrated)
            assertEquals("Call #$i must not compound uptime repeatedly", expectedTime, calculatedTime)
        }
    }

    @Test
    fun testRegression_timeAdvancesByApproximatelyRealElapsedTime() {
        val serverTime = 1756720800000L
        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 10_000L,
            mockSystemTime = serverTime
        )
        authority.recordServerTimeAnchor(serverTime)

        val deltas = listOf(1_000L, 5_000L, 60_000L, 3_600_000L)
        var currentElapsed = 10_000L
        for (delta in deltas) {
            currentElapsed += delta
            authority.mockElapsedRealtime = currentElapsed
            val (calculatedTime, _) = authority.getAuthoritativeSubscriptionTime()
            assertEquals("Time must advance by exact real elapsed duration", serverTime + (currentElapsed - 10_000L), calculatedTime)
        }
    }

    @Test
    fun testRegression_repeatedCallsAtSameElapsedTime_doNotAdvanceClock() {
        val serverTime = 1756720800000L
        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 20_000L,
            mockSystemTime = serverTime
        )
        authority.recordServerTimeAnchor(serverTime)

        authority.mockElapsedRealtime = 35_000L
        val (firstCallTime, _) = authority.getAuthoritativeSubscriptionTime()
        val (secondCallTime, _) = authority.getAuthoritativeSubscriptionTime()
        val (thirdCallTime, _) = authority.getAuthoritativeSubscriptionTime()

        assertEquals("First and second calls at same elapsed time must match", firstCallTime, secondCallTime)
        assertEquals("Second and third calls at same elapsed time must match", secondCallTime, thirdCallTime)
    }

    @Test
    fun testRegression_processRecreationWithinSameBoot_preservesCorrectProgression() {
        val serverTime = 1756720800000L
        val authority1 = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 10_000L,
            mockSystemTime = serverTime
        )
        authority1.recordServerTimeAnchor(serverTime)

        // Time progresses in process 1 to 25_000L (15s delta)
        authority1.mockElapsedRealtime = 25_000L
        val (timeBeforeDeath, isCal1) = authority1.getAuthoritativeSubscriptionTime()
        assertTrue(isCal1)
        assertEquals(serverTime + 15_000L, timeBeforeDeath)

        // Process recreation at 30_000L (5s later, same boot because 30_000L > 25_000L)
        val authority2 = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 30_000L,
            mockSystemTime = serverTime + 20_000L
        )
        assertTrue("Process recreation in same boot must retain calibration", authority2.isCalibrated.value)
        val (timeAfterRecreation, isCal2) = authority2.getAuthoritativeSubscriptionTime()
        assertTrue(isCal2)
        assertEquals("Progression after recreation must reflect exact elapsed delta (20s)", serverTime + 20_000L, timeAfterRecreation)
    }

    @Test
    fun testRegression_rebootWithoutTrustedTimeAnchor_followsExplicitSafeOfflinePolicy() {
        // Fresh install: no anchor recorded at all
        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 5_000L,
            mockSystemTime = 1756720800000L
        )
        assertFalse(authority.isCalibrated.value)
        val (time, isCal) = authority.getAuthoritativeSubscriptionTime()
        assertEquals("Without trusted anchor, authoritative time must be 0L", 0L, time)
        assertFalse("Without trusted anchor, clock must be uncalibrated", isCal)
        assertFalse("Without trusted anchor, offline lease must fail closed", authority.isWithinOfflineLease(1756720800000L))
    }

    @Test
    fun testRegression_wallClockRollbackAndFastForward_cannotExtendAccess() {
        val serverTime = 1756720800000L
        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 10_000L,
            mockSystemTime = serverTime
        )
        authority.recordServerTimeAnchor(serverTime)

        // Rollback: wall clock shifted behind high-water by 2 hours
        authority.mockSystemTime = serverTime - TimeUnit.HOURS.toMillis(2)
        assertFalse("Wall clock rollback must fail closed", authority.isWithinOfflineLease(serverTime))

        // Fast-forward: wall clock shifted ahead by 5 days (beyond 24h lease)
        authority.mockSystemTime = serverTime + TimeUnit.DAYS.toMillis(5)
        assertFalse("Wall clock fast-forward past 24h lease must fail closed", authority.isWithinOfflineLease(serverTime))
    }

    @Test
    fun testRegression_offlineLeaseExpiresAtCorrectBoundary() {
        val serverTime = 1756720800000L
        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 10_000L,
            mockSystemTime = serverTime
        )
        authority.recordServerTimeAnchor(serverTime)

        // Exactly 24 hours minus 1 second
        val syncTime = serverTime
        authority.mockElapsedRealtime = 10_000L + SubscriptionTimeAuthority.MAX_OFFLINE_LEASE_MS - 1000L
        authority.mockSystemTime = serverTime + SubscriptionTimeAuthority.MAX_OFFLINE_LEASE_MS - 1000L
        assertTrue("Inside 24-hour lease must return true", authority.isWithinOfflineLease(syncTime))

        // Exceeded 24 hours by 1 second
        authority.mockElapsedRealtime = 10_000L + SubscriptionTimeAuthority.MAX_OFFLINE_LEASE_MS + 1000L
        authority.mockSystemTime = serverTime + SubscriptionTimeAuthority.MAX_OFFLINE_LEASE_MS + 1000L
        assertFalse("Exceeding 24-hour lease boundary must fail closed", authority.isWithinOfflineLease(syncTime))
    }

    @Test
    fun testRegression_validPaidAccessPreservedInsideLegitimateOfflineLease() {
        val serverTime = 1756720800000L
        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 10_000L,
            mockSystemTime = serverTime
        )
        authority.recordServerTimeAnchor(serverTime)

        // User was synced 8 hours ago, paid period ends in 20 days
        val lastSync = serverTime - TimeUnit.HOURS.toMillis(8)
        val periodEnd = serverTime + TimeUnit.DAYS.toMillis(20)
        assertTrue("Legitimate 8 hours offline is within lease", authority.isWithinOfflineLease(lastSync))

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            subscriptionId = "sub_paid_valid_1",
            currentPeriodStart = Timestamp(Date(serverTime - TimeUnit.DAYS.toMillis(10))),
            currentPeriodEnd = Timestamp(Date(periodEnd)),
            premium = true
        )
        val domain = dto.toDomain(authoritativeNow = serverTime, isOfflineLeaseExpired = false)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue("Valid paid subscription inside offline lease remains unlocked", domain.isUnlocked)
    }

    @Test
    fun testRegression_expiredRevokedInvalidUntrustedStates_failClosed() {
        val serverTime = 1756720800000L
        val pastEnd = serverTime - TimeUnit.HOURS.toMillis(1)

        // 1. Expired periodEnd
        val expiredDto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(pastEnd)),
            premium = true
        )
        val domainExpired = expiredDto.toDomain(authoritativeNow = serverTime)
        assertEquals(AccessState.LOCKED, domainExpired.accessState)
        assertFalse(domainExpired.isUnlocked)

        // 2. Revoked/Halted
        val haltedDto = CloudSubscriptionDto(
            subscriptionStatus = "HALTED",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(serverTime + TimeUnit.DAYS.toMillis(10))),
            premium = true
        )
        val domainHalted = haltedDto.toDomain(authoritativeNow = serverTime)
        assertEquals(AccessState.LOCKED, domainHalted.accessState)
        assertFalse(domainHalted.isUnlocked)

        // 3. Lease expired
        val leaseExpiredDto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            currentPeriodEnd = Timestamp(Date(serverTime + TimeUnit.DAYS.toMillis(10))),
            premium = true
        )
        val domainLeaseExpired = leaseExpiredDto.toDomain(authoritativeNow = serverTime, isOfflineLeaseExpired = true)
        assertEquals(AccessState.LOCKED, domainLeaseExpired.accessState)
        assertFalse(domainLeaseExpired.isUnlocked)
    }

    @Test
    fun testRegression_missingOrCorruptedPersistedState_cannotMintNewLease() {
        val prefs = context.getSharedPreferences("subscription_security_time_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("server_time_anchor", 0L)
            .putLong("elapsed_realtime_anchor", 0L)
            .putLong("high_water_time", 0L)
            .putLong("boot_session_base_time", 0L)
            .putLong("boot_session_elapsed_baseline", 0L)
            .putInt("offline_reboot_count", 0)
            .commit()

        val authority = TestableSubscriptionTimeAuthority(
            context, httpClient,
            mockElapsedRealtime = 5_000L,
            mockSystemTime = 1756720800000L
        )
        val (time, isCal) = authority.getAuthoritativeSubscriptionTime()
        assertEquals(0L, time)
        assertFalse(isCal)
        assertFalse("Corrupted/missing state cannot mint a valid lease", authority.isWithinOfflineLease(1756720800000L))
    }
}
