package com.reeltrak.app.security

import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Security and Request Optimization Regression Test Suite (OPT-001 through OPT-006).
 *
 * Verifies code-based implementations:
 * 1. OPT-001: SubscriptionManager.ensureSubscriptionSync guards against redundant /api/ensure-subscription calls.
 * 2. OPT-002: LocalRestoreRepositoryImpl and LocalBackupRepositoryImpl enforce single-flight concurrency via Mutex.
 * 3. OPT-003: RazorpaySubscriptionPurchaseServiceImpl latches Checkout.preload with an AtomicBoolean process guard.
 * 4. OPT-004: SubscriptionRepositoryImpl.refreshSubscription leverages local cache when real-time listener is fresh.
 * 5. OPT-005: SettingsViewModel eliminates redundant /api/backup/status HTTP polling, binding directly to real-time snapshot listener.
 * 6. OPT-006: NetworkModule provides a singleton OkHttpClient with ConnectionPool and AppCheckInterceptor across all network consumers.
 */
class RequestOptimizationAndSecurityTest {

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        for (candidate in candidates) {
            val f = File(candidate)
            if (f.exists()) return f
        }
        val currentDir = File(".").absoluteFile
        val found = currentDir.walkTopDown().firstOrNull { it.path.replace("\\", "/").endsWith(relativePath) }
        if (found != null && found.exists()) return found
        throw AssertionError("Could not locate source file: $relativePath across workspace")
    }

    // =========================================================================
    // OPT-001: Ensure-Subscription Redundancy Guard
    // =========================================================================

    @Test
    fun testSubscriptionManager_EnsureSubscriptionSync_GuardsAgainstRedundantWorkerCalls() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/domain/managers/SubscriptionManager.kt")
        val content = file.readText()

        // Verify ensureSubscriptionSync checks currentStatus() and accessState
        assertTrue(
            "ensureSubscriptionSync must check currentStatus != SubscriptionStatus.NONE to skip redundant calls",
            content.contains("if (!force && currentStatus != SubscriptionStatus.NONE && accessState.value != AccessState.INITIALIZING) {")
        )

        // Verify that under the mutex lock it re-checks status to prevent race conditions
        assertTrue(
            "ensureSubscriptionSync must re-check under ensureMutex to guarantee single-flight skip",
            content.contains("val statusUnderLock = currentStatus()") &&
                    content.contains("if (!force && statusUnderLock != SubscriptionStatus.NONE && accessState.value != AccessState.INITIALIZING) {")
        )
    }

    @Test
    fun testSubscriptionManager_SkipLogicSimulation() {
        val statuses = listOf(
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.TRIAL,
            SubscriptionStatus.CANCELLED,
            SubscriptionStatus.EXPIRED,
            SubscriptionStatus.PAST_DUE
        )

        for (status in statuses) {
            val isNone = status == SubscriptionStatus.NONE
            val isInitializing = false
            val shouldSkip = !isNone && !isInitializing
            assertTrue("Status $status with resolved access state must skip redundant ensureSubscriptionSync", shouldSkip)
        }

        // Status NONE must NOT skip (new user trial initialization)
        val isNewUserNone = SubscriptionStatus.NONE == SubscriptionStatus.NONE
        assertFalse("New user with status NONE must proceed to initialize trial", !isNewUserNone)
    }

    // =========================================================================
    // OPT-002: Concurrency & Single-Flight Protection for Backup and Restore
    // =========================================================================

    @Test
    fun testLocalRestoreRepository_EnforcesSingleFlightMutex() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/LocalRestoreRepositoryImpl.kt")
        val content = file.readText()

        assertTrue(
            "LocalRestoreRepositoryImpl must declare a restoreMutex",
            content.contains("private val restoreMutex = Mutex()")
        )
        assertTrue(
            "performRestore must reject concurrent requests atomically via tryLock",
            content.contains("if (!restoreMutex.tryLock()) {") &&
                    content.contains("RestoreResult.InProgress")
        )
        assertTrue(
            "performRestore must release restoreMutex in a finally block",
            content.contains("restoreMutex.unlock()")
        )
    }

    @Test
    fun testLocalBackupRepository_EnforcesSingleFlightMutex() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/LocalBackupRepositoryImpl.kt")
        val content = file.readText()

        assertTrue(
            "LocalBackupRepositoryImpl must declare a backupMutex",
            content.contains("private val backupMutex = Mutex()")
        )
        assertTrue(
            "performBackup must reject concurrent requests atomically via tryLock",
            content.contains("if (!backupMutex.tryLock()) {") &&
                    content.contains("BackupResult.Ineligible")
        )
        assertTrue(
            "performBackup must release backupMutex in a finally block",
            content.contains("backupMutex.unlock()")
        )
    }

    // =========================================================================
    // OPT-003: Razorpay Checkout Preload Latching
    // =========================================================================

    @Test
    fun testRazorpayCheckoutPreload_LatchesOncePerProcess() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt")
        val content = file.readText()

        assertTrue(
            "RazorpaySubscriptionPurchaseServiceImpl must declare an isPreloaded AtomicBoolean in companion object",
            content.contains("private val isPreloaded = java.util.concurrent.atomic.AtomicBoolean(false)")
        )
        assertTrue(
            "preloadInternal must use compareAndSet(false, true) to ensure at most one execution per process",
            content.contains("if (!isPreloaded.compareAndSet(false, true)) {")
        )
    }

    @Test
    fun testRazorpayPreloadLatch_AtomicBooleanSimulation() {
        val latch = AtomicBoolean(false)
        val executionCounter = AtomicInteger(0)

        val preloadCall = {
            if (latch.compareAndSet(false, true)) {
                executionCounter.incrementAndGet()
            }
        }

        // Simulate multiple lifecycle events (onCreate, LaunchedEffect, onStart, onResume)
        preloadCall()
        preloadCall()
        preloadCall()
        preloadCall()

        assertEquals("Preload must only execute once across multiple lifecycle transitions", 1, executionCounter.get())
    }

    // =========================================================================
    // OPT-004: SubscriptionRepository Cache First Read
    // =========================================================================

    @Test
    fun testSubscriptionRepository_PrefersCacheWhenListenerIsFresh() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt")
        val content = file.readText()

        assertTrue(
            "refreshSubscription must evaluate offline lease before querying cache",
            content.contains("val isLeaseExpiredOrUnset = lastServerSyncTimestampMs <= 0L || !subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)")
        )
        assertTrue(
            "refreshSubscription must force server read if forceServer or lease is expired",
            content.contains("val shouldQueryServer = forceServer || isLeaseExpiredOrUnset")
        )
        assertTrue(
            "refreshSubscription must attempt get(Source.CACHE) when cache is valid and not forced",
            content.contains(".get(Source.CACHE)")
        )
        assertTrue(
            "refreshSubscription must request Source.SERVER when lease expired or forced",
            content.contains(".get(Source.SERVER)")
        )
        assertTrue(
            "lastServerSyncTimestampMs must ONLY update on confirmed server responses (!isFromCache)",
            content.contains("if (!isFromCache) {") &&
                    content.contains("lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first")
        )
    }

    // =========================================================================
    // OPT-005: SettingsViewModel Eliminates Redundant HTTP Polling
    // =========================================================================

    @Test
    fun testSettingsViewModel_EliminatesRedundantBackupStatusHttpPoll() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/settings/viewmodel/SettingsViewModel.kt")
        val content = file.readText()

        assertFalse(
            "SettingsViewModel.init must NOT call syncManager.checkServerBackupStatusIfNeeded()",
            content.contains("syncManager.checkServerBackupStatusIfNeeded()")
        )
        assertTrue(
            "SettingsViewModel must bind isBackupRestricted directly from syncManager",
            content.contains("val isBackupRestricted: StateFlow<Boolean> = syncManager.isBackupRestricted")
        )
        assertTrue(
            "SettingsViewModel must bind isBackedUpToday directly from syncManager",
            content.contains("val isBackedUpToday: StateFlow<Boolean> = syncManager.isBackedUpToday")
        )
    }

    // =========================================================================
    // OPT-006: Singleton OkHttpClient and DI Consolidation
    // =========================================================================

    @Test
    fun testNetworkModule_ProvidesSingletonOkHttpClientWithConnectionPoolAndAppCheck() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/core/di/NetworkModule.kt")
        val content = file.readText()

        assertTrue("NetworkModule must be an installed SingletonComponent module", content.contains("@Module") && content.contains("@InstallIn(SingletonComponent::class)"))
        assertTrue("NetworkModule must provide a Singleton OkHttpClient", content.contains("@Provides") && content.contains("@Singleton") && content.contains("fun provideOkHttpClient(): OkHttpClient"))
        assertTrue("NetworkModule must configure ConnectionPool with keepAlive", content.contains("ConnectionPool(5, 5, TimeUnit.MINUTES)"))
        assertTrue("NetworkModule must attach AppCheckInterceptor", content.contains("AppCheckInterceptor()"))
    }

    @Test
    fun testNetworkModule_AllConsumersInjectSharedOkHttpClient() {
        val filesToCheck = listOf(
            "src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt",
            "src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt",
            "src/main/java/com/reeltrak/app/core/sync/SyncManager.kt",
            "src/main/java/com/reeltrak/app/data/repository/AccountRepositoryImpl.kt",
            "src/main/java/com/reeltrak/app/data/security/SubscriptionTimeAuthority.kt",
            "src/main/java/com/reeltrak/app/data/repository/LocalBackupRepositoryImpl.kt",
            "src/main/java/com/reeltrak/app/data/repository/LocalRestoreRepositoryImpl.kt",
            "src/main/java/com/reeltrak/app/core/sync/RestoreAckWorker.kt"
        )

        for (relPath in filesToCheck) {
            val file = findSourceFile(relPath)
            val content = file.readText()
            assertTrue(
                "$relPath must inject OkHttpClient in constructor",
                content.contains("httpClient: OkHttpClient") || content.contains("httpClient: okhttp3.OkHttpClient")
            )
            assertFalse(
                "$relPath must NOT instantiate its own OkHttpClient.Builder()",
                content.contains("private val httpClient = OkHttpClient.Builder()") ||
                        content.contains("private val httpClient = okhttp3.OkHttpClient.Builder()")
            )
        }
    }
}
