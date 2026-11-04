package com.reeltrak.app.core.network

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Attaches the Firebase App Check token (X-Firebase-AppCheck) to outgoing backend requests.
 * 
 * Safe token acquisition:
 * - Does not crash if App Check token retrieval fails.
 * - Does not log sensitive token values.
 * - Times out quickly if attestation takes too long.
 * - Attaches token ONLY when the request host exactly matches the configured Cloudflare Worker host.
 */
class AppCheckInterceptor(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val customTokenProvider: (() -> String?)? = null,
    private val targetHost: String? = null
) : Interceptor {

    constructor(customTokenProvider: (() -> String?)?) : this(DEFAULT_TIMEOUT_MS, customTokenProvider, null)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
    }

    private val TAG = "AppCheckInterceptor"

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestHost = originalRequest.url.host
        val expectedHost = targetHost ?: NetworkConstants.WORKER_BASE_URL.toHttpUrlOrNull()?.host

        val isTargetHost = expectedHost != null && requestHost.equals(expectedHost, ignoreCase = true)

        var token: String? = null
        if (isTargetHost) {
            try {
                if (customTokenProvider != null) {
                    token = customTokenProvider.invoke()
                } else {
                    val appCheck = FirebaseAppCheck.getInstance()
                    val tokenTask = appCheck.getAppCheckToken(false)
                    val result = Tasks.await(tokenTask, timeoutMs, TimeUnit.MILLISECONDS)
                    token = result?.token
                }
            } catch (e: Exception) {
                Log.w(TAG, "App Check token retrieval unavailable or timed out: ${e.javaClass.simpleName}")
            }
        }

        val requestBuilder = originalRequest.newBuilder()
        if (isTargetHost && !token.isNullOrBlank()) {
            requestBuilder.header("X-Firebase-AppCheck", token)
        } else {
            requestBuilder.removeHeader("X-Firebase-AppCheck")
        }

        return chain.proceed(requestBuilder.build())
    }
}

