package com.reeltrak.app.data.services

import android.util.Log
import com.reeltrak.app.BuildConfig
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.services.CheckoutState
import com.reeltrak.app.domain.services.MockSubscriptionController
import com.reeltrak.app.domain.services.SubscriptionPurchaseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Development & testing implementation of SubscriptionPurchaseService & MockSubscriptionController.
 *
 * SAFETY ARCHITECTURE:
 * All simulation state is strictly held IN-MEMORY in debug builds.
 * This implementation NEVER performs client writes to production Cloud Firestore documents
 * (/users/{uid}/subscription/status) and NEVER corrupts real user subscription records.
 * In release builds, all simulation functionality is disabled and rejected.
 */
@Singleton
class DevMockSubscriptionPurchaseServiceImpl @Inject constructor() : SubscriptionPurchaseService, MockSubscriptionController {

    private val TAG = "DevMockSubscription"

    private val _checkoutState = MutableStateFlow<CheckoutState>(CheckoutState.Idle)
    override val checkoutState: StateFlow<CheckoutState> = _checkoutState.asStateFlow()

    private val _mockSubscriptionInfo = MutableStateFlow<SubscriptionInfo?>(null)
    override val mockSubscriptionInfo: StateFlow<SubscriptionInfo?> = _mockSubscriptionInfo.asStateFlow()

    private var mockModeEnabled = BuildConfig.DEBUG

    override fun setMockModeEnabled(enabled: Boolean) {
        if (BuildConfig.DEBUG) {
            mockModeEnabled = enabled
            if (!enabled) {
                _mockSubscriptionInfo.value = null
            }
            Log.d(TAG, "Mock mode set to: $enabled")
        }
    }

