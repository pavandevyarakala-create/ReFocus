package com.reeltrak.app.data.services

import kotlinx.coroutines.delay
import java.io.IOException

internal fun isRetryableSubscriptionVerificationStatus(statusCode: Int): Boolean =
    statusCode == 429 || statusCode >= 500

/** Bounded retry for transient transport and server failures during authoritative verification. */
internal suspend fun <T> withSubscriptionVerificationRetry(
    execute: suspend () -> T,
    shouldRetry: (T) -> Boolean,
    wait: suspend (Long) -> Unit = { delay(it) }
): T {
    val maxAttempts = 3
    var lastError: IOException? = null

    repeat(maxAttempts) { attempt ->
        val result = try {
            execute()
        } catch (error: IOException) {
            lastError = error
            if (attempt == maxAttempts - 1) throw error
            wait(500L * (attempt + 1))
            return@repeat
        }

        if (!shouldRetry(result) || attempt == maxAttempts - 1) return result
        wait(500L * (attempt + 1))
    }

    throw lastError ?: IOException("Subscription verification retries were exhausted.")
}
