package com.reeltrak.app.core.network

/**
 * Authoritative Centralized Network Configuration for Re:Focus
 *
 * All Android repositories and services targeting the backend MUST consume
 * the canonical Worker base URL defined here or via BuildConfig.
 */
object NetworkConstants {
    const val DEFAULT_WORKER_BASE_URL = "https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev"

    /**
     * Authoritative Production Cloudflare Worker Base URL.
     * Can be overridden by BuildConfig.WORKER_BASE_URL when defined.
     */
    val WORKER_BASE_URL: String
        get() = DEFAULT_WORKER_BASE_URL
}
