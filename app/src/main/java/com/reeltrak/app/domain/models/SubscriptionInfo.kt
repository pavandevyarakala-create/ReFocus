package com.reeltrak.app.domain.models

/**
 * Centralized whole-app access state.
 * There is NO separate "premium tier" - the entire app access is governed by this state.
 */
enum class AccessState {
    INITIALIZING,       // Subscription state is loading / not yet known
    TRIAL,              // 7-day free trial on account creation (full access)
    ACTIVE,             // Active or unexpired paid subscription (full access)
    FINAL_SYNC_GRACE,   // Exactly 2-minute post-expiration reconciliation window (reels locked, sync allowed)
    LOCKED              // Trial or subscription expired / failed payment (app locked except Settings & Subscription)
}

/**
 * Domain representations of user subscription state.
 */
enum class SubscriptionStatus {
    NONE,
    TRIAL,
    ACTIVE,
    PENDING,
    HALTED,
    CANCELLED,
    EXPIRED,
    PAST_DUE // Legacy alias for backward compatibility
}

enum class SubscriptionPlan(val planId: String, val displayName: String) {
    FREE("free", "Free Trial"),
    MONTHLY("plan_monthly", "₹79/Month");

    companion object {
        fun fromId(id: String?): SubscriptionPlan {
            return entries.find { it.planId.equals(id, ignoreCase = true) } ?: FREE
        }
    }
}

data class SubscriptionInfo(
    val status: SubscriptionStatus = SubscriptionStatus.NONE,
    val plan: SubscriptionPlan = SubscriptionPlan.FREE,
    val subscriptionId: String? = null,
    val latestPaymentId: String? = null,
    val currentPeriodStart: Long? = null,
    val currentPeriodEnd: Long? = null,
    val trialStart: Long? = null,
    val trialEnd: Long? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val nextBillingDate: Long? = null,
    val accessState: AccessState = AccessState.LOCKED,
    val lastServerVerificationMs: Long = 0L
) {
    /**
     * Backward-compatible convenience getter: returns true if the app is fully accessible (TRIAL or ACTIVE).
     * Note: FINAL_SYNC_GRACE is strictly LOCKED for premium reel tracking.
     */
    val isUnlocked: Boolean
        get() = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE

    val hasActiveEntitlement: Boolean
        get() = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE

    /**
     * Indicates whether background daily data sync / reconciliation is permitted.
     * True during TRIAL, ACTIVE, and the 2-minute FINAL_SYNC_GRACE window.
     */
    val isSyncPermitted: Boolean
        get() = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE || accessState == AccessState.FINAL_SYNC_GRACE

    // Kept as alias for isUnlocked to prevent immediate breaks in legacy references
    val isPremium: Boolean
        get() = isUnlocked

    /**
     * Returns true ONLY if subscription is confirmed LOCKED or in FINAL_SYNC_GRACE.
     * Evaluates to false during INITIALIZING, TRIAL, and ACTIVE.
     */
    val isConfirmedLocked: Boolean
        get() = accessState == AccessState.LOCKED || accessState == AccessState.FINAL_SYNC_GRACE

    companion object {
        val INITIALIZING_DEFAULT = SubscriptionInfo(
            status = SubscriptionStatus.NONE,
            plan = SubscriptionPlan.FREE,
            subscriptionId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = null,
            trialEnd = null,
            accessState = AccessState.INITIALIZING
        )

        val LOCKED_DEFAULT = SubscriptionInfo(
            status = SubscriptionStatus.NONE,
            plan = SubscriptionPlan.FREE,
            subscriptionId = null,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            trialStart = null,
            trialEnd = null,
            accessState = AccessState.LOCKED
        )

        val FREE_DEFAULT = LOCKED_DEFAULT
    }
}

/**
 * Encapsulates safe UI state for subscription handling across the app.
 */
sealed interface SubscriptionState {
    data object Loading : SubscriptionState
    data class Success(val info: SubscriptionInfo) : SubscriptionState
    data class Error(val message: String, val fallbackInfo: SubscriptionInfo = SubscriptionInfo.LOCKED_DEFAULT) : SubscriptionState
}
