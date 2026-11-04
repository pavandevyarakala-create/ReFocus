package com.reeltrak.app.core.network

import com.reeltrak.app.BuildConfig

/**
 * Authoritative Centralized Network Configuration for Re:Focus
 *
 * All Android repositories and services targeting the backend MUST consume
 * the canonical Worker base URL defined here or via BuildConfig.
 */
object NetworkConstants {
    const val PROD_WORKER_BASE_URL = "https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev"
    const val DEFAULT_WORKER_BASE_URL = PROD_WORKER_BASE_URL

    /**
     * Strictly validates and resolves the Worker base URL according to the active build environment.
     * Prevents cross-environment misrouting (e.g. staging talking to prod or vice versa).
     */
    fun resolveWorkerBaseUrl(environment: String, configuredUrl: String): String {
        val env = environment.trim().lowercase()
        val url = configuredUrl.trim().removeSuffix("/")

        require(url.isNotEmpty()) { "Worker base URL cannot be blank." }
        require(url.startsWith("https://")) { "Worker base URL must be HTTPS: $url" }

        when (env) {
            "prod", "production" -> {
                if (url != PROD_WORKER_BASE_URL) {
                    throw IllegalStateException(
                        "CRITICAL SECURITY FAULT: Production build is misconfigured to point to non-production endpoint: $url"
                    )
                }
            }
            "staging" -> {
                if (url == PROD_WORKER_BASE_URL) {
                    throw IllegalStateException(
                        "CRITICAL ISOLATION FAULT: Staging build cannot point to production endpoint ($PROD_WORKER_BASE_URL)."
                    )
                }
                if (url.contains("PLACEHOLDER", ignoreCase = true)) {
                    throw IllegalStateException(
                        "CRITICAL CONFIGURATION FAULT: Staging build cannot use placeholder worker base URL: $url"
                    )
                }
            }
            else -> {
                // For test or default unconfigured environments
                if (url.contains("PLACEHOLDER", ignoreCase = true)) {
                    throw IllegalStateException("Invalid placeholder endpoint in environment '$environment': $url")
                }
            }
        }
        return url
    }

    /**
     * Authoritative Cloudflare Worker Base URL resolved per environment.
     */
    val WORKER_BASE_URL: String
        get() = try {
            val env = runCatching { BuildConfig.ENVIRONMENT }.getOrNull() ?: "prod"
            val url = runCatching { BuildConfig.WORKER_BASE_URL }.getOrNull() ?: DEFAULT_WORKER_BASE_URL
            resolveWorkerBaseUrl(env, url)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            DEFAULT_WORKER_BASE_URL
        }
}
