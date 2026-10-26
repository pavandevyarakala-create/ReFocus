package com.reeltrak.app.data.security

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated Security Clock Authority for Subscription & Trial Entitlement.
 *
 * ARCHITECTURAL CONTRACT:
 * - Completely isolated from normal app local date/time features (Domain A).
 * - Provides monotonic, server-anchored time for subscription/trial expiration decisions (Domain B).
 * - Immune to Android device date/time changes, timezone manipulation, and offline clock rollback attacks.
 * - Enforces bounded offline validity leases (MAX_OFFLINE_LEASE_MS = 24 hours).
 * - Tracks a persistent monotonic high-water timestamp to prevent backward time jumps across restarts.
 * - Detects device reboot and marks the clock as uncalibrated until a fresh server response re-anchors it.
 */
@Singleton
open class SubscriptionTimeAuthority {
    private val context: Context?
    private val httpClient: OkHttpClient

    @Inject
    constructor(@ApplicationContext context: Context, httpClient: OkHttpClient) {
        this.context = context
        this.httpClient = httpClient
        loadPersistedState()
    }

    constructor() {
        this.context = null
        this.httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val TAG = "SubTimeAuthority"
    private val PREFS_NAME = "subscription_security_time_prefs"

    private val KEY_SERVER_TIME_ANCHOR = "server_time_anchor"
    private val KEY_ELAPSED_REALTIME_ANCHOR = "elapsed_realtime_anchor"
    private val KEY_HIGH_WATER_TIME = "high_water_time"
    private val KEY_BOOT_COUNT = "boot_count"
    private val KEY_LAST_KNOWN_UPTIME = "last_known_uptime"

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL

    companion object {
        /**
         * Maximum allowed offline lease window: 24 Hours.
         * Rationale:
         * - Prevents indefinite offline premium theft while allowing users normal daily offline mobility.
         * - Beyond 24 hours of total disconnectivity or after reboot without server verification,
         *   unanchored entitlement fails closed to prevent stale privilege escalation.
         */
        const val MAX_OFFLINE_LEASE_MS = 24 * 60 * 60 * 1000L

        /**
         * Final-Sync Grace Window: Exactly 2 Minutes (120,000 ms).
         * Used ONLY for final daily data reconciliation/sync after expiration, NOT for premium access.
         */
        const val FINAL_SYNC_GRACE_WINDOW_MS = 2 * 60 * 1000L
    }

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated: StateFlow<Boolean> = _isCalibrated.asStateFlow()

    @Volatile
    private var serverAnchorMs: Long = 0L

    @Volatile
    private var elapsedRealtimeAnchorMs: Long = 0L

    @Volatile
    private var highWaterServerTimeMs: Long = 0L


    @Synchronized
    private fun loadPersistedState() {
        val p = prefs ?: return
        serverAnchorMs = p.getLong(KEY_SERVER_TIME_ANCHOR, 0L)
        elapsedRealtimeAnchorMs = p.getLong(KEY_ELAPSED_REALTIME_ANCHOR, 0L)
        highWaterServerTimeMs = p.getLong(KEY_HIGH_WATER_TIME, 0L)

        val currentElapsed = try { SystemClock.elapsedRealtime() } catch (e: Exception) { 0L }
        val lastKnownUptime = p.getLong(KEY_LAST_KNOWN_UPTIME, 0L)

        // Detect reboot: If current elapsedRealtime < stored anchor elapsedRealtime, device was rebooted
        val hasRebooted = (currentElapsed < elapsedRealtimeAnchorMs) || (currentElapsed < lastKnownUptime)

        if (serverAnchorMs > 0L && elapsedRealtimeAnchorMs > 0L && !hasRebooted) {
            _isCalibrated.value = true
            Log.d(TAG, "Subscription clock initialized from active session anchor (server: $serverAnchorMs, elapsed: $elapsedRealtimeAnchorMs)")
        } else {
            _isCalibrated.value = false
            if (hasRebooted) {
                Log.w(TAG, "Device reboot detected. Subscription time requires fresh server re-anchor.")
            } else {
                Log.d(TAG, "No initial subscription anchor found. Awaiting server synchronization.")
            }
        }
    }

    /**
     * Records an authoritative server timestamp anchor.
     * Can be called whenever an authenticated Worker response or /api/time response is received.
     */
    @Synchronized
    open fun recordServerTimeAnchor(serverTimestampMs: Long) {
        if (serverTimestampMs <= 0L) return

        val currentElapsed = try { SystemClock.elapsedRealtime() } catch (e: Exception) { 0L }
        
        // Enforce monotonicity against stored high-water mark
        val effectiveServerTime = maxOf(serverTimestampMs, highWaterServerTimeMs)

        serverAnchorMs = effectiveServerTime
        elapsedRealtimeAnchorMs = currentElapsed
        highWaterServerTimeMs = effectiveServerTime
        _isCalibrated.value = true

        prefs?.edit()
            ?.putLong(KEY_SERVER_TIME_ANCHOR, effectiveServerTime)
            ?.putLong(KEY_ELAPSED_REALTIME_ANCHOR, currentElapsed)
            ?.putLong(KEY_HIGH_WATER_TIME, effectiveServerTime)
            ?.putLong(KEY_LAST_KNOWN_UPTIME, currentElapsed)
            ?.apply()

        Log.d(TAG, "Anchored subscription clock to server: $effectiveServerTime at elapsedRealtime: $currentElapsed")
    }

    /**
     * Calculates the authoritative current subscription time in epoch milliseconds.
     * 
     * Formula:
     * subscriptionTrustedNow = serverAnchorMs + (SystemClock.elapsedRealtime() - elapsedRealtimeAnchorMs)
     * 
     * Guaranteed Properties:
     * 1. Monotonic across the boot session (via elapsedRealtime).
     * 2. Advancing high-water bound across process restarts and reboots.
     * 3. Completely independent of phone date/time settings.
     * 
     * Returns:
     * - Pair(authoritativeTimestampMs, isCalibrated)
     */
    @Synchronized
    open fun getAuthoritativeSubscriptionTime(): Pair<Long, Boolean> {
        val currentElapsed = try { SystemClock.elapsedRealtime() } catch (e: Exception) { 0L }
        
        // Update last known uptime to detect subsequent reboots
        val p = prefs
        if (p != null && currentElapsed > p.getLong(KEY_LAST_KNOWN_UPTIME, 0L)) {
            p.edit().putLong(KEY_LAST_KNOWN_UPTIME, currentElapsed).apply()
        }

        if (_isCalibrated.value && serverAnchorMs > 0L && elapsedRealtimeAnchorMs > 0L) {
            val deltaMs = (currentElapsed - elapsedRealtimeAnchorMs).coerceAtLeast(0L)
            val currentCalculated = serverAnchorMs + deltaMs

            // Advance high-water mark monotonically
            if (currentCalculated > highWaterServerTimeMs) {
                highWaterServerTimeMs = currentCalculated
                p?.edit()?.putLong(KEY_HIGH_WATER_TIME, highWaterServerTimeMs)?.apply()
            }

            return Pair(currentCalculated, true)
        }

        // If uncalibrated (e.g. fresh install or post-reboot before network fetch),
        // return the persistent high-water server timestamp.
        return Pair(highWaterServerTimeMs, false)
    }

    /**
     * Fetches authoritative server time from the Cloudflare Worker `/api/time` endpoint.
     */
    open suspend fun syncWithServer(): Result<Long> = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        Log.d("SUB_PERF", "[SUB_PERF] syncWithServer (/api/time) request started")
        try {
            val request = Request.Builder()
                .url("$workerBaseUrl/api/time")
                .header("X-Client-Timestamp", SystemClock.elapsedRealtime().toString())
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val tHttp = SystemClock.elapsedRealtime()
            Log.d("SUB_PERF", "[SUB_PERF] GET /api/time completed in ${tHttp - t0}ms, code=${response.code}")
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: "{}"
                val json = JSONObject(bodyStr)
                val serverTime = json.optLong("serverTime", 0L)
                val effectiveTime = if (serverTime > 0L) {
                    serverTime
                } else {
                    response.headers.getDate("Date")?.time ?: 0L
                }
                if (effectiveTime > 0L) {
                    recordServerTimeAnchor(effectiveTime)
                    Result.success(effectiveTime)
                } else {
                    Result.failure(Exception("Server returned invalid serverTime"))
                }
            } else {
                Result.failure(Exception("Server time request failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync subscription time with server", e)
            Result.failure(e)
        }
    }

    /**
     * Checks if the current subscription state has exceeded the maximum allowed offline lease.
     * 
     * @param lastServerSyncTimeMs Timestamp when the subscription document was last verified with server.
     * @return true if still within the authorized offline lease, false if revalidation is strictly required.
     */
    open fun isWithinOfflineLease(lastServerSyncTimeMs: Long): Boolean {
        if (lastServerSyncTimeMs <= 0L) return false
        val (currentTimeMs, isCalibrated) = getAuthoritativeSubscriptionTime()
        if (!isCalibrated && highWaterServerTimeMs == 0L) return false

        val elapsedSinceSync = (currentTimeMs - lastServerSyncTimeMs).coerceAtLeast(0L)
        return elapsedSinceSync <= MAX_OFFLINE_LEASE_MS
    }
}
