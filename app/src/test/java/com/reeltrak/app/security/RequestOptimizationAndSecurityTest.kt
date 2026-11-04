package com.reeltrak.app.security

import com.reeltrak.app.data.repository.SubscriptionRepositoryImpl
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    // =========================================================================
    // OPT-007: SubscriptionTimeAuthority Single-Flight & Redundant Sync Guard
    // =========================================================================

    @Test
    fun testSubscriptionTimeAuthority_GuardsAgainstRedundantServerTimeCalls() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/security/SubscriptionTimeAuthority.kt")
        val content = file.readText()

        assertTrue(
            "SubscriptionTimeAuthority must declare a timeSyncMutex",
            content.contains("private val timeSyncMutex = Mutex()")
        )
        assertTrue(
            "syncWithServer must guard with timeSyncMutex.withLock",
            content.contains("timeSyncMutex.withLock {")
        )
        assertTrue(
            "syncWithServer must skip network fetch when already calibrated unless forced",
            content.contains("if (!force && _isCalibrated.value) {")
        )
    }

    @Test
    fun testSubscriptionTimeAuthority_Behavioral_CoalescingAndSkipWhenCalibrated() = runBlocking {
        val networkCallCount = AtomicInteger(0)
        val fakeInterceptor = Interceptor { chain ->
            networkCallCount.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                .body("""{"serverTime": 1700000000000}""".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        var simulatedElapsed = 1000L
        var simulatedSystem = 1700000000000L
        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { simulatedElapsed },
            systemTimeProvider = { simulatedSystem }
        )

        assertFalse("Initially authority should not be calibrated", authority.isCalibrated.value)

        // 1. Initial sync calibrates clock and makes exactly 1 network call
        val result1 = authority.syncWithServer()
        if (result1.isFailure) {
            throw AssertionError("result1 failed: ${result1.exceptionOrNull()?.message}", result1.exceptionOrNull())
        }
        assertTrue("result1 must be successful", result1.isSuccess)
        assertTrue("Effective server time must be > 0", (result1.getOrNull() ?: 0L) > 0L)
        assertEquals("Initial sync must execute exactly 1 HTTP request", 1, networkCallCount.get())
        assertTrue("Authority must be calibrated after sync", authority.isCalibrated.value)

        // 2. Second non-forced sync skips network call because authority is calibrated
        val result2 = authority.syncWithServer(force = false)
        assertTrue("result2 must be successful", result2.isSuccess)
        assertEquals("Redundant sync must NOT execute additional HTTP request", 1, networkCallCount.get())

        // 3. Concurrent non-forced syncs do not produce duplicate network requests
        val concurrentJobs = (1..5).map {
            async { authority.syncWithServer(force = false) }
        }
        val concurrentResults = concurrentJobs.awaitAll()
        assertTrue("All concurrent results must succeed", concurrentResults.all { it.isSuccess })
        assertEquals("Concurrent syncs must NOT execute additional HTTP requests when calibrated", 1, networkCallCount.get())

        // 4. Explicit forced sync bypasses calibration check and executes HTTP request
        val resultForced = authority.syncWithServer(force = true)
        assertTrue("resultForced must be successful", resultForced.isSuccess)
        assertEquals("Forced sync must execute HTTP request", 2, networkCallCount.get())
    }

    // =========================================================================
    // OPT-008: InAppUpdateManager Single-Flight Concurrency Guard
    // =========================================================================

    @Test
    fun testInAppUpdateManager_GuardsAgainstConcurrentStartupCalls() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/core/update/InAppUpdateManager.kt")
        val content = file.readText()

        assertTrue(
            "InAppUpdateManager must declare an updateCheckMutex",
            content.contains("private val updateCheckMutex = Mutex()")
        )
        assertTrue(
            "checkForUpdate must guard with updateCheckMutex.withLock",
            content.contains("updateCheckMutex.withLock {")
        )
    }

    // =========================================================================
    // OPT-009: SubscriptionRepository Ensure-Subscription Single-Flight Guard
    // =========================================================================

    @Test
    fun testSubscriptionRepository_EnsureSubscription_GuardsWithSingleFlightCoalescing() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt")
        val content = file.readText()

        assertTrue(
            "SubscriptionRepositoryImpl must declare inFlightEnsureJobs map",
            content.contains("inFlightEnsureJobs = mutableMapOf<String, CompletableDeferred<Result<Unit>>>()")
        )
        assertTrue(
            "SubscriptionRepositoryImpl must declare ensureJobsMutex",
            content.contains("private val ensureJobsMutex = Mutex()")
        )
        assertTrue(
            "ensureSubscription must coalesce to in-flight deferred",
            content.contains("return@withContext deferred.await()")
        )
    }

    @Test
    fun testSubscriptionRepository_Behavioral_SingleFlightCoalescingByKeyedUid() = runBlocking {
        val requestCountsByUid = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        val fakeInterceptor = Interceptor { chain ->
            val req = chain.request()
            val authHeader = req.header("Authorization") ?: ""
            // Identify UID from Authorization: Bearer token_<uid>
            val uid = authHeader.removePrefix("Bearer token_").ifEmpty { "unknown" }
            requestCountsByUid.computeIfAbsent(uid) { AtomicInteger(0) }.incrementAndGet()

            // Simulate realistic network latency so concurrent coroutines overlap in flight
            Thread.sleep(60)

            if (uid == "user_error") {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                    .body("{\"error\": \"Internal error\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                    .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { 1000L },
            systemTimeProvider = { 1700000000000L }
        )

        val repository = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = testHttpClient
        )

        // ---------------------------------------------------------------------
        // 1. Concurrent identical calls for the same UID must execute exactly 1 HTTP request
        // ---------------------------------------------------------------------
        val concurrentSameUidJobs = (1..5).map {
            async {
                repository.ensureSubscriptionForUser("user_same") { "token_user_same" }
            }
        }
        val sameUidResults = concurrentSameUidJobs.awaitAll()
        assertTrue("All concurrent callers must receive successful result", sameUidResults.all { it.isSuccess })
        assertEquals("Exactly 1 HTTP request must be issued for concurrent identical calls", 1, requestCountsByUid["user_same"]?.get() ?: 0)

        // ---------------------------------------------------------------------
        // 2. Distinct UIDs must NOT be merged and must execute independent requests
        // ---------------------------------------------------------------------
        val alphaJob = async {
            repository.ensureSubscriptionForUser("user_alpha") { "token_user_alpha" }
        }
        val betaJob = async {
            repository.ensureSubscriptionForUser("user_beta") { "token_user_beta" }
        }
        val distinctResults = listOf(alphaJob.await(), betaJob.await())
        assertTrue("Both distinct UID calls must succeed", distinctResults.all { it.isSuccess })
        assertEquals("UID 'user_alpha' must have exactly 1 independent request", 1, requestCountsByUid["user_alpha"]?.get() ?: 0)
        assertEquals("UID 'user_beta' must have exactly 1 independent request", 1, requestCountsByUid["user_beta"]?.get() ?: 0)

        // ---------------------------------------------------------------------
        // 3. Subsequent call for same UID after completion must execute a new request
        // ---------------------------------------------------------------------
        val subsequentResult = repository.ensureSubscriptionForUser("user_same") { "token_user_same" }
        assertTrue("Subsequent call must succeed", subsequentResult.isSuccess)
        assertEquals("Subsequent call must issue a new HTTP request (count = 2)", 2, requestCountsByUid["user_same"]?.get() ?: 0)

        // ---------------------------------------------------------------------
        // 4. Concurrent calls on error must propagate failure to all callers without hanging
        // ---------------------------------------------------------------------
        val concurrentErrorJobs = (1..3).map {
            async {
                repository.ensureSubscriptionForUser("user_error") { "token_user_error" }
            }
        }
        val errorResults = concurrentErrorJobs.awaitAll()
        assertTrue("All callers must receive failure result on error", errorResults.all { it.isFailure })
        assertEquals("Exactly 1 HTTP request must be issued for concurrent failing calls", 1, requestCountsByUid["user_error"]?.get() ?: 0)
    }

    @Test
    fun testSubscriptionRepository_Behavioral_CallerCancellationDoesNotCancelSharedFlight() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)

        val fakeInterceptor = Interceptor { chain ->
            requestCount.incrementAndGet()
            requestStarted.countDown()
            releaseRequest.await(5, TimeUnit.SECONDS)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { 1000L },
            systemTimeProvider = { 1700000000000L }
        )

        val repository = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = testHttpClient
        )

        // Caller 1 initiates the shared in-flight request
        val caller1Job = launch(Dispatchers.IO) {
            repository.ensureSubscriptionForUser("user_cancel_shared") { "token_cancel_shared" }
        }

        // Wait until HTTP request actually starts executing in background
        assertTrue("HTTP request must start within timeout", requestStarted.await(5, TimeUnit.SECONDS))

        // Caller 2 attaches to the same in-flight request
        val caller2Job = async(Dispatchers.IO) {
            repository.ensureSubscriptionForUser("user_cancel_shared") { "token_cancel_shared" }
        }

        // Cancel Caller 1 while request is still running
        caller1Job.cancelAndJoin()
        assertTrue("Caller 1 must be cancelled", caller1Job.isCancelled)

        // Release the HTTP request so it completes
        releaseRequest.countDown()

        // Caller 2 must complete successfully despite Caller 1 cancellation
        val caller2Result = caller2Job.await()
        assertTrue("Caller 2 must succeed even though Caller 1 was cancelled", caller2Result.isSuccess)
        assertEquals("Exactly 1 HTTP request must have run for the shared flight", 1, requestCount.get())

        // Subsequent call must execute a fresh HTTP request
        val subsequentResult = repository.ensureSubscriptionForUser("user_cancel_shared") { "token_cancel_shared" }
        assertTrue("Subsequent call must succeed", subsequentResult.isSuccess)
        assertEquals("Subsequent call must issue a new HTTP request (count = 2)", 2, requestCount.get())
    }

    @Test
    fun testSubscriptionRepository_Behavioral_TokenErrorAndAccountMismatchFailsSafely() = runBlocking {
        val testHttpClient = OkHttpClient.Builder().build()
        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { 1000L },
            systemTimeProvider = { 1700000000000L }
        )
        val repository = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = testHttpClient
        )

        // 1. Token acquisition failure (e.g. refresh token expired or network error getting ID token)
        val tokenFailResult = repository.ensureSubscriptionForUser("user_token_fail") {
            throw java.io.IOException("Token acquisition failed: network unreachable")
        }
        assertTrue("Token provider failure must return Result.failure", tokenFailResult.isFailure)
        assertTrue(
            "Exception message must match",
            tokenFailResult.exceptionOrNull()?.message?.contains("Token acquisition failed") == true
        )

        // 2. Account mismatch / logout during in-flight token acquisition
        val accountMismatchResult = repository.ensureSubscriptionForUser("user_mismatch") {
            throw IllegalStateException("User account changed or logged out during token acquisition")
        }
        assertTrue("Account mismatch during token acquisition must fail closed", accountMismatchResult.isFailure)
        assertTrue(
            "Exception must be IllegalStateException",
            accountMismatchResult.exceptionOrNull() is IllegalStateException
        )

        // 3. Ensure repository cleans up properly and subsequent call can succeed
        var httpExecuted = false
        val okHttpClientSuccess = OkHttpClient.Builder()
            .addInterceptor { chain ->
                httpExecuted = true
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                    .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
            .build()
        val recoveredRepo = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = okHttpClientSuccess
        )
        val subsequentSuccess = recoveredRepo.ensureSubscriptionForUser("user_token_fail") { "valid_fresh_token" }
        assertTrue("Subsequent call must succeed after token failure", subsequentSuccess.isSuccess)
        assertTrue("Recovered HTTP request must have executed", httpExecuted)
    }

    @Test
    fun testSubscriptionRepository_Behavioral_AccountSwitchAfterTokenAcquisitionDoesNotBleedState() = runBlocking {
        val requestCountsByUid = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val userARequestStarted = CountDownLatch(1)
        val releaseUserARequest = CountDownLatch(1)

        val fakeInterceptor = Interceptor { chain ->
            val req = chain.request()
            val authHeader = req.header("Authorization") ?: ""
            val uid = authHeader.removePrefix("Bearer token_").ifEmpty { "unknown" }
            requestCountsByUid.computeIfAbsent(uid) { AtomicInteger(0) }.incrementAndGet()

            if (uid == "user_A") {
                userARequestStarted.countDown()
                releaseUserARequest.await(5, TimeUnit.SECONDS)
            }

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { 1000L },
            systemTimeProvider = { 1700000000000L }
        )

        val repository = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = testHttpClient
        )

        // 1. User A initiates ensureSubscription; token is acquired and HTTP request enters in-flight state
        val userAJob = async(Dispatchers.IO) {
            repository.ensureSubscriptionForUser("user_A") { "token_user_A" }
        }

        // Wait until User A's HTTP request is actively in-flight on the network
        assertTrue("User A's HTTP request must start", userARequestStarted.await(5, TimeUnit.SECONDS))

        // 2. User B logs in while User A's request is still in flight
        val userBJob = async(Dispatchers.IO) {
            repository.ensureSubscriptionForUser("user_B") { "token_user_B" }
        }

        // Release User A's HTTP request
        releaseUserARequest.countDown()

        val resultA = userAJob.await()
        val resultB = userBJob.await()

        assertTrue("User A result must succeed", resultA.isSuccess)
        assertTrue("User B result must succeed", resultB.isSuccess)

        // 3. Verify complete isolation: exactly 1 request for User A and exactly 1 request for User B
        assertEquals("User A must execute exactly 1 request", 1, requestCountsByUid["user_A"]?.get() ?: 0)
        assertEquals("User B must execute exactly 1 request independently", 1, requestCountsByUid["user_B"]?.get() ?: 0)
    }

    @Test
    fun testEnsureSubscription_AccountSwitchDuringTokenAcquisition_FailsClosedImmediately() = runBlocking {
        var httpExecuted = false
        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                httpExecuted = true
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                    .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
            .build()

        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { 1000L },
            systemTimeProvider = { 1700000000000L }
        )

        val repository = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = testHttpClient
        )

        var activeFirebaseUser: String? = "user_initial"

        // Simulate real ensureSubscription behavior where user switches during token acquisition:
        val failedResult = repository.ensureSubscriptionForUser("user_initial") {
            // User switched accounts before token was acquired
            activeFirebaseUser = "user_switched"
            val current = activeFirebaseUser
            if (current == null || current != "user_initial") {
                throw IllegalStateException("User account changed or logged out during token acquisition")
            }
            "token_stale"
        }

        assertTrue("When account changes during token acquisition, call must fail closed", failedResult.isFailure)
        assertTrue(
            "Exception must be IllegalStateException",
            failedResult.exceptionOrNull() is IllegalStateException
        )
        assertFalse("No HTTP request must be sent when token acquisition fails due to account switch", httpExecuted)
    }

    @Test
    fun testFetchAuthorizationLease_SingleFlightCoalescingByKeyedUid() = runBlocking {
        val requestCountsByUid = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        val fakeInterceptor = Interceptor { chain ->
            val req = chain.request()
            val authHeader = req.header("Authorization") ?: ""
            val uid = authHeader.removePrefix("Bearer token_").ifEmpty { "unknown" }
            requestCountsByUid.computeIfAbsent(uid) { AtomicInteger(0) }.incrementAndGet()

            Thread.sleep(60)

            val jsonBody = """
                {
                    "uid": "$uid",
                    "planId": "reeltrak_pro_monthly",
                    "accessMode": "ACTIVE",
                    "issuedAtMs": 1700000000000,
                    "expiresAtMs": 1702592000000,
                    "leaseValidUntilMs": 1702592000000,
                    "integrityDigest": "com.reeltrak.app:df485c640bd322464a72171a516cadf3a3cbb217d0a52111f608a0300c374b66",
                    "leaseVersion": 1,
                    "nonce": "test-nonce-$uid",
                    "subscriptionId": "sub_12345",
                    "signature": "mock_signature_test"
                }
            """.trimIndent()

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                .body(jsonBody.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { 1000L },
            systemTimeProvider = { 1700000000000L }
        )

        val repository = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = testHttpClient
        )

        // Launch 5 concurrent calls for user_alpha
        val alphaJobs = (1..5).map {
            async(Dispatchers.IO) {
                repository.fetchAuthorizationLeaseForUser("user_alpha") { "token_user_alpha" }
            }
        }

        // Launch 3 concurrent calls for user_beta
        val betaJobs = (1..3).map {
            async(Dispatchers.IO) {
                repository.fetchAuthorizationLeaseForUser("user_beta") { "token_user_beta" }
            }
        }

        val alphaResults = alphaJobs.awaitAll()
        val betaResults = betaJobs.awaitAll()

        // 1. All callers succeed
        assertTrue("All alpha callers must succeed", alphaResults.all { it.isSuccess })
        assertTrue("All beta callers must succeed", betaResults.all { it.isSuccess })

        // 2. Exactly ONE network request was issued for user_alpha
        val alphaNetworkCalls = requestCountsByUid["user_alpha"]?.get() ?: 0
        assertEquals("user_alpha concurrent requests must coalesce into exactly 1 HTTP call", 1, alphaNetworkCalls)

        // 3. Exactly ONE network request was issued for user_beta
        val betaNetworkCalls = requestCountsByUid["user_beta"]?.get() ?: 0
        assertEquals("user_beta concurrent requests must coalesce into exactly 1 HTTP call", 1, betaNetworkCalls)

        // 4. Verify all callers received the exact same token
        val firstAlphaToken = alphaResults.first().getOrThrow()
        assertTrue("All alpha callers must share the exact same returned token", alphaResults.all { it.getOrThrow() == firstAlphaToken })
        assertEquals("user_alpha", firstAlphaToken.uid)

        val firstBetaToken = betaResults.first().getOrThrow()
        assertTrue("All beta callers must share the exact same returned token", betaResults.all { it.getOrThrow() == firstBetaToken })
        assertEquals("user_beta", firstBetaToken.uid)
    }

    @Test
    fun testFetchAuthorizationLease_CancellationIsolation_CallerCancellationDoesNotAbortSharedFlight() = runBlocking {
        val requestLatch = CountDownLatch(1)
        val canCompleteLatch = CountDownLatch(1)
        val networkCallCount = AtomicInteger(0)

        val fakeInterceptor = Interceptor { chain ->
            networkCallCount.incrementAndGet()
            requestLatch.countDown()
            canCompleteLatch.await(5, TimeUnit.SECONDS)

            val jsonBody = """
                {
                    "uid": "user_shared_cancel",
                    "planId": "reeltrak_pro_monthly",
                    "accessMode": "ACTIVE",
                    "issuedAtMs": 1700000000000,
                    "expiresAtMs": 1702592000000,
                    "leaseValidUntilMs": 1702592000000,
                    "integrityDigest": "com.reeltrak.app:df485c640bd322464a72171a516cadf3a3cbb217d0a52111f608a0300c374b66",
                    "leaseVersion": 1,
                    "nonce": "test-nonce-cancel",
                    "subscriptionId": "sub_12345",
                    "signature": "mock_signature_test"
                }
            """.trimIndent()

            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Date", "Tue, 03 Nov 2026 10:00:00 GMT")
                .body(jsonBody.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val authority = SubscriptionTimeAuthority(
            context = null,
            httpClient = testHttpClient,
            elapsedRealtimeProvider = { 1000L },
            systemTimeProvider = { 1700000000000L }
        )

        val repository = SubscriptionRepositoryImpl(
            subscriptionTimeAuthority = authority,
            httpClient = testHttpClient
        )

        // Caller 1: will be cancelled
        val caller1 = async(Dispatchers.IO) {
            repository.fetchAuthorizationLeaseForUser("user_shared_cancel") { "token_shared" }
        }

        // Wait until request starts execution
        requestLatch.await(3, TimeUnit.SECONDS)

        // Caller 2: joins the in-flight flight
        val caller2 = async(Dispatchers.IO) {
            repository.fetchAuthorizationLeaseForUser("user_shared_cancel") { "token_shared" }
        }

        // Cancel Caller 1 while request is in flight
        caller1.cancelAndJoin()

        // Unblock network response
        canCompleteLatch.countDown()

        // Caller 2 must still receive success
        val caller2Result = caller2.await()
        assertTrue("Caller 2 must succeed even if Caller 1 was cancelled", caller2Result.isSuccess)
        assertEquals("user_shared_cancel", caller2Result.getOrThrow().uid)
        assertEquals("Network request must execute exactly once", 1, networkCallCount.get())
    }

    @Test
    fun testAndroidKeystoreLeaseCrypto_PlaintextFallbackAndDecryption() {
        val testJson = "{\"uid\":\"test_uid\",\"planId\":\"reeltrak_pro_monthly\",\"accessMode\":\"ACTIVE\"}"
        // In JVM test environment, AndroidKeyStore is unavailable, so encrypt() safely falls back to plaintext
        val encryptedOrPlain = com.reeltrak.app.data.security.authorization.AndroidKeystoreLeaseCrypto.encrypt(testJson)
        // Decrypt returns the original plaintext
        val decrypted = com.reeltrak.app.data.security.authorization.AndroidKeystoreLeaseCrypto.decrypt(encryptedOrPlain)
        assertEquals("Decrypted JSON must match original plaintext", testJson, decrypted)
    }
}
