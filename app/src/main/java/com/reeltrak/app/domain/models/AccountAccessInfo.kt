package com.reeltrak.app.domain.models

/**
 * Encapsulates the master account access status and profile classification.
 *
 * Stored server-side at: /users/{uid}/account/status
 *
 * - [accountStatus]: ACTIVE or RESTRICTED (RESTRICTED strictly overrides everything)
 * - [accountType]: NORMAL or TESTER (Razorpay review test account)
 */
data class AccountAccessInfo(
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    val accountType: AccountType = AccountType.NORMAL,
    val deletionScheduledFor: Long? = null,
    val deletionRequestedAt: Long? = null,
    val isLoading: Boolean = false
) {
    val isRestricted: Boolean
        get() = accountStatus == AccountStatus.RESTRICTED

    val isPendingDeletion: Boolean
        get() = accountStatus == AccountStatus.PENDING_DELETION

    val isDeleted: Boolean
        get() = accountStatus == AccountStatus.DELETED

    val isAllowedAccess: Boolean
        get() = accountStatus == AccountStatus.ACTIVE

    val isTester: Boolean
        get() = accountType == AccountType.TESTER

    companion object {
        val DEFAULT = AccountAccessInfo(
            accountStatus = AccountStatus.ACTIVE,
            accountType = AccountType.NORMAL,
            deletionScheduledFor = null,
            deletionRequestedAt = null,
            isLoading = false
        )

        val LOADING = AccountAccessInfo(
            accountStatus = AccountStatus.ACTIVE,
            accountType = AccountType.NORMAL,
            deletionScheduledFor = null,
            deletionRequestedAt = null,
            isLoading = true
        )
    }
}
