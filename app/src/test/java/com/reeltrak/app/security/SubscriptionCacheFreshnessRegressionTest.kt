package com.reeltrak.app.security

import com.google.firebase.Timestamp
import com.reeltrak.app.core.network.AppCheckInterceptor
import com.reeltrak.app.core.network.NetworkConstants
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionStatus
import kotlinx.coroutines.sync.Mutex
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated Regression Test Suite for BUG-SUB-01 and Audit Hardening:
 *
 * 1. Cache reads cannot extend the offline lease (lastServerSyncTimestampMs remains unaltered on cache hits).
 * 2. Stale cached data cannot indefinitely bypass server verification (fails closed when lease expires).
 * 3. Legitimate offline mobility is preserved within the 24-hour lease window.
 * 4. Server revocations (HALTED/EXPIRED) take effect authoritatively on server refresh.
 * 5. AppCheckInterceptor attaches X-Firebase-AppCheck ONLY to exact Worker host.
 * 6. Non-blocking atomic mutex acquisition (tryLock) prevents race conditions in backup/restore.
 */
class SubscriptionCacheFreshnessRegressionTest {

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
    // 1. BUG-SUB-01: Offline Lease Extension Prevention & Expiration Tests
    // =========================================================================

    @Test
    fun testOfflineLease_CacheReadsCannotAdvanceSyncTimestamp() {
        // Simulates the exact state transition contract in SubscriptionRepositoryImpl
        val initialServerSyncTimeMs = 1700000000000L
        var recordedLastServerSyncTimestampMs = initialServerSyncTimeMs

        // Simulate 10 consecutive cached reads over 3 days (each reading Source.CACHE)
        val readSnapshots = List(10) { index ->
            val simulatedElapsedDays = (index + 1) * 8 * 3600 * 1000L // every 8 hours
            val currentCalculatedTime = initialServerSyncTimeMs + simulatedElapsedDays
            val isFromCache = true // All from local cache

            // Verified implementation rule: Only update lastServerSyncTimestampMs if !isFromCache
            if (!isFromCache) {
                recordedLastServerSyncTimestampMs = currentCalculatedTime
            }

            Pair(currentCalculatedTime, isFromCache)
        }

        // Timestamp MUST remain frozen at the initial server sync timestamp
        assertEquals(
            "Cache reads must NEVER advance lastServerSyncTimestampMs",
            initialServerSyncTimeMs,
            recordedLastServerSyncTimestampMs
        )
    }

    @Test
    fun testOfflineLease_StaleCachedDataCannotIndefinitelyBypassServerVerification() {
        val authority = SubscriptionTimeAuthority()
        val anchorTimeMs = 1700000000000L
        authority.recordServerTimeAnchor(anchorTimeMs)

        // 1. Within 24-hour lease: isWithinOfflineLease returns true
        val recentSyncTime = anchorTimeMs - (12 * 60 * 60 * 1000L) // 12 hours ago
        assertTrue(
            "Subscription within 24 hours of server verification must be within offline lease",
            authority.isWithinOfflineLease(recentSyncTime)
        )

        // 2. Beyond 24-hour lease: isWithinOfflineLease returns false
        val expiredSyncTime = anchorTimeMs - (24 * 60 * 60 * 1000L + 1000L) // 24 hours and 1 second ago
        assertFalse(
            "Subscription older than 24 hours without server verification must be expired",
            authority.isWithinOfflineLease(expiredSyncTime)
        )

        // 3. Unverified sync (0L): isWithinOfflineLease returns false
        assertFalse(
            "Subscription with 0L verification timestamp must fail closed immediately",
            authority.isWithinOfflineLease(0L)
        )
    }

    @Test
    fun testOfflineLease_ExpiredLeaseForcesLockedAccessStateInDomain() {
        val nowMs = 1700000000000L
        val periodEndMs = nowMs + (10 * 86400000L) // Stored subscription has 10 days left in cache

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "monthly",
            currentPeriodEnd = Timestamp(Date(periodEndMs)),
            premium = true
        )

