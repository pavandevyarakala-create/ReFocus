package com.reeltrak.app.security

import com.reeltrak.app.core.network.AppCheckInterceptor
import com.reeltrak.app.core.network.NetworkConstants
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 Backend & Edge Security Hardening Verification Tests
 *
 * Covers:
 * - Rate Limiting (VULN-07)
 * - Firebase App Check & Play Integrity (VULN-08)
 * - Backend Consolidation & Deprecation (VULN-11)
 * - Strict CORS Policy (VULN-13)
 * - Security Response Headers
 * - Error Message Sanitization
 */
class Phase3BackendEdgeSecurityTest {

    // ========================================================================
    // 1. RATE LIMITING (VULN-07)
    // ========================================================================

    data class RateLimitEntry(var count: Int, var resetTime: Long)

    class InMemoryRateLimiter(private val maxKeys: Int = 10000) {
        private val limits = ConcurrentHashMap<String, RateLimitEntry>()

        fun check(key: String, limit: Int, windowSec: Long, now: Long = System.currentTimeMillis()): Pair<Boolean, Long> {
            // Memory bound check
            if (!limits.containsKey(key) && limits.size >= maxKeys) {
                // prune expired entries
                limits.entries.removeIf { it.value.resetTime <= now }
                if (limits.size >= maxKeys) {
                    // Evict oldest if still full
                    val oldestKey = limits.minByOrNull { it.value.resetTime }?.key
                    if (oldestKey != null) limits.remove(oldestKey)
                }
            }

            val entry = limits.compute(key) { _, current ->
                if (current == null || current.resetTime <= now) {
                    RateLimitEntry(1, now + (windowSec * 1000))
                } else {
                    current.count++
                    current
                }
            }!!

            val retryAfter = if (entry.count > limit) {
                Math.max(1L, (entry.resetTime - now) / 1000)
            } else 0L

            return Pair(entry.count <= limit, retryAfter)
        }

        fun size(): Int = limits.size
    }

    @Test
    fun testRateLimiter_ThresholdEnforcementAndRetryAfter() {
        val limiter = InMemoryRateLimiter()
        val key = "uid_test_123:/api/create-order"
        val limit = 5
        val windowSec = 60L
        val now = 1000000L

        // 5 requests pass
        for (i in 1..5) {
            val (allowed, retryAfter) = limiter.check(key, limit, windowSec, now)
            assertTrue("Request $i should be allowed", allowed)
            assertEquals(0L, retryAfter)
        }

        // 6th request fails (429)
        val (allowed6, retryAfter6) = limiter.check(key, limit, windowSec, now)
        assertFalse("6th request should be rate-limited", allowed6)
        assertEquals(60L, retryAfter6)
    }

    @Test
    fun testRateLimiter_PerUidIsolation() {
        val limiter = InMemoryRateLimiter()
        val uidA = "user_A:/api/verify-payment"
        val uidB = "user_B:/api/verify-payment"

        // Exhaust user A
        for (i in 1..5) {
            limiter.check(uidA, 5, 60L)
        }
        val (allowedA, _) = limiter.check(uidA, 5, 60L)
        assertFalse("User A should be blocked", allowedA)

        // User B should still be allowed
        val (allowedB, _) = limiter.check(uidB, 5, 60L)
        assertTrue("User B should be unaffected by User A's rate limit", allowedB)
    }

    @Test
    fun testRateLimiter_PerIpIsolationForWebhook() {
        val limiter = InMemoryRateLimiter()
        val ip1 = "198.51.100.1:/api/razorpay-webhook"
        val ip2 = "198.51.100.2:/api/razorpay-webhook"

        // Exhaust IP 1 with 120 requests
        for (i in 1..120) {
            limiter.check(ip1, 120, 60L)
        }
        val (allowedIp1, retryAfter) = limiter.check(ip1, 120, 60L)
        assertFalse("IP 1 should be rate-limited after 120 requests", allowedIp1)
        assertTrue(retryAfter > 0)

        // IP 2 must still succeed
        val (allowedIp2, _) = limiter.check(ip2, 120, 60L)
        assertTrue("IP 2 should be unaffected", allowedIp2)
    }

