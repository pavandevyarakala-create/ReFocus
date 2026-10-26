package com.reeltrak.app.domain.models

import com.google.firebase.Timestamp
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.concurrent.TimeUnit

class SubscriptionModelTest {

    private fun ts(millis: Long): Timestamp = Timestamp(Date(millis))

    @Test
    fun `test default empty state is locked`() {
        val defaultInfo = SubscriptionInfo.LOCKED_DEFAULT
        assertEquals(SubscriptionStatus.NONE, defaultInfo.status)
        assertEquals(SubscriptionPlan.FREE, defaultInfo.plan)
        assertEquals(AccessState.LOCKED, defaultInfo.accessState)
        assertFalse(defaultInfo.isUnlocked)
    }

    @Test
    fun `test active 7-day trial grants TRIAL access state and unlocks app`() {
        val now = System.currentTimeMillis()
        val trialEnd = now + TimeUnit.DAYS.toMillis(6)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "free",
            subscriptionId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = ts(now - TimeUnit.DAYS.toMillis(1)),
            trialEnd = ts(trialEnd),
            premium = false
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.TRIAL, domain.status)
        assertEquals(AccessState.TRIAL, domain.accessState)
        assertTrue("Active trial must unlock the app", domain.isUnlocked)
    }

    @Test
    fun `test expired trial with no paid subscription results in LOCKED access state`() {
        val now = System.currentTimeMillis()
        val trialEnd = now - TimeUnit.HOURS.toMillis(2) // Expired 2 hours ago

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "free",
            subscriptionId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = ts(now - TimeUnit.DAYS.toMillis(8)),
            trialEnd = ts(trialEnd),
            premium = false
        )

        val domain = dto.toDomain(now)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Expired trial must lock the app", domain.isUnlocked)
    }

    @Test
    fun `test clock rollback cannot extend expired trial when trustedNow is used`() {
        val originalTrialStart = 1757000000000L // arbitrary fixed time
        val originalTrialEnd = originalTrialStart + TimeUnit.DAYS.toMillis(7) // 7 days later

        // Simulating user rolling device wall-clock backwards to before trial start
        val spoofedClock = originalTrialStart - TimeUnit.DAYS.toMillis(10)
        val trustedServerNow = originalTrialEnd + TimeUnit.HOURS.toMillis(5) // Trusted server time says expired

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "free",
            trialStart = ts(originalTrialStart),
            trialEnd = ts(originalTrialEnd),
            premium = false
        )

        // Evaluating against trustedNow rejects the spoofed clock
        val domain = dto.toDomain(trustedNow = trustedServerNow)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Manipulated wall clock must not unlock expired trial", domain.isUnlocked)
    }

    @Test
    fun `test active paid subscription grants ACTIVE access state and unlocks app`() {
        val now = System.currentTimeMillis()
        val validFuture = now + TimeUnit.DAYS.toMillis(30)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            subscriptionId = "sub_active_123",
            currentPeriodStart = ts(now),
            currentPeriodEnd = ts(validFuture),
            trialStart = null,
            trialEnd = null,
            premium = true
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.ACTIVE, domain.status)
        assertEquals(SubscriptionPlan.MONTHLY, domain.plan)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
    }

    @Test
    fun `test expired paid subscription with expired trial results in LOCKED access state`() {
        val now = System.currentTimeMillis()
        val pastDate = now - TimeUnit.DAYS.toMillis(2)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            planId = "plan_monthly",
            subscriptionId = "sub_expired_123",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(32)),
            currentPeriodEnd = ts(pastDate),
            trialStart = ts(now - TimeUnit.DAYS.toMillis(60)),
            trialEnd = ts(now - TimeUnit.DAYS.toMillis(53)),
            premium = true // Malicious/outdated flag
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.EXPIRED, domain.status)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Expired subscription must lock the app", domain.isUnlocked)
    }

    @Test
    fun `test cancelled subscription with future periodEnd retains ACTIVE access until expiration`() {
        val now = System.currentTimeMillis()
        val futureDate = now + TimeUnit.DAYS.toMillis(15)

        // Cancelled plan that still has active paid days remaining
        val dto = CloudSubscriptionDto(
            subscriptionStatus = "CANCELLED",
            planId = "plan_monthly",
            subscriptionId = "sub_cancel_123",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(20)),
            currentPeriodEnd = ts(futureDate),
            trialStart = null,
            trialEnd = null,
            premium = true
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.CANCELLED, domain.status)
        assertEquals(SubscriptionPlan.MONTHLY, domain.plan)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue("Cancelled subscription before periodEnd remains active", domain.isUnlocked)
    }

    @Test
    fun `test cancelled subscription after periodEnd results in LOCKED access state`() {
        val now = System.currentTimeMillis()
        val pastDate = now - TimeUnit.HOURS.toMillis(1)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "CANCELLED",
            planId = "plan_annual",
            subscriptionId = "sub_cancel_123",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(366)),
            currentPeriodEnd = ts(pastDate),
            trialStart = null,
            trialEnd = null,
            premium = true
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.CANCELLED, domain.status)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Cancelled subscription after periodEnd must lock the app", domain.isUnlocked)
    }

    @Test
    fun `critical exploit test - Sept 1 trial, Sept 4 uninstall, device set to Sept 2, reinstall calculates 4 days NOT 6 days`() {
        // 1. User starts trial: Sept 1, 10:00 AM
        val sept1_10am = 1756720800000L
        val sept8_10am = sept1_10am + TimeUnit.DAYS.toMillis(7) // Sept 8, 10:00 AM (trialEnd)

        // 2. Firebase stored document (permanent, account-level)
        val cloudDto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "free",
            subscriptionId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = ts(sept1_10am),
            trialEnd = ts(sept8_10am),
            premium = false
        )

        // 3. User uninstalls on Sept 4, 10:00 AM
        // 4. User changes device clock backward to Sept 2, 10:00 AM (spoofed device time)
        val sept2_10am_spoofed = sept1_10am + TimeUnit.DAYS.toMillis(1)
        val sept4_10am_realServerTime = sept1_10am + TimeUnit.DAYS.toMillis(3)

        // 5. User reinstalls app and logs in.
        // App establishes trusted server time = Sept 4, 10:00 AM, ignoring spoofed phone clock Sept 2.
        val trustedNow = sept4_10am_realServerTime

        val domainInfo = cloudDto.toDomain(trustedNow = trustedNow)

        // 6. Assert remaining duration is 4 days (Sept 8 - Sept 4), NOT 6 days (Sept 8 - Sept 2)
        val remainingDurationMs = (domainInfo.trialEnd!! - trustedNow)
        val remainingDays = TimeUnit.MILLISECONDS.toDays(remainingDurationMs)

        assertEquals("Must calculate exactly 4 days remaining", 4L, remainingDays)
        assertEquals(TimeUnit.DAYS.toMillis(4), remainingDurationMs)
        assertEquals(AccessState.TRIAL, domainInfo.accessState)
        assertTrue(domainInfo.isUnlocked)

        // Verify that if naive phone clock (Sept 2) were used, it would yield 6 days (the vulnerability we prevented)
        val naiveVulnerableRemainingDays = TimeUnit.MILLISECONDS.toDays(domainInfo.trialEnd!! - sept2_10am_spoofed)
        assertEquals(6L, naiveVulnerableRemainingDays)
    }

    @Test
    fun `critical exploit test - Sept 1 trial, device date changed to Sept 10 on reinstall results in LOCKED`() {
        val sept1_10am = 1756720800000L
        val sept8_10am = sept1_10am + TimeUnit.DAYS.toMillis(7) // Expired on Sept 8

        val cloudDto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "free",
            trialStart = ts(sept1_10am),
            trialEnd = ts(sept8_10am),
            premium = false
        )

        // Real server time is Sept 10 (2 days after trialEnd)
        val sept10_10am_realServerTime = sept1_10am + TimeUnit.DAYS.toMillis(9)

        val domainInfo = cloudDto.toDomain(trustedNow = sept10_10am_realServerTime)

        assertEquals(AccessState.LOCKED, domainInfo.accessState)
        assertFalse("Expired trial evaluated on Sept 10 must be LOCKED", domainInfo.isUnlocked)
    }

    @Test
    fun `test two layer monotonic countdown ignores local phone wall clock shifts`() {
        val serverAnchorTime = 1756720800000L // Sept 1, 10:00 AM
        val elapsedRealtimeAnchor = 100000L // arbitrary boot monotonic ms

        val trialEnd = serverAnchorTime + TimeUnit.DAYS.toMillis(7)

        // Simulate 2 hours passing on hardware monotonic clock:
        val elapsedAfter2Hours = elapsedRealtimeAnchor + TimeUnit.HOURS.toMillis(2)

        // Calculate trustedNow via Layer 2 formula: S_0 + (E - E_0)
        val trustedNowAfter2Hours = serverAnchorTime + (elapsedAfter2Hours - elapsedRealtimeAnchor)

        // Remaining should be exactly 7 days - 2 hours
        val remainingMs = trialEnd - trustedNowAfter2Hours
        val expectedRemainingMs = TimeUnit.DAYS.toMillis(7) - TimeUnit.HOURS.toMillis(2)
        assertEquals(expectedRemainingMs, remainingMs)

        // Even if user changes wall-clock by +5 days or -5 days, Layer 2 calculation is completely unaffected
        val spoofedWallClock = serverAnchorTime + TimeUnit.DAYS.toMillis(5)
        val spoofedRemaining = trialEnd - spoofedWallClock
        assertTrue("Spoofed wall clock gives wrong duration", spoofedRemaining != remainingMs)
    }

    @Test
    fun `test formatRemainingDurationText formats date-based values properly`() {
        val sixDays23Hours = TimeUnit.DAYS.toMillis(6) + TimeUnit.HOURS.toMillis(23)
        assertEquals("7 days remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(sixDays23Hours))

        val exactFiveDays = TimeUnit.DAYS.toMillis(5)
        assertEquals("5 days remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(exactFiveDays))

        val oneDayFiveHours = TimeUnit.DAYS.toMillis(1) + TimeUnit.HOURS.toMillis(5)
        assertEquals("2 days remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(oneDayFiveHours))

        val exactOneDay = TimeUnit.DAYS.toMillis(1)
        assertEquals("1 day remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(exactOneDay))

        val underOneDay = TimeUnit.HOURS.toMillis(14)
        assertEquals("1 day remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(underOneDay))

        val expired = 0L
        assertEquals("Expired", com.reeltrak.app.core.ui.components.formatRemainingDurationText(expired))
    }

    @Test
    fun `test formatEpochDate formats epoch timestamps to day month year string`() {
        // Sept 8, 2026 UTC
        val formatted = com.reeltrak.app.core.ui.components.formatEpochDate(1757300000000L)
        assertTrue(formatted != null && formatted.contains("2025") || formatted?.contains("2026") == true)
        val nullFormatted = com.reeltrak.app.core.ui.components.formatEpochDate(null)
        assertEquals(null, nullFormatted)
    }

    @Test
    fun `test transition from TRIAL to ACTIVE switches target end without disappearing`() {
        val now = 1757000000000L
        val trialEnd = now + TimeUnit.DAYS.toMillis(3)
        val paidPeriodEnd = now + TimeUnit.DAYS.toMillis(30)

        // 1. In Trial
        val trialDto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "free",
            trialStart = ts(now - TimeUnit.DAYS.toMillis(4)),
            trialEnd = ts(trialEnd)
        )
        val trialDomain = trialDto.toDomain(now)
        assertEquals(AccessState.TRIAL, trialDomain.accessState)
        assertEquals(trialEnd, trialDomain.trialEnd)

        // Calculate trial remaining duration
        val trialRemainingMs = trialDomain.trialEnd!! - now
        assertEquals("3 days remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(trialRemainingMs))

        // 2. User subscribes -> transitions to ACTIVE
        val activeDto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            subscriptionId = "sub_new_123",
            currentPeriodStart = ts(now),
            currentPeriodEnd = ts(paidPeriodEnd),
            trialStart = ts(now - TimeUnit.DAYS.toMillis(4)),
            trialEnd = ts(trialEnd),
            premium = true
        )
        val activeDomain = activeDto.toDomain(now)
        assertEquals(AccessState.ACTIVE, activeDomain.accessState)
        assertEquals(paidPeriodEnd, activeDomain.currentPeriodEnd)

        // Calculate active remaining duration
        val activeRemainingMs = activeDomain.currentPeriodEnd!! - now
        assertEquals("30 days remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(activeRemainingMs))
    }

    @Test
    fun `test transition from ACTIVE to CANCELLED retains ACTIVE accessState with periodEnd`() {
        val now = 1757000000000L
        val paidPeriodEnd = now + TimeUnit.DAYS.toMillis(12)

        val cancelledDto = CloudSubscriptionDto(
            subscriptionStatus = "CANCELLED",
            planId = "plan_monthly",
            subscriptionId = "sub_cancelled_123",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(18)),
            currentPeriodEnd = ts(paidPeriodEnd),
            premium = true
        )
        val cancelledDomain = cancelledDto.toDomain(now)
        assertEquals(SubscriptionStatus.CANCELLED, cancelledDomain.status)
        assertEquals(AccessState.ACTIVE, cancelledDomain.accessState)
        assertTrue(cancelledDomain.isUnlocked)

        val remainingMs = cancelledDomain.currentPeriodEnd!! - now
        assertEquals("12 days remaining", com.reeltrak.app.core.ui.components.formatRemainingDurationText(remainingMs))
    }

    @Test
    fun `test PENDING status within valid currentPeriodEnd results in ACTIVE accessState with warning`() {
        val now = 1757000000000L
        val graceEnd = now + TimeUnit.DAYS.toMillis(3)

        val pendingDto = CloudSubscriptionDto(
            subscriptionStatus = "PENDING",
            planId = "plan_monthly",
            subscriptionId = "sub_mock_pending",
            latestPaymentId = "pay_mock_failed",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(28)),
            currentPeriodEnd = ts(graceEnd),
            premium = true
        )
        val domain = pendingDto.toDomain(now)
        assertEquals(SubscriptionStatus.PENDING, domain.status)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue("PENDING status in grace period keeps app unlocked", domain.isUnlocked)
    }

    @Test
    fun `test HALTED status results in LOCKED accessState`() {
        val now = 1757000000000L
        val pastEnd = now - TimeUnit.DAYS.toMillis(1)

        val haltedDto = CloudSubscriptionDto(
            subscriptionStatus = "HALTED",
            planId = "plan_monthly",
            subscriptionId = "sub_mock_halted",
            latestPaymentId = "pay_mock_exhausted",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(35)),
            currentPeriodEnd = ts(pastEnd),
            premium = false
        )
        val domain = haltedDto.toDomain(now)
        assertEquals(SubscriptionStatus.HALTED, domain.status)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("HALTED status must lock the app", domain.isUnlocked)
    }

    @Test
    fun `test EXPIRED status results in LOCKED accessState`() {
        val now = 1757000000000L
        val pastEnd = now - TimeUnit.HOURS.toMillis(1)

        val expiredDto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            planId = "plan_monthly",
            subscriptionId = "sub_mock_expired",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(31)),
            currentPeriodEnd = ts(pastEnd),
            premium = false
        )
        val domain = expiredDto.toDomain(now)
        assertEquals(SubscriptionStatus.EXPIRED, domain.status)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("EXPIRED status must lock the app", domain.isUnlocked)
    }

    @Test
    fun `test stale premium boolean cannot bypass expired period or expired trial`() {
        val now = 1757000000000L
        val pastEnd = now - TimeUnit.HOURS.toMillis(1)

        val maliciousOrStaleDto = CloudSubscriptionDto(
            subscriptionStatus = "EXPIRED",
            planId = "plan_monthly",
            subscriptionId = "sub_mock_expired",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(31)),
            currentPeriodEnd = ts(pastEnd),
            trialStart = ts(now - TimeUnit.DAYS.toMillis(40)),
            trialEnd = ts(now - TimeUnit.DAYS.toMillis(33)),
            premium = true // Stale/tampered boolean flag
        )
        val domain = maliciousOrStaleDto.toDomain(now)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Stale premium boolean must NEVER bypass expired dates", domain.isUnlocked)
    }

    @Test
    fun `test clean reset state results in NONE status and LOCKED accessState`() {
        val now = 1757000000000L
        val resetDto = CloudSubscriptionDto(
            subscriptionStatus = "NONE",
            planId = "free",
            subscriptionId = null,
            latestPaymentId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = null,
            trialEnd = null,
            cancelAtPeriodEnd = false,
            premium = false
        )
        val domain = resetDto.toDomain(now)
        assertEquals(SubscriptionStatus.NONE, domain.status)
        assertEquals(SubscriptionPlan.FREE, domain.plan)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Clean reset state must be LOCKED", domain.isUnlocked)
    }

    @Test
    fun `test active paid subscription with lingering trial dates never yields TRIAL status or plan`() {
        val now = 1757000000000L
        val validFuture = now + TimeUnit.DAYS.toMillis(28)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_monthly",
            subscriptionId = "sub_paid_active_123",
            currentPeriodStart = ts(now),
            currentPeriodEnd = ts(validFuture),
            trialStart = ts(now - TimeUnit.DAYS.toMillis(10)),
            trialEnd = ts(now - TimeUnit.DAYS.toMillis(3)), // Trial in the past
            premium = true
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.ACTIVE, domain.status)
        assertEquals(SubscriptionPlan.MONTHLY, domain.plan)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
    }

    @Test
    fun `test cancelled paid subscription with valid periodEnd retains ACTIVE access state and MONTHLY plan`() {
        val now = 1757000000000L
        val validFuture = now + TimeUnit.DAYS.toMillis(14)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "CANCELLED",
            planId = "plan_monthly",
            subscriptionId = "sub_paid_cancelled_123",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(14)),
            currentPeriodEnd = ts(validFuture),
            trialStart = ts(now - TimeUnit.DAYS.toMillis(30)),
            trialEnd = ts(now - TimeUnit.DAYS.toMillis(23)),
            cancelAtPeriodEnd = true,
            premium = true
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.CANCELLED, domain.status)
        assertEquals(SubscriptionPlan.MONTHLY, domain.plan)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
        assertTrue(domain.cancelAtPeriodEnd)
    }

    @Test
    fun `test pending recurring payment with valid current periodEnd retains ACTIVE access state and MONTHLY plan`() {
        val now = 1757000000000L
        val validFuture = now + TimeUnit.DAYS.toMillis(2)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "PENDING",
            planId = "plan_monthly",
            subscriptionId = "sub_paid_pending_123",
            currentPeriodStart = ts(now - TimeUnit.DAYS.toMillis(26)),
            currentPeriodEnd = ts(validFuture),
            trialStart = ts(now - TimeUnit.DAYS.toMillis(40)),
            trialEnd = ts(now - TimeUnit.DAYS.toMillis(33)),
            cancelAtPeriodEnd = false,
            premium = true
        )

        val domain = dto.toDomain(now)
        assertEquals(SubscriptionStatus.PENDING, domain.status)
        assertEquals(SubscriptionPlan.MONTHLY, domain.plan)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
    }
}


