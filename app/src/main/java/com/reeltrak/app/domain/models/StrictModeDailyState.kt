package com.reeltrak.app.domain.models

/**
 * Explicit daily lifecycle state model for Strict Mode.
 *
 * Daily Lifecycle Contract:
 * 1. [ACTIVE_INTERACTIVE]: At the start of every calendar day, Strict Mode automatically starts ON
 *    and interactive. The user has the privilege to turn it OFF once for that day.
 * 2. [DISABLED_TODAY]: The user has turned Strict Mode OFF today. It remains OFF for the rest of the day,
 *    but the user can choose to reactivate it by watching a rewarded video ad.
 * 3. [REACTIVATED_LOCKED]: The user has reactivated Strict Mode through the rewarded ad. Enforcement is
 *    active and LOCKED for the remainder of today (user cannot turn it OFF again).
 * 4. At the start of the next calendar day, the state automatically resets to [ACTIVE_INTERACTIVE].
 */
enum class StrictModeDailyState {
    /**
     * Strict Mode automatically active at start of calendar day.
     * Interactive: The user can turn it OFF once for today.
     */
    ACTIVE_INTERACTIVE,

    /**
     * User has turned Strict Mode OFF today.
     * Inactive: Available for one-time ad-based reactivation.
     */
    DISABLED_TODAY,

    /**
     * User has re-enabled Strict Mode via the rewarded ad.
     * Active & Locked: Enforcement is ON and cannot be turned OFF again for the rest of today.
     */
    REACTIVATED_LOCKED;

    val isEnforcementActive: Boolean
        get() = this == ACTIVE_INTERACTIVE || this == REACTIVATED_LOCKED

    val isLocked: Boolean
        get() = this == REACTIVATED_LOCKED

    val canTurnOff: Boolean
        get() = this == ACTIVE_INTERACTIVE

    val canReactivateWithAd: Boolean
        get() = this == DISABLED_TODAY
}