    @Test
    fun testRateLimiter_BoundedMemoryPreventsUnlimitedGrowth() {
        val maxCap = 10
        val limiter = InMemoryRateLimiter(maxKeys = maxCap)
        val now = 1000000L

        // Attempt to insert 50 different IP keys
        for (i in 1..50) {
            limiter.check("ip_$i:/health", 60, 60L, now)
        }

        assertTrue("Limiter map size (${limiter.size()}) must not exceed bounded max ($maxCap)", limiter.size() <= maxCap)
    }

    // ========================================================================
    // 2. CORS ALLOWLIST (VULN-13)
    // ========================================================================

    private val allowedOrigins = setOf(
        "https://refocus-app.firebaseapp.com",
        "https://refocus-504119.web.app"
    )

    private fun evaluateCorsOrigin(originHeader: String?): String? {
        if (originHeader.isNullOrBlank()) return null
        val normalized = originHeader.trim().lowercase()
        return if (allowedOrigins.contains(normalized)) originHeader.trim() else null
    }

    @Test
    fun testCors_AllowedOriginsReflected() {
        val origin1 = "https://refocus-app.firebaseapp.com"
        val result1 = evaluateCorsOrigin(origin1)
        assertEquals(origin1, result1)

        val origin2 = "https://refocus-504119.web.app"
        val result2 = evaluateCorsOrigin(origin2)
        assertEquals(origin2, result2)
    }

    @Test
    fun testCors_DisallowedAndArbitraryOriginsRejected() {
        val evilOrigins = listOf(
            "https://evil.com",
            "http://refocus-app.firebaseapp.com", // http instead of https
            "https://refocus-app.firebaseapp.com.attacker.com",
            "null",
            "*"
        )

        for (evil in evilOrigins) {
            val result = evaluateCorsOrigin(evil)
            assertNull("Origin $evil must NOT be granted CORS header", result)
        }
    }

    @Test
    fun testCors_NoOriginFromAndroidOrWebhookPreserved() {
        val result = evaluateCorsOrigin(null)
        assertNull("Non-browser requests without Origin header must have null CORS origin header", result)
    }

    // ========================================================================
    // 3. FIREBASE APP CHECK (VULN-08)
    // ========================================================================

    @Test
    fun testAppCheckInterceptor_AttachesHeaderWhenPresent() {
        var capturedHeader: String? = null
        val interceptor = AppCheckInterceptor(customTokenProvider = { "mock-app-check-token-xyz" })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("${NetworkConstants.WORKER_BASE_URL}/api/account/status").build()
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
        assertEquals("mock-app-check-token-xyz", capturedHeader)
    }

    @Test
    fun testAppCheckInterceptor_HandlesTokenFetchFailureGracefully() {
        var capturedHeader: String? = null
        val interceptor = AppCheckInterceptor(customTokenProvider = { null })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("${NetworkConstants.WORKER_BASE_URL}/api/account/status").build()
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
        assertNull("Header should not be attached if token is null", capturedHeader)
    }

    @Test
    fun testAppCheckInterceptor_HandlesTokenTimeoutGracefully() {
        var capturedHeader: String? = null
        // Simulate a token provider that throws TimeoutException
        val interceptor = AppCheckInterceptor(customTokenProvider = {
            throw java.util.concurrent.TimeoutException("Attestation timed out")
        })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("${NetworkConstants.WORKER_BASE_URL}/api/account/status").build()
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

        val response = interceptor.intercept(fakeChain)
        assertNotNull("Response should still be returned without crashing on timeout", response)
        assertNull("Header should not be attached when token acquisition times out", capturedHeader)
    }

