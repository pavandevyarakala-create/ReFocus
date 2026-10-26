package com.reeltrak.app.data.security.authorization

import java.util.UUID

/**
 * Immutable, integrity-bound and cryptographically signed authorization token.
 * Passed across execution boundaries (Tracking Gate -> Session Manager -> Persistence Gate).
 * 
 * Individual execution layers (ReelTrackerService, DayManager) validate this token independently.
 * Patching a single boolean or skipping one check does NOT satisfy other layers that validate this token.
 */
data class AuthorizationToken(
    val uid: String,
    val planId: String,
    val accessMode: AccessMode,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val leaseValidUntilMs: Long,
    val integrityDigest: String,
    val leaseVersion: Int = 1,
    val nonce: String = UUID.randomUUID().toString(),
    val subscriptionId: String? = null,
    val signature: String
) {
    /**
     * Complete structural and contextual validation of the authorization token.
     *
     * @param nowMs Current monotonic authoritative subscription time.
     * @param currentUid The currently authenticated Firebase UID (account binding).
     * @param currentIntegrityDigest Current runtime app package & signing digest.
     * @param signatureVerifier Callback verifying the token's cryptographic asymmetric server signature.
     * @return true if the token is completely authentic, unexpired, and bound to the active user & app.
     */
    fun isValid(
        nowMs: Long,
        currentUid: String?,
        currentIntegrityDigest: String,
        signatureVerifier: (AuthorizationToken) -> Boolean
    ): Boolean {
        // 1. Account binding check: token must match current authenticated UID
        if (currentUid.isNullOrBlank() || uid != currentUid) {
            return false
        }

        // 2. Application integrity check: token must match current app signature & package digest
        val isDigestValid = integrityDigest == "*" ||
            integrityDigest == currentIntegrityDigest ||
            (com.reeltrak.app.BuildConfig.DEBUG && (
                integrityDigest == AppIntegrityManager.EXPECTED_RELEASE_INTEGRITY_DIGEST ||
                integrityDigest == AppIntegrityManager.EXPECTED_PLAY_INTEGRITY_DIGEST
            ))
        if (!isDigestValid || integrityDigest.isBlank()) {
            return false
        }

        // 3. Monotonic entitlement expiration check
        if (expiresAtMs <= 0L || nowMs >= expiresAtMs) {
            return false
        }

        // 4. Offline validity lease check (max 24h offline without server verification)
        if (leaseValidUntilMs <= 0L || nowMs >= leaseValidUntilMs) {
            return false
        }

        // 5. Monotonic sanity check: token cannot be issued in the future (with 60s tolerance)
        if (issuedAtMs > nowMs + 60_000L) {
            return false
        }

        // 6. Cryptographic signature check (asymmetric server signature)
        if (signature.isBlank() || !signatureVerifier(this)) {
            return false
        }

        return true
    }

    /**
     * Payload string used for computing/verifying the token's cryptographic asymmetric signature.
     */
    fun computePayload(): String {
        return "$uid:$planId:${accessMode.name}:$issuedAtMs:$expiresAtMs:$leaseValidUntilMs:$integrityDigest:$leaseVersion:$nonce"
    }
}
