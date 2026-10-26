package com.reeltrak.app.data.security.authorization

import com.reeltrak.app.domain.models.AccessState

/**
 * Root enumeration of reasons why authorization is denied or locked.
 */
enum class AuthorizationLockReason {
    NO_AUTHENTICATED_ACCOUNT,
    TRIAL_EXPIRED,
    SUBSCRIPTION_EXPIRED,
    SUBSCRIPTION_HALTED,
    OFFLINE_LEASE_EXPIRED,
    ACCOUNT_SWITCH_INVALIDATION,
    ACCOUNT_RESTRICTED,
    SIGNATURE_VERIFICATION_FAILED,
    INTEGRITY_COMPROMISED,
    CLOCK_TAMPERING_DETECTED,
    NO_SERVER_LEASE
}

/**
 * Strongly typed multi-factor local authorization state.
 * Prevents single-boolean bypass by requiring rich validated state.
 */
sealed interface AuthorizationState {
    /**
     * Initializing / awaiting initial local or server verification.
     */
    data object Initializing : AuthorizationState

    /**
     * Genuinely authorized. Contains a cryptographic, integrity-bound token.
     */
    data class Authorized(val token: AuthorizationToken) : AuthorizationState

    /**
     * In final 120-second grace window after natural expiration.
     * Tracking is strictly forbidden; final sync is permitted.
     */
    data class FinalSyncGrace(val token: AuthorizationToken) : AuthorizationState

    /**
     * Access locked.
     */
    data class Locked(val reason: AuthorizationLockReason) : AuthorizationState

    /**
     * Application tampering, signature mismatch, or package forgery detected.
     * Fails closed across all execution paths.
     */
    data class Tampered(val details: String) : AuthorizationState

    /**
     * Convenience mapper to domain AccessState.
     */
    fun toDomainAccessState(): AccessState = when (this) {
        is Initializing -> AccessState.INITIALIZING
        is Authorized -> when (token.accessMode) {
            AccessMode.TRIAL -> AccessState.TRIAL
            AccessMode.ACTIVE -> AccessState.ACTIVE
        }
        is FinalSyncGrace -> AccessState.FINAL_SYNC_GRACE
        is Locked -> AccessState.LOCKED
        is Tampered -> AccessState.LOCKED
    }

    val isAuthorized: Boolean
        get() = this is Authorized
}

enum class AccessMode {
    TRIAL,
    ACTIVE
}

/**
 * Authoritative system-wide operational tracking and entitlement mode.
 * Eliminates implicit null-token fallthroughs by making operational state strictly explicit.
 */
enum class OperationalMode {
    UNAUTHENTICATED_FREE,
    AUTHENTICATED_FREE,
    TRIAL,
    PREMIUM,
    LOCKED_OR_EXPIRED;

    val isFreeTrackingAllowed: Boolean
        get() = this == UNAUTHENTICATED_FREE || this == AUTHENTICATED_FREE

    val isEntitled: Boolean
        get() = this == TRIAL || this == PREMIUM
}
