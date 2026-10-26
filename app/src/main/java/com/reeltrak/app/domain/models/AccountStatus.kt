package com.reeltrak.app.domain.models

/**
 * Master account-level access status controlling whether the user is permitted
 * to access Re:Focus at all.
 *
 * Stored server-side at: /users/{uid}/account/status
 *
 * RESTRICTED overrides everything (trials, active subscriptions, payments).
 */
enum class AccountStatus {
    ACTIVE,
    RESTRICTED,
    PENDING_DELETION,
    DELETED;

    companion object {
        fun fromString(value: String?): AccountStatus {
            return when (value?.trim()?.uppercase()) {
                "RESTRICTED" -> RESTRICTED
                "PENDING_DELETION" -> PENDING_DELETION
                "DELETED" -> DELETED
                else -> ACTIVE
            }
        }
    }
}
