package com.reeltrak.app.domain.models

/**
 * Categorization of account profile (e.g. for Razorpay review testers vs normal users).
 *
 * Stored server-side at: /users/{uid}/account/status or /users/{uid}/account
 *
 * For accountType = TESTER and accountStatus = ACTIVE with no valid subscription/trial,
 * the app displays a tester-specific locked screen explaining the test payment.
 */
enum class AccountType {
    NORMAL,
    TESTER;

    companion object {
        fun fromString(value: String?): AccountType {
            return when (value?.trim()?.uppercase()) {
                "TESTER" -> TESTER
                else -> NORMAL
            }
        }
    }
}