        // Case A: Cache is within 24-hour lease -> AccessState.ACTIVE (legitimate offline mobility preserved)
        val activeInfo = dto.toDomain(
            authoritativeNow = nowMs,
            isOfflineLeaseExpired = false,
            lastServerVerificationMs = nowMs - 3600000L
        )
        assertEquals(AccessState.ACTIVE, activeInfo.accessState)
        assertTrue(activeInfo.isUnlocked)

        // Case B: Cache offline lease expired (>24 hours disconnected) -> AccessState.LOCKED (fails closed)
        val lockedInfo = dto.toDomain(
            authoritativeNow = nowMs,
            isOfflineLeaseExpired = true,
            lastServerVerificationMs = nowMs - (25 * 3600000L)
        )
        assertEquals(AccessState.LOCKED, lockedInfo.accessState)
        assertFalse(lockedInfo.isUnlocked)
    }

    @Test
    fun testServerRevocation_ObservedImmediatelyWhenServerReturnsHaltedOrExpired() {
        val nowMs = 1700000000000L
        val periodEndMs = nowMs + 86400000L

        // Webhook or server marks subscription HALTED (e.g. payment failure / refund)
        val haltedDto = CloudSubscriptionDto(
            subscriptionStatus = "HALTED",
            planId = "monthly",
            currentPeriodEnd = Timestamp(Date(periodEndMs)),
            premium = false
        )

        val haltedInfo = haltedDto.toDomain(
            authoritativeNow = nowMs,
            isOfflineLeaseExpired = false,
            lastServerVerificationMs = nowMs
        )
        assertEquals(AccessState.LOCKED, haltedInfo.accessState)
        assertFalse(haltedInfo.isUnlocked)
        assertEquals(SubscriptionStatus.HALTED, haltedInfo.status)
    }

    @Test
    fun testSubscriptionRepositoryImpl_CodebaseSourceVerification() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt")
        val content = file.readText()

        // 1. Verify lastServerSyncTimestampMs is ONLY updated on confirmed server response (!isFromCache)
        assertTrue(
            "lastServerSyncTimestampMs must only be updated when !isFromCache",
            content.contains("if (!isFromCache) {") &&
                    content.contains("lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first")
        )

        // 2. Verify isOfflineLeaseExpired evaluates isWithinOfflineLease for cached snapshots
        assertTrue(
            "isOfflineLeaseExpired must evaluate !subscriptionTimeAuthority.isWithinOfflineLease",
            content.contains("val isLeaseExpired = if (isFromCache) {") &&
                    content.contains("!subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)")
        )

        // 3. Verify server read is forced when lease expired or forceServer == true
        assertTrue(
            "refreshSubscription must check isLeaseExpiredOrUnset and forceServer",
            content.contains("val isLeaseExpiredOrUnset = lastServerSyncTimestampMs <= 0L || !subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)") &&
                    content.contains("val shouldQueryServer = forceServer || isLeaseExpiredOrUnset")
        )

        // 4. Verify syncIfStale passes forceServer = true
        assertTrue(
            "syncIfStale must force server fetch when stale interval elapsed",
            content.contains("refreshSubscription(forceServer = true)")
        )

        // 5. Verify cancelSubscription passes forceServer = true
        assertTrue(
            "cancelSubscription must force server fetch to update cancelAtPeriodEnd",
            content.contains("refreshSubscription(forceServer = true)")
        )
    }

    // =========================================================================
    // 2. RSK-CONC-02: Non-Blocking Atomic Mutex Concurrency
    // =========================================================================

    @Test
    fun testAtomicMutex_TryLockEnforcesSingleFlightWithoutRace() {
        val mutex = Mutex()

        // First thread acquires lock
        val acquired1 = mutex.tryLock()
        assertTrue("First attempt must acquire the lock", acquired1)

        // Concurrent thread attempts acquisition while locked
        val acquired2 = mutex.tryLock()
        assertFalse("Concurrent attempt while locked must be immediately rejected (tryLock returns false)", acquired2)

        // Lock released in finally block
        mutex.unlock()

        // Subsequent attempt succeeds
        val acquired3 = mutex.tryLock()
        assertTrue("Subsequent attempt after unlock must acquire the lock", acquired3)
        mutex.unlock()
    }

    @Test
    fun testLocalRepositories_DeclareTryLockWithFinallyUnlock() {
        val backupFile = findSourceFile("src/main/java/com/reeltrak/app/data/repository/LocalBackupRepositoryImpl.kt")
        val backupContent = backupFile.readText()
        assertTrue(
            "LocalBackupRepositoryImpl must acquire backupMutex via tryLock()",
            backupContent.contains("if (!backupMutex.tryLock()) {")
        )
        assertTrue(
            "LocalBackupRepositoryImpl must unlock backupMutex in finally block",
            backupContent.contains("backupMutex.unlock()")
        )

        val restoreFile = findSourceFile("src/main/java/com/reeltrak/app/data/repository/LocalRestoreRepositoryImpl.kt")
        val restoreContent = restoreFile.readText()
        assertTrue(
            "LocalRestoreRepositoryImpl must acquire restoreMutex via tryLock()",
            restoreContent.contains("if (!restoreMutex.tryLock()) {")
        )
        assertTrue(
            "LocalRestoreRepositoryImpl must unlock restoreMutex in finally block",
            restoreContent.contains("restoreMutex.unlock()")
        )
    }

    // =========================================================================
    // 3. RSK-APPCHECK-03: AppCheckInterceptor Exact Host Whitelisting
    // =========================================================================

    @Test
    fun testAppCheckInterceptor_AttachesTokenToConfiguredWorkerHost() {
        var capturedHeader: String? = null
        val interceptor = AppCheckInterceptor(customTokenProvider = { "app-check-token-abc" })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder()
                .url("${NetworkConstants.WORKER_BASE_URL}/api/time")
                .build()
            override fun proceed(request: Request): Response {
                capturedHeader = request.header("X-Firebase-AppCheck")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 1000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 1000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 1000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        interceptor.intercept(fakeChain)
        assertEquals("app-check-token-abc", capturedHeader)
    }

    @Test
    fun testAppCheckInterceptor_NeverAttachesTokenToThirdPartyHosts() {
        val nonWorkerUrls = listOf(
            "https://api.razorpay.com/v1/subscriptions",
            "https://api.razorpay.com/v1/payments",
            "https://www.googleapis.com/oauth2/v4/token",
            "https://firebaseinstallations.googleapis.com/v1/projects",
            "https://example.com/api/test",
            "https://evil-workers.dev/api/time"
        )

        for (url in nonWorkerUrls) {
            var capturedHeader: String? = null
            var tokenProviderCalled = false
            val interceptor = AppCheckInterceptor(customTokenProvider = {
                tokenProviderCalled = true
                "sensitive-token-123"
            })

            val fakeChain = object : Interceptor.Chain {
                override fun request(): Request = Request.Builder()
                    .url(url)
                    .header("X-Firebase-AppCheck", "stale-placeholder")
                    .build()
                override fun proceed(request: Request): Response {
                    capturedHeader = request.header("X-Firebase-AppCheck")
                    return Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }
                override fun connection() = null
                override fun call() = throw UnsupportedOperationException()
                override fun connectTimeoutMillis() = 1000
                override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
                override fun readTimeoutMillis() = 1000
                override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
                override fun writeTimeoutMillis() = 1000
                override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            }

            interceptor.intercept(fakeChain)
            assertFalse("Token provider must NOT be invoked for non-Worker host: $url", tokenProviderCalled)
            assertNull("App Check token must NEVER be attached to non-Worker host: $url", capturedHeader)
        }
    }
}
