package com.reeltrak.app.domain.repository

import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.AccountType
import kotlinx.coroutines.flow.Flow


interface AccountRepository {
    /**
     * Real-time observable master account access status.
     * Emits AccountStatus.ACTIVE or AccountStatus.RESTRICTED.
     * When restricted, app access must immediately be terminated.
     */
    val accountStatus: Flow<AccountStatus>

    /**
     * Real-time observable master account access info containing status and account type (TESTER vs NORMAL).
     */
    val accountAccessInfo: Flow<AccountAccessInfo>

    /**
     * Freshly fetches the authoritative account status from Firestore for the given user ID.
     */
    suspend fun checkAccountStatus(userId: String): Result<AccountStatus>

    /**
     * Freshly fetches the authoritative account access info from Firestore for the given user ID.
     */
    suspend fun checkAccountAccessInfo(userId: String): Result<AccountAccessInfo>

    /**
     * Authenticated call to Cloudflare Worker to request account soft deletion (7-day grace period).
     * Returns the epoch milliseconds of the scheduled deletion deadline.
     */
    suspend fun requestAccountDeletion(): Result<Long>

    /**
     * Authenticated call to Cloudflare Worker to cancel a pending account deletion and restore to ACTIVE.
     */
    suspend fun cancelAccountDeletion(): Result<Unit>
}

