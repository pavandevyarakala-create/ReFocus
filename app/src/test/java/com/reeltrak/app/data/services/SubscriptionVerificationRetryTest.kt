package com.reeltrak.app.data.services

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SubscriptionVerificationRetryTest {
    @Test
    fun transientServerFailuresRetryUntilAuthoritativeVerificationSucceeds() = runBlocking {
        var calls = 0
        val result = withSubscriptionVerificationRetry(
            execute = {
                calls++
                if (calls < 3) 503 else 200
            },
            shouldRetry = { isRetryableSubscriptionVerificationStatus(it) },
            wait = { _ -> }
        )

        assertEquals(200, result)
        assertEquals(3, calls)
    }

    @Test
    fun permanentClientFailureIsNotRetried() = runBlocking {
        var calls = 0
        val result = withSubscriptionVerificationRetry(
            execute = {
                calls++
                403
            },
            shouldRetry = { isRetryableSubscriptionVerificationStatus(it) },
            wait = { _ -> }
        )

        assertEquals(403, result)
        assertEquals(1, calls)
    }

    @Test
    fun rateLimitResponsesAreRetryableButOtherClientErrorsAreNot() {
        assertEquals(true, isRetryableSubscriptionVerificationStatus(429))
        assertEquals(true, isRetryableSubscriptionVerificationStatus(503))
        assertEquals(false, isRetryableSubscriptionVerificationStatus(400))
        assertEquals(false, isRetryableSubscriptionVerificationStatus(403))
    }

    @Test
    fun retryableHttpFailureStopsAfterThreeAttemptsAndRemainsUnverified() = runBlocking {
        var calls = 0
        val lastStatus = withSubscriptionVerificationRetry(
            execute = {
                calls++
                503
            },
            shouldRetry = { isRetryableSubscriptionVerificationStatus(it) },
            wait = { _ -> }
        )

        assertEquals(503, lastStatus)
        assertEquals(3, calls)
    }

    @Test
    fun transportFailureStopsAfterThreeAttemptsWithoutConvertingToSuccess() = runBlocking {
        var calls = 0
        var failed = false
        try {
            withSubscriptionVerificationRetry(
                execute = {
                    calls++
                    throw IOException("temporary transport failure")
                },
                shouldRetry = { isRetryableSubscriptionVerificationStatus(it) },
                wait = { _ -> }
            )
        } catch (_: IOException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(3, calls)
    }
}