    override suspend fun initiatePurchase(plan: SubscriptionPlan) {
        if (!BuildConfig.DEBUG) {
            _checkoutState.value = CheckoutState.Error("Simulations disabled in release builds.")
            return
        }

        _checkoutState.value = CheckoutState.CreatingOrder(plan)

        withContext(Dispatchers.IO) {
            try {
                delay(800)
                val orderId = "mock_order_${UUID.randomUUID().toString().take(6)}"
                _checkoutState.value = CheckoutState.CheckoutInProgress(orderId)

                delay(1200)
                _checkoutState.value = CheckoutState.Verifying

                delay(1000)
                val result = simulateSubscriptionTransition(
                    status = SubscriptionStatus.ACTIVE,
                    plan = plan,
                    isSuccess = true
                )

                if (result.isSuccess) {
                    _checkoutState.value = CheckoutState.Success("Mock Premium activated!")
                } else {
                    _checkoutState.value = CheckoutState.Error("Mock payment failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during mock checkout", e)
                _checkoutState.value = CheckoutState.Error("Checkout simulation error")
            }
        }
    }

    override fun resetCheckoutState() {
        _checkoutState.value = CheckoutState.Idle
    }

    override suspend fun simulateStartTrial(daysRemaining: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        val trialEnd = now + TimeUnit.DAYS.toMillis(daysRemaining.toLong())

        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.TRIAL,
            plan = SubscriptionPlan.FREE,
            subscriptionId = null,
            latestPaymentId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = now,
            trialEnd = trialEnd,
            cancelAtPeriodEnd = false,
            nextBillingDate = null,
            accessState = AccessState.TRIAL,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated StartTrial in-memory (TRIAL, $daysRemaining days) - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulateTrialExpired(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        val trialEnd = now - TimeUnit.HOURS.toMillis(1)

        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.NONE,
            plan = SubscriptionPlan.FREE,
            subscriptionId = null,
            latestPaymentId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = now - TimeUnit.DAYS.toMillis(8),
            trialEnd = trialEnd,
            cancelAtPeriodEnd = false,
            nextBillingDate = null,
            accessState = AccessState.LOCKED,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated TrialExpired in-memory (LOCKED) - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulateSubscriptionTransition(
        status: SubscriptionStatus,
        plan: SubscriptionPlan,
        isSuccess: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        if (!isSuccess) {
            return@withContext Result.failure(Exception("Simulated payment/mandate failure"))
        }

        when (status) {
            SubscriptionStatus.ACTIVE -> simulateActiveSubscription(plan)
            SubscriptionStatus.CANCELLED -> simulateCancelledAtPeriodEnd()
            SubscriptionStatus.EXPIRED -> simulatePeriodExpired()
            SubscriptionStatus.PENDING -> simulatePendingGrace()
            SubscriptionStatus.HALTED -> simulateHalted()
            SubscriptionStatus.PAST_DUE -> simulateHalted()
            SubscriptionStatus.NONE -> simulateReset()
            SubscriptionStatus.TRIAL -> simulateStartTrial(7)
        }
    }

    override suspend fun simulateActiveSubscription(plan: SubscriptionPlan): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        val periodEnd = now + TimeUnit.DAYS.toMillis(30)

        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = plan,
            subscriptionId = "sub_mock_${UUID.randomUUID().toString().take(8)}",
            latestPaymentId = "pay_mock_${UUID.randomUUID().toString().take(8)}",
            currentPeriodStart = now,
            currentPeriodEnd = periodEnd,
            trialStart = null,
            trialEnd = null,
            cancelAtPeriodEnd = false,
            nextBillingDate = periodEnd,
            accessState = AccessState.ACTIVE,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated ActiveSubscription in-memory (ACTIVE, plan=$plan) - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulateMonthlyRenewal(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        val periodEnd = now + TimeUnit.DAYS.toMillis(30)

        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_mock_${UUID.randomUUID().toString().take(8)}",
            latestPaymentId = "pay_renew_${UUID.randomUUID().toString().take(8)}",
            currentPeriodStart = now,
            currentPeriodEnd = periodEnd,
            trialStart = null,
            trialEnd = null,
            cancelAtPeriodEnd = false,
            nextBillingDate = periodEnd,
            accessState = AccessState.ACTIVE,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated MonthlyRenewal in-memory (+30 days) - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulatePendingGrace(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        val graceEnd = now + TimeUnit.DAYS.toMillis(3)

        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.PENDING,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_mock_pending",
            latestPaymentId = "pay_mock_failed_attempt",
            currentPeriodStart = now - TimeUnit.DAYS.toMillis(28),
            currentPeriodEnd = graceEnd,
            trialStart = null,
            trialEnd = null,
            cancelAtPeriodEnd = false,
            nextBillingDate = now + TimeUnit.DAYS.toMillis(1),
            accessState = AccessState.ACTIVE,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated PendingGrace in-memory - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulateHalted(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.HALTED,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_mock_halted",
            latestPaymentId = "pay_mock_exhausted",
            currentPeriodStart = now - TimeUnit.DAYS.toMillis(35),
            currentPeriodEnd = now - TimeUnit.DAYS.toMillis(1),
            trialStart = null,
            trialEnd = null,
            cancelAtPeriodEnd = false,
            nextBillingDate = null,
            accessState = AccessState.LOCKED,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated Halted in-memory (HALTED, LOCKED) - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulateCancelledAtPeriodEnd(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        val remainingPeriodEnd = now + TimeUnit.DAYS.toMillis(15)

        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.CANCELLED,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_mock_cancelled",
            latestPaymentId = "pay_mock_latest",
            currentPeriodStart = now - TimeUnit.DAYS.toMillis(15),
            currentPeriodEnd = remainingPeriodEnd,
            trialStart = null,
            trialEnd = null,
            cancelAtPeriodEnd = true,
            nextBillingDate = null,
            accessState = AccessState.ACTIVE,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated CancelledAtPeriodEnd in-memory (ACTIVE until end) - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulatePeriodExpired(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        val now = System.currentTimeMillis()
        val pastEnd = now - TimeUnit.HOURS.toMillis(1)

        _mockSubscriptionInfo.value = SubscriptionInfo(
            status = SubscriptionStatus.EXPIRED,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_mock_expired",
            latestPaymentId = "pay_mock_old",
            currentPeriodStart = now - TimeUnit.DAYS.toMillis(31),
            currentPeriodEnd = pastEnd,
            trialStart = null,
            trialEnd = null,
            cancelAtPeriodEnd = false,
            nextBillingDate = null,
            accessState = AccessState.LOCKED,
            lastServerVerificationMs = now
        )

        Log.d(TAG, "Simulated PeriodExpired in-memory (EXPIRED, LOCKED) - purely local, zero Firestore writes")
        Result.success(Unit)
    }

    override suspend fun simulateReset(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("Disabled in release builds"))
        }

        _mockSubscriptionInfo.value = null
        Log.d(TAG, "Simulated Reset - cleared in-memory mock, restored authoritative server state")
        Result.success(Unit)
    }
}
