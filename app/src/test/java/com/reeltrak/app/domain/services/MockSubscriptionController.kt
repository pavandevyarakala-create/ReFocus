package com.reeltrak.app.domain.services

import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for controlling mock subscription & trial transitions during development/testing.
 * Strictly disabled in release builds.
 */
interface MockSubscriptionController {
    /**
     * Observable in-memory mock subscription info (null when mock mode is inactive).
     */
    val mockSubscriptionInfo: StateFlow<SubscriptionInfo?>

    /**
     * Sets whether mock checkout simulations (success/failure) should be active.
     */
    fun setMockModeEnabled(enabled: Boolean)

    /**
     * Directly simulates backend status transitions:
     * - Start 7-day trial
     * - Active trial
     * - Trial expired
     * - Activate monthly subscription
     * - Simulate monthly auto-debit renewal
     * - Simulate payment failure / grace period (PENDING)
     * - Simulate retries exhausted / halted state (HALTED)
     * - Cancel recurring autopay with period-end access (cancel_at_period_end)
     * - Current period expiration (EXPIRED)
     * - Reset account to clean initial state (NONE/LOCKED)
     */
    suspend fun simulateSubscriptionTransition(
        status: SubscriptionStatus,
        plan: SubscriptionPlan = SubscriptionPlan.MONTHLY,
        isSuccess: Boolean = true
    ): Result<Unit>

    /**
     * Starts or resets a fresh trial for the user with specified days remaining.
     */
    suspend fun simulateStartTrial(daysRemaining: Int = 7): Result<Unit>

    /**
     * Simulates trial expiration (trialEnd in past).
     */
    suspend fun simulateTrialExpired(): Result<Unit>

    /**
     * Simulates active recurring monthly ₹99 subscription with 30-day period.
     */
    suspend fun simulateActiveSubscription(plan: SubscriptionPlan = SubscriptionPlan.MONTHLY): Result<Unit>

    /**
     * Simulates monthly renewal / successful auto-charge event advancing the period by 30 days.
     */
    suspend fun simulateMonthlyRenewal(): Result<Unit>

    /**
     * Simulates payment failure entering a 3-day grace period (PENDING status, unlocked with warning).
     */
    suspend fun simulatePendingGrace(): Result<Unit>

    /**
     * Simulates payment failure retries exhausted (HALTED status, locked access).
     */
    suspend fun simulateHalted(): Result<Unit>

    /**
     * Simulates user cancelling autopay with cancel_at_period_end = true (unlocked until periodEnd).
     */
    suspend fun simulateCancelledAtPeriodEnd(): Result<Unit>

    /**
     * Simulates current period expiration (periodEnd in past, EXPIRED status, locked access).
     */
    suspend fun simulatePeriodExpired(): Result<Unit>

    /**
     * Resets subscription to clean initial state (NONE status, no trial, locked access).
     */
    suspend fun simulateReset(): Result<Unit>
}