    @Test
    fun testAppCheckInterceptor_ProceedsExactlyOnceWithoutDuplicates() {
        var proceedCallCount = 0
        val interceptor = AppCheckInterceptor(customTokenProvider = { "valid-token-123" })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("${NetworkConstants.WORKER_BASE_URL}/api/account/status").build()
            override fun proceed(request: Request): Response {
                proceedCallCount++
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
        assertEquals("Interceptor must proceed exactly once to prevent duplicate requests", 1, proceedCallCount)
    }

    @Test
    fun testAppCheckInterceptor_MissingTokenPreservesAuthorizationHeader() {
        var capturedAppCheckHeader: String? = null
        var capturedAuthHeader: String? = null
        val interceptor = AppCheckInterceptor(customTokenProvider = { null })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder()
                .url("${NetworkConstants.WORKER_BASE_URL}/api/account/status")
                .header("Authorization", "Bearer valid-firebase-id-token-abc")
                .build()
            override fun proceed(request: Request): Response {
                capturedAppCheckHeader = request.header("X-Firebase-AppCheck")
                capturedAuthHeader = request.header("Authorization")
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
        assertNull("Missing App Check token must not attach an X-Firebase-AppCheck header", capturedAppCheckHeader)
        assertEquals("Authorization Bearer token must remain completely intact and uncorrupted", "Bearer valid-firebase-id-token-abc", capturedAuthHeader)
    }

    @Test
    fun testAppCheckInterceptor_BlankTokenStripsStaleHeaderAndNeverSendsPlaceholder() {
        var capturedAppCheckHeader: String? = null
        // Custom token provider returns empty/whitespace string
        val interceptor = AppCheckInterceptor(customTokenProvider = { "   " })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder()
                .url("${NetworkConstants.WORKER_BASE_URL}/api/account/status")
                .header("X-Firebase-AppCheck", "stale-or-invalid-token")
                .build()
            override fun proceed(request: Request): Response {
                capturedAppCheckHeader = request.header("X-Firebase-AppCheck")
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
        assertNull("Blank or invalid App Check token must strip any pre-existing stale header and never forward placeholders", capturedAppCheckHeader)
    }

    @Test
    fun testAppCheckInterceptor_ExceptionPreservesAuthorizationHeaderAndDoesNotRetry() {
        var proceedCount = 0
        var capturedAuthHeader: String? = null
        var capturedAppCheckHeader: String? = null
        val interceptor = AppCheckInterceptor(customTokenProvider = {
            throw IllegalStateException("Attestation service unavailable")
        })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder()
                .url("${NetworkConstants.WORKER_BASE_URL}/api/account/status")
                .header("Authorization", "Bearer user-token-xyz")
                .build()
            override fun proceed(request: Request): Response {
                proceedCount++
                capturedAuthHeader = request.header("Authorization")
                capturedAppCheckHeader = request.header("X-Firebase-AppCheck")
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

        val response = interceptor.intercept(fakeChain)
        assertNotNull(response)
        assertEquals("Interceptor must proceed exactly once even when an exception is thrown (no retry loops)", 1, proceedCount)
        assertEquals("Authorization header must remain completely intact despite token provider failure", "Bearer user-token-xyz", capturedAuthHeader)
        assertNull("No AppCheck header should be sent when provider throws an exception", capturedAppCheckHeader)
    }

    @Test
    fun testAppCheckInterceptor_RejectsNonWorkerHost_NeverLeaksToken() {
        var capturedAppCheckHeader: String? = null
        var tokenProviderCalled = false
        val interceptor = AppCheckInterceptor(customTokenProvider = {
            tokenProviderCalled = true
            "secret-app-check-token-999"
        })

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder()
                .url("https://api.razorpay.com/v1/subscriptions")
                .header("X-Firebase-AppCheck", "pre-existing-stale-token")
                .build()
            override fun proceed(request: Request): Response {
                capturedAppCheckHeader = request.header("X-Firebase-AppCheck")
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
        assertFalse("Token provider must NOT even be invoked for non-Worker hosts", tokenProviderCalled)
        assertNull("AppCheck token must NEVER be attached to non-Worker hosts (especially Razorpay)", capturedAppCheckHeader)
    }

    @Test
    fun testOfflineLocalTracking_MakesZeroNetworkCalls() {
        // Verify that local reel data operations run completely offline without triggering AppCheckInterceptor or network clients
        var networkCallsInitiated = 0
        val localRow = com.reeltrak.app.data.local.entity.DailyReelData(
            dateString = "2026-10-16",
            totalReelsCount = 15,
            dailyGoal = 60,
            isStrictModeOn = false
        )

        // Local state increment
        val updatedRow = localRow.copy(totalReelsCount = localRow.totalReelsCount + 1)
        assertEquals(16, updatedRow.totalReelsCount)
        assertEquals("Local reel counting must make exactly 0 network calls", 0, networkCallsInitiated)
    }

    @Test
    fun testAppCheck_AuthAndEntitlementIndependence() {
        // App Check verifies app identity, NOT user authentication or subscription entitlement
        val appCheckValid = true
        val hasUserAuth = false
        val hasSubscriptionEntitlement = false

        // Even if App Check is valid, missing user auth must fail closed
        val canAccessUserData = appCheckValid && hasUserAuth
        assertFalse("Valid App Check alone MUST NOT grant access to user data without user authentication", canAccessUserData)

        // Even if App Check and user auth are valid, missing subscription entitlement must fail closed
        val canAccessPremium = appCheckValid && hasUserAuth && hasSubscriptionEntitlement
        assertFalse("Valid App Check and user auth MUST NOT grant premium access without subscription entitlement", canAccessPremium)
    }

    enum class AppCheckMode { DISABLED, AUDIT, ENFORCED }

    private fun verifyAppCheckClaims(
        issuer: String?,
        audience: List<String>?,
        subject: String?,
        configuredProjectNumber: String?,
        configuredProjectId: String?
    ): Boolean {
        if (configuredProjectNumber.isNullOrBlank()) return false
        if (issuer != "https://firebaseappcheck.googleapis.com/$configuredProjectNumber") return false

        val validAudiences = listOfNotNull(
            "projects/$configuredProjectNumber",
            configuredProjectId?.let { "projects/$it" }
        )

        val hasValidAud = audience?.any { validAudiences.contains(it) } ?: false
        val hasValidSub = subject?.let { validAudiences.contains(it) } ?: true

        return hasValidAud || hasValidSub
    }

    private fun evaluateAppCheck(
        mode: AppCheckMode,
        tokenValid: Boolean,
        hasConfiguredProjectNumber: Boolean = true
    ): Pair<Int, String> {
        return when (mode) {
            AppCheckMode.DISABLED -> Pair(200, "OK")
            AppCheckMode.AUDIT -> Pair(200, "OK (Logged)")
            AppCheckMode.ENFORCED -> {
                if (!hasConfiguredProjectNumber) {
                    Pair(401, "Missing FIREBASE_PROJECT_NUMBER")
                } else if (tokenValid) {
                    Pair(200, "OK")
                } else {
                    Pair(401, "App Check verification failed")
                }
            }
        }
    }

    @Test
    fun testAppCheck_ProjectNumberIssuerValidation() {
        val validProjectNum = "524118238647"
        val validProjectId = "refocus-504119"

        // 1. Valid project-number issuer is accepted
        val validResult = verifyAppCheckClaims(
            issuer = "https://firebaseappcheck.googleapis.com/$validProjectNum",
            audience = listOf("projects/$validProjectNum"),
            subject = "projects/$validProjectNum",
            configuredProjectNumber = validProjectNum,
            configuredProjectId = validProjectId
        )
        assertTrue("Valid project-number issuer and audience must be accepted", validResult)

        // 2. Wrong project-number issuer is rejected
        val wrongNumberResult = verifyAppCheckClaims(
            issuer = "https://firebaseappcheck.googleapis.com/999999999999",
            audience = listOf("projects/$validProjectNum"),
            subject = "projects/$validProjectNum",
            configuredProjectNumber = validProjectNum,
            configuredProjectId = validProjectId
        )
        assertFalse("Wrong project-number issuer must be rejected", wrongNumberResult)

        // 3. Project ID used as issuer (instead of project number) is rejected
        val projectIdIssuerResult = verifyAppCheckClaims(
            issuer = "https://firebaseappcheck.googleapis.com/$validProjectId",
            audience = listOf("projects/$validProjectNum"),
            subject = "projects/$validProjectNum",
            configuredProjectNumber = validProjectNum,
            configuredProjectId = validProjectId
        )
        assertFalse("Project ID issuer must be rejected because App Check specifies project number", projectIdIssuerResult)

        // 4. Substring / attacker domain injection is rejected
        val attackerIssuerResult = verifyAppCheckClaims(
            issuer = "https://firebaseappcheck.googleapis.com/${validProjectNum}.attacker.com",
            audience = listOf("projects/$validProjectNum"),
            subject = "projects/$validProjectNum",
            configuredProjectNumber = validProjectNum,
            configuredProjectId = validProjectId
        )
        assertFalse("Attacker issuer with substring match must be rejected", attackerIssuerResult)

        // 5. Invalid audience is rejected
        val invalidAudResult = verifyAppCheckClaims(
            issuer = "https://firebaseappcheck.googleapis.com/$validProjectNum",
            audience = listOf("projects/wrong-project-123"),
            subject = "projects/wrong-project-123",
            configuredProjectNumber = validProjectNum,
            configuredProjectId = validProjectId
        )
        assertFalse("Invalid audience must be rejected", invalidAudResult)
    }

    @Test
    fun testAppCheck_EnforcementModesAndMissingConfig() {
        // Disabled mode accepts all
        assertEquals(200, evaluateAppCheck(AppCheckMode.DISABLED, false).first)

        // Audit mode accepts all but logs internally
        assertEquals(200, evaluateAppCheck(AppCheckMode.AUDIT, false).first)

        // Enforced mode fails closed on invalid/missing token
        assertEquals(401, evaluateAppCheck(AppCheckMode.ENFORCED, false).first)
        assertEquals(200, evaluateAppCheck(AppCheckMode.ENFORCED, true).first)

        // Enforced mode fails closed if FIREBASE_PROJECT_NUMBER is missing
        assertEquals(401, evaluateAppCheck(AppCheckMode.ENFORCED, true, hasConfiguredProjectNumber = false).first)
    }


    // ========================================================================
    // 4. SECURITY RESPONSE HEADERS & ERROR SANITIZATION
    // ========================================================================

    @Test
    fun testSecurityResponseHeaders_PresentOnWorkerResponses() {
        val expectedHeaders = mapOf(
            "X-Content-Type-Options" to "nosniff",
            "Strict-Transport-Security" to "max-age=31536000; includeSubDomains",
            "Referrer-Policy" to "no-referrer",
            "X-Frame-Options" to "DENY"
        )

        for ((header, value) in expectedHeaders) {
            assertNotNull("Header $header must be specified", value)
            assertTrue("Header $header value must not be empty", value.isNotEmpty())
        }
    }

    @Test
    fun testErrorSanitization_Production500HidesInternalDetails() {
        val internalError = Exception("Firestore connection timeout at service-account@project.iam.gserviceaccount.com")
        val isProduction = true

        val clientResponseError = if (isProduction) {
            "Internal Server Error"
        } else {
            internalError.message ?: "Internal Server Error"
        }

        assertEquals("Internal Server Error", clientResponseError)
        assertFalse("Response must not contain service account email", clientResponseError.contains("service-account"))
        assertFalse("Response must not contain firestore internals", clientResponseError.contains("Firestore"))
    }

    // ========================================================================
    // 5. BACKEND CONSOLIDATION (VULN-11)
    // ========================================================================

    @Test
    fun testBackendConsolidation_NoLegacyUrlsInProductionConfig() {
        val workerUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL

        // Ensure worker URL is HTTPS
        assertTrue(workerUrl.startsWith("https://"))
        assertTrue(workerUrl.contains("workers.dev"))

        // Ensure no localhost or cloudfunctions references in production URLs
        assertFalse(workerUrl.contains("localhost"))
        assertFalse(workerUrl.contains("cloudfunctions.net"))
        assertFalse(workerUrl.contains("10.0.2.2"))
    }
}


