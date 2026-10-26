package com.reeltrak.app.domain.managers

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.repository.SubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.AuthorizationLockReason
import com.reeltrak.app.data.security.authorization.AuthorizationState
import com.reeltrak.app.data.security.authorization.AuthorizationToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide centralized Subscription & Access Manager.
 * All feature ViewModels, services, and UI navigation components observe or query this manager
 * to determine whole-app access gating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SubscriptionManager @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionTimeAuthority: SubscriptionTimeAuthority,
    private val appAuthorizationManager: AppAuthorizationManager? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Observable rich subscription state (Loading / Success / Error).
     */
    val state: StateFlow<SubscriptionState> = subscriptionRepository.subscriptionState
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SubscriptionState.Loading
        )

    /**
     * Real-time observable SubscriptionInfo.
     */
    val subscriptionInfo: StateFlow<SubscriptionInfo> = subscriptionRepository.subscriptionInfo
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SubscriptionInfo.INITIALIZING_DEFAULT
        )

    /**
     * Live countdown ticker emitting remaining trial or paid subscription duration in milliseconds.
     * Uses the monotonic server-anchored subscription clock.
     */
    val remainingSubscriptionDurationMs: StateFlow<Long> = subscriptionRepository.subscriptionInfo.flatMapLatest { info ->
        val targetEnd = when (info.accessState) {
            AccessState.TRIAL -> info.trialEnd
            AccessState.ACTIVE -> info.currentPeriodEnd
            AccessState.INITIALIZING, AccessState.FINAL_SYNC_GRACE, AccessState.LOCKED -> null
        }
        if (targetEnd != null && targetEnd > 0L) {
            flow {
                while (true) {
                    val authoritativeNow = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
                    val remaining = (targetEnd - authoritativeNow).coerceAtLeast(0L)
                    emit(remaining)
                    if (remaining <= 0L) break
                    delay(1000L)
                }
            }
        } else {
            flowOf(0L)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0L
    )

    /**
     * Alias for remainingSubscriptionDurationMs for backward compatibility.
     */
    val remainingTrialDurationMs: StateFlow<Long> = remainingSubscriptionDurationMs

    /**
     * Real-time observable AccessState: INITIALIZING, TRIAL, ACTIVE, FINAL_SYNC_GRACE, or LOCKED.
     * Automatically transitions to FINAL_SYNC_GRACE or LOCKED when the server-anchored clock
     * reaches or exceeds the period/trial expiration target.
     * Crucially: uninitialized ticker values (0L) NEVER masquerade as confirmed expiration.
     */
    val accessState: StateFlow<AccessState> = combine(
        subscriptionRepository.accessState,
        subscriptionRepository.subscriptionInfo,
        remainingSubscriptionDurationMs,
        appAuthorizationManager?.authorizationState ?: flowOf(AuthorizationState.Locked(AuthorizationLockReason.NO_SERVER_LEASE))
    ) { repoAccessState, info, _, authState ->
        val (authoritativeNow, isCalibrated) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
        // MULTI-FACTOR DEFENSE:
        // 1. If local authorization manager reports Tampered, fail closed immediately
        // 2. If repository is still initializing, remain in neutral INITIALIZING state (never flash LockedAppScreen)
        // 3. If local authorization manager reports Locked (excluding transient NO_SERVER_LEASE while awaiting/fetching server lease), fail closed immediately
        // 4. If within final sync grace, allow sync but lock reels
        val derivedState = if (authState is AuthorizationState.Tampered) {
            AccessState.LOCKED
        } else if (repoAccessState == AccessState.INITIALIZING) {
            AccessState.INITIALIZING
        } else if (authState is AuthorizationState.Locked && authState.reason != AuthorizationLockReason.NO_SERVER_LEASE) {
            AccessState.LOCKED
        } else if (authState is AuthorizationState.FinalSyncGrace) {
            AccessState.FINAL_SYNC_GRACE
        } else {
            val targetEnd = when (info.accessState) {
                AccessState.TRIAL -> info.trialEnd
                AccessState.ACTIVE -> info.currentPeriodEnd
                AccessState.INITIALIZING, AccessState.FINAL_SYNC_GRACE, AccessState.LOCKED -> null
            }
            if (targetEnd != null && targetEnd > 0L) {
                if (authoritativeNow >= targetEnd) {
                    val FINAL_SYNC_WINDOW_MS = 2 * 60 * 1000L
                    val isEligibleForGrace = info.status != SubscriptionStatus.EXPIRED &&
                                             info.status != SubscriptionStatus.HALTED &&
                                             info.status != SubscriptionStatus.CANCELLED
                    if (isEligibleForGrace && authoritativeNow < (targetEnd + FINAL_SYNC_WINDOW_MS)) {
                        AccessState.FINAL_SYNC_GRACE
                    } else {
                        AccessState.LOCKED
                    }
                } else {
                    repoAccessState
                }
            } else {
                repoAccessState
            }
        }
        derivedState
    }.distinctUntilChanged()
    .onEach { state ->
        android.util.Log.d(
            "SUB_DEBUG_MANAGER",
            "accessState transitioned -> $state (isUnlocked=${state == AccessState.ACTIVE || state == AccessState.TRIAL})"
        )
        android.util.Log.d(
            "STARTUP_DEBUG",
            "SubscriptionManager: accessState transitioned -> $state (isUnlocked=${state == AccessState.ACTIVE || state == AccessState.TRIAL})"
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AccessState.INITIALIZING
    )

    /**
     * Real-time observable boolean indicating whether the entire app is unlocked (TRIAL or ACTIVE).
     */
    val isAppUnlocked: StateFlow<Boolean> = accessState.map { it == AccessState.TRIAL || it == AccessState.ACTIVE }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    /**
     * Observable boolean indicating if access is genuinely confirmed LOCKED or FINAL_SYNC_GRACE.
     * Evaluates to false during INITIALIZING, TRIAL, and ACTIVE.
     */
    val isConfirmedLocked: StateFlow<Boolean> = accessState.map { 
        it == AccessState.LOCKED || it == AccessState.FINAL_SYNC_GRACE 
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    /**
     * Authoritative entitlement gate: true for ACTIVE PREMIUM or ACTIVE TRIAL; false for NON-PREMIUM.
     */
    val hasActiveEntitlement: StateFlow<Boolean> = isAppUnlocked

    /**
     * Synchronous authoritative check if user has active entitlement (Premium or Trial).
     */
    fun hasActiveEntitlementNow(): Boolean = isAppUnlockedNow()

    /**
     * Legacy alias for isAppUnlocked.
     */
    val isPremium: StateFlow<Boolean> = isAppUnlocked

    /**
     * Immediate synchronous check if app is unlocked.
     */
    fun isAppUnlockedNow(): Boolean = isAppUnlocked.value

    /**
     * Immediate synchronous check if app access is confirmed locked.
     */
    fun isConfirmedLockedNow(): Boolean = isConfirmedLocked.value

    /**
     * Legacy alias for isAppUnlockedNow.
     */
    fun isPremiumNow(): Boolean = isAppUnlockedNow()

    /**
     * Gets current access state directly.
     */
    fun currentAccessState(): AccessState = accessState.value

    /**
     * Gets the current subscription status (NONE, TRIAL, ACTIVE, PAST_DUE, CANCELLED, EXPIRED).
     */
    fun currentStatus(): SubscriptionStatus = subscriptionInfo.value.status

    /**
     * Synchronously calculates remaining trial duration in milliseconds based on authoritative subscription time.
     */
    fun getRemainingTrialDurationMs(): Long {
        val info = subscriptionInfo.value
        val trialEnd = info.trialEnd ?: return 0L
        val authoritativeNow = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
        return (trialEnd - authoritativeNow).coerceAtLeast(0L)
    }

    @Volatile
    private var isEnsureInFlight = false

    /**
     * Ensures the user's trial/subscription document is initialized on the server.
     */
    fun ensureSubscription() {
        if (Firebase.auth.currentUser == null) {
            return
        }
        if (isEnsureInFlight) {
            android.util.Log.d("STARTUP_DEBUG", "SubscriptionManager: ensureSubscription() already in flight, skipping duplicate call")
            return
        }
        val currentStatus = currentStatus()
        if (currentStatus != SubscriptionStatus.NONE && accessState.value != AccessState.INITIALIZING) {
            android.util.Log.d("STARTUP_DEBUG", "SubscriptionManager: subscription already initialized ($currentStatus), skipping redundant ensureSubscription")
            return
        }
        android.util.Log.d("STARTUP_DEBUG", "SubscriptionManager: ensureSubscription() called")
        scope.launch {
            isEnsureInFlight = true
            try {
                subscriptionRepository.ensureSubscription()
            } finally {
                isEnsureInFlight = false
            }
        }
    }

    private val ensureMutex = Mutex()

    /**
     * Suspending call to ensure subscription on server and await Worker completion.
     * Guards against redundant calls when user already has an active/resolved subscription status (OPT-001).
     */
    suspend fun ensureSubscriptionSync(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val currentStatus = currentStatus()
        if (!force && currentStatus != SubscriptionStatus.NONE && accessState.value != AccessState.INITIALIZING) {
            android.util.Log.d("STARTUP_DEBUG", "SubscriptionManager: subscription already initialized ($currentStatus), skipping redundant ensureSubscriptionSync")
            return@withContext Result.success(Unit)
        }
        ensureMutex.withLock {
            val statusUnderLock = currentStatus()
            if (!force && statusUnderLock != SubscriptionStatus.NONE && accessState.value != AccessState.INITIALIZING) {
                return@withLock Result.success(Unit)
            }
            subscriptionRepository.ensureSubscription()
        }
    }

    /**
     * Deterministic startup/login sequence:
     * 1. Awaits worker /api/ensure-subscription call.
     * 2. Forces fresh server fetch of subscription document.
     * 3. Awaits non-INITIALIZING accessState.
     * Returns the authoritative AccessState.
     */
    suspend fun ensureAndAwaitEntitlement(timeoutMs: Long = 5000L): AccessState = withContext(Dispatchers.IO) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("SUB_PERF", "[SUB_PERF] ensureAndAwaitEntitlement started")
        try {
            ensureSubscriptionSync()
        } catch (e: Exception) {
            android.util.Log.w("SubscriptionManager", "ensureSubscriptionSync failed: ${e.message}", e)
        }
        try {
            refreshSubscription()
        } catch (e: Exception) {
            android.util.Log.w("SubscriptionManager", "refreshSubscription failed: ${e.message}", e)
        }

        val resolved = try {
            withTimeout(timeoutMs) {
                accessState.filter { it != AccessState.INITIALIZING }.first()
            }
        } catch (e: Exception) {
            android.util.Log.w("SubscriptionManager", "Timed out waiting for non-initializing accessState, returning current", e)
            currentAccessState()
        }
        android.util.Log.d("SUB_PERF", "[SUB_PERF] ensureAndAwaitEntitlement finished in ${android.os.SystemClock.elapsedRealtime() - t0}ms, resolvedState=$resolved")
        resolved
    }

    /**
     * Synchronously fetches the latest subscription status directly from Firestore.
     */
    suspend fun getSubscriptionInfoSync(): SubscriptionInfo {
        return subscriptionRepository.getSubscriptionInfoSync()
    }

    /**
     * Explicitly triggers a server-side refresh of subscription data.
     */
    suspend fun refreshSubscription(forceServer: Boolean = true): Result<SubscriptionInfo> {
        return subscriptionRepository.refreshSubscription(forceServer)
    }

    /**
     * Cancels the active recurring subscription on the server at billing cycle end.
     */
    suspend fun cancelSubscription(): Result<Unit> {
        return subscriptionRepository.cancelSubscription()
    }

    /**
     * Low-frequency sync check (e.g. on app resume) that only queries server if last sync is stale (>15 mins).
     */
    suspend fun syncIfStale(minIntervalMs: Long = 15 * 60 * 1000L): Result<SubscriptionInfo>? {
        return subscriptionRepository.syncIfStale(minIntervalMs)
    }

    /**
     * Retrieves the current cryptographic, integrity-bound AuthorizationToken if active.
     */
    fun getActiveAuthorizationToken(): AuthorizationToken? = appAuthorizationManager?.activeToken

    /**
     * Direct access to AppAuthorizationManager for independent verification.
     */
    fun getAppAuthorizationManager(): AppAuthorizationManager? = appAuthorizationManager
}
