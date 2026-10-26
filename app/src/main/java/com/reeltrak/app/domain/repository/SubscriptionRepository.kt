package com.reeltrak.app.domain.repository

import com.reeltrak.app.data.security.authorization.AuthorizationToken
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionState
import kotlinx.coroutines.flow.Flow

/**
 * Central repository interface managing subscription and access state.
 */
interface SubscriptionRepository {
    /**
     * Emits rich subscription state with loading, success, and error boundaries.
     */
    val subscriptionState: Flow<SubscriptionState>

    /**
     * Direct flow of the current subscription info.
     */
    val subscriptionInfo: Flow<SubscriptionInfo>

    /**
     * Real-time observable access state (TRIAL, ACTIVE, LOCKED).
     */
    val accessState: Flow<AccessState>

    /**
     * Flow indicating whether the entire app is unlocked (TRIAL or ACTIVE).
     */
    val isAppUnlocked: Flow<Boolean>

    /**
     * Legacy alias for isAppUnlocked.
     */
    val isPremium: Flow<Boolean>

    /**
     * Ensures user has a subscription document (initializes 7-day trial for new users).
     */
    suspend fun ensureSubscription(): Result<Unit>

    /**
     * Synchronous / one-shot check for subscription info (e.g., for background services / workers).
     */
    suspend fun getSubscriptionInfoSync(): SubscriptionInfo

    /**
     * Explicitly forces a server refresh of the Firestore subscription document.
     */
    suspend fun refreshSubscription(): Result<SubscriptionInfo>

    /**
     * Refreshes subscription data from Firestore and re-anchors subscription time.
     * When [forceServer] is true, the offline lease has expired, or no server sync has occurred,
     * requests [Source.SERVER]. Otherwise queries [Source.CACHE] with fallback to [Source.SERVER].
     */
    suspend fun refreshSubscription(forceServer: Boolean): Result<SubscriptionInfo> = refreshSubscription()

    /**
     * Cancels the active recurring subscription on the server at billing cycle end.
     */
    suspend fun cancelSubscription(): Result<Unit>

    /**
     * Low-frequency sync that only hits the server if last sync was older than minIntervalMs.
     */
    suspend fun syncIfStale(minIntervalMs: Long = 15 * 60 * 1000L): Result<SubscriptionInfo>?

    /**
     * Fetches an authoritative cryptographic authorization lease from the Cloudflare Worker.
     */
    suspend fun fetchAuthorizationLease(): Result<AuthorizationToken>
}
