package com.reeltrak.app.data.security

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val elapsedRealtimeProvider: () -> Long
    private val systemTimeProvider: () -> Long

    @Inject
    constructor(@ApplicationContext context: Context, httpClient: OkHttpClient) : this(
        context,
        httpClient,
        { try { SystemClock.elapsedRealtime() } catch (e: Exception) { 0L } },
        { System.currentTimeMillis() }
    )

    constructor() : this(
        null,
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build(),
        { try { SystemClock.elapsedRealtime() } catch (e: Exception) { 0L } },
        { System.currentTimeMillis() }
    )

    constructor(
        context: Context?,
        httpClient: OkHttpClient,
        elapsedRealtimeProvider: () -> Long,
        systemTimeProvider: () -> Long
    ) {
        this.context = context
        this.httpClient = httpClient
        this.elapsedRealtimeProvider = elapsedRealtimeProvider
        this.systemTimeProvider = systemTimeProvider
        loadPersistedState()
    }

    private val TAG = "SubTimeAuthority"
    private val PREFS_NAME = "subscription_security_time_prefs"

    private val KEY_SERVER_TIME_ANCHOR = "server_time_anchor"
    private val KEY_ELAPSED_REALTIME_ANCHOR = "elapsed_realtime_anchor"
    private val KEY_HIGH_WATER_TIME = "high_water_time"
    private val KEY_BOOT_SESSION_BASE_TIME = "boot_session_base_time"
    private val KEY_BOOT_SESSION_ELAPSED_BASELINE = "boot_session_elapsed_baseline"
    private val KEY_BOOT_COUNT = "boot_count"
    private val KEY_LAST_KNOWN_UPTIME = "last_known_uptime"
    private val KEY_LAST_SYSTEM_TIME = "last_system_time"
    private val KEY_OFFLINE_REBOOT_COUNT = "offline_reboot_count"

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val lock = Any()

    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL

    companion object {
        /**
         * Maximum allowed offline lease window: 24 Hours.
         * Rationale:
         * - Prevents indefinite offline premium theft while allowing users normal daily offline mobility.
         * - Beyond 24 hours of total disconnectivity or after unverified reboot threshold,
         *   unanchored entitlement fails closed to prevent stale privilege escalation.
         */
        const val MAX_OFFLINE_LEASE_MS = 24 * 60 * 60 * 1000L

        /**
         * Final-Sync Grace Window: Exactly 2 Minutes (120,000 ms).
         * Used ONLY for final daily data reconciliation/sync after expiration, NOT for premium access.
         */
        const val FINAL_SYNC_GRACE_WINDOW_MS = 2 * 60 * 1000L

        /**
         * Maximum allowable offline reboots without fresh server calibration.
         * Permits legitimate reboots (e.g. phone died and restarted on a flight),
         * but prevents indefinite offline lease exploitation via repeated reboot cycling.
         */
        const val MAX_OFFLINE_REBOOTS_WITHOUT_SYNC = 2

        /**
         * Maximum tolerated backward clock drift (1 minute).
         * Wall-clock rollback exceeding this threshold is treated as an active time tampering attack.
         */
        const val MAX_CLOCK_ROLLBACK_TOLERANCE_MS = 60_000L
    }

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated: StateFlow<Boolean> = _isCalibrated.asStateFlow()

    @Volatile
    private var serverAnchorMs: Long = 0L

    @Volatile
    private var elapsedRealtimeAnchorMs: Long = 0L

    @Volatile
    private var highWaterServerTimeMs: Long = 0L

    @Volatile
    private var bootSessionBaseTimestampMs: Long = 0L

    @Volatile
    private var bootSessionElapsedBaselineMs: Long = 0L

    /**
     * Hardware clock hooks to enable deterministic unit and security testing.
     */
    protected open fun getCurrentElapsedRealtime(): Long {
        return try { elapsedRealtimeProvider() } catch (e: Exception) { 0L }
    }

    protected open fun getCurrentSystemTime(): Long {
        return try { systemTimeProvider() } catch (e: Exception) { 0L }
    }

    @Synchronized
    private fun loadPersistedState() {
        val p = prefs ?: return
        synchronized(lock) {
            serverAnchorMs = p.getLong(KEY_SERVER_TIME_ANCHOR, 0L)
            elapsedRealtimeAnchorMs = p.getLong(KEY_ELAPSED_REALTIME_ANCHOR, 0L)
            highWaterServerTimeMs = p.getLong(KEY_HIGH_WATER_TIME, 0L)
            bootSessionBaseTimestampMs = p.getLong(KEY_BOOT_SESSION_BASE_TIME, 0L)
            bootSessionElapsedBaselineMs = p.getLong(KEY_BOOT_SESSION_ELAPSED_BASELINE, 0L)

            val currentElapsed = getCurrentElapsedRealtime()
            val lastKnownUptime = p.getLong(KEY_LAST_KNOWN_UPTIME, 0L)
            val currentSystemTime = getCurrentSystemTime()

            // Detect reboot using available reliable signals:
            // 1. Current elapsed uptime < recorded last known uptime
            // 2. Current elapsed uptime < active server elapsed anchor
            // 3. Current elapsed uptime < active boot session baseline
            val hasRebooted = (lastKnownUptime > 0L && currentElapsed < lastKnownUptime) ||
                              (elapsedRealtimeAnchorMs > 0L && currentElapsed < elapsedRealtimeAnchorMs) ||
                              (bootSessionElapsedBaselineMs > 0L && currentElapsed < bootSessionElapsedBaselineMs)

            if (hasRebooted) {
                _isCalibrated.value = false
                val currentReboots = p.getInt(KEY_OFFLINE_REBOOT_COUNT, 0) + 1

                // Invalidate server session anchor since boot context was lost
                serverAnchorMs = 0L
                elapsedRealtimeAnchorMs = 0L

                // Capture fixed high-water timestamp and fixed elapsed baseline for this boot session
                bootSessionBaseTimestampMs = highWaterServerTimeMs
                bootSessionElapsedBaselineMs = currentElapsed

                p.edit()
                    .putLong(KEY_SERVER_TIME_ANCHOR, 0L)
                    .putLong(KEY_ELAPSED_REALTIME_ANCHOR, 0L)
                    .putLong(KEY_BOOT_SESSION_BASE_TIME, bootSessionBaseTimestampMs)
                    .putLong(KEY_BOOT_SESSION_ELAPSED_BASELINE, bootSessionElapsedBaselineMs)
                    .putInt(KEY_OFFLINE_REBOOT_COUNT, currentReboots)
                    .putLong(KEY_LAST_KNOWN_UPTIME, currentElapsed)
                    .putLong(KEY_LAST_SYSTEM_TIME, currentSystemTime)
                    .apply()
                Log.w(TAG, "Device reboot detected (offline reboot #$currentReboots). Subscription time requires fresh server re-anchor.")
            } else if (serverAnchorMs > 0L && elapsedRealtimeAnchorMs > 0L) {
                _isCalibrated.value = true
                if (bootSessionBaseTimestampMs <= 0L) {
                    bootSessionBaseTimestampMs = serverAnchorMs
                    bootSessionElapsedBaselineMs = elapsedRealtimeAnchorMs
                }
                Log.d(TAG, "Subscription clock initialized from active session anchor (server: $serverAnchorMs, elapsed: $elapsedRealtimeAnchorMs)")
            } else if (bootSessionBaseTimestampMs > 0L) {
                _isCalibrated.value = false
                Log.d(TAG, "Resuming uncalibrated boot session with base: $bootSessionBaseTimestampMs, baseline: $bootSessionElapsedBaselineMs")
            } else {
                _isCalibrated.value = false
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

        synchronized(lock) {
            val currentElapsed = getCurrentElapsedRealtime()
            val currentSystemTime = getCurrentSystemTime()

            // Enforce monotonicity against stored high-water mark
            val effectiveServerTime = maxOf(serverTimestampMs, highWaterServerTimeMs)

            serverAnchorMs = effectiveServerTime
            elapsedRealtimeAnchorMs = currentElapsed
            highWaterServerTimeMs = effectiveServerTime
            bootSessionBaseTimestampMs = effectiveServerTime
            bootSessionElapsedBaselineMs = currentElapsed
            _isCalibrated.value = true

            prefs?.edit()
                ?.putLong(KEY_SERVER_TIME_ANCHOR, effectiveServerTime)
                ?.putLong(KEY_ELAPSED_REALTIME_ANCHOR, currentElapsed)
                ?.putLong(KEY_HIGH_WATER_TIME, effectiveServerTime)
                ?.putLong(KEY_BOOT_SESSION_BASE_TIME, effectiveServerTime)
                ?.putLong(KEY_BOOT_SESSION_ELAPSED_BASELINE, currentElapsed)
                ?.putLong(KEY_LAST_KNOWN_UPTIME, currentElapsed)
                ?.putLong(KEY_LAST_SYSTEM_TIME, currentSystemTime)
                ?.putInt(KEY_OFFLINE_REBOOT_COUNT, 0) // Reset offline reboot count upon authoritative server anchor
                ?.apply()

            Log.d(TAG, "Anchored subscription clock to server: $effectiveServerTime at elapsedRealtime: $currentElapsed")
        }
    }

    /**
     * Calculates the authoritative current subscription time in epoch milliseconds.
     * 
     * Formula (Calibrated & Uncalibrated Within Boot Session):
     * authoritativeTime = bootSessionBaseTimestampMs + (currentElapsed - bootSessionElapsedBaselineMs).coerceAtLeast(0L)
     *
     * Guaranteed Properties:
     * 1. Monotonic across the boot session (via kernel elapsedRealtime delta).
     * 2. Never adds cumulative uptime repeatedly to an already advanced timestamp.
     * 3. Completely immune to phone date/time rollback tampering.
     * 4. Advancing high-water bound across process restarts and reboots.
     * 5. If no trusted anchor exists, fails closed returning (0L, false).
     * 
     * Returns:
     * - Pair(authoritativeTimestampMs, isCalibrated)
     */
    @Synchronized
    open fun getAuthoritativeSubscriptionTime(): Pair<Long, Boolean> {
        synchronized(lock) {
            val currentElapsed = getCurrentElapsedRealtime()
            val currentSystemTime = getCurrentSystemTime()
            val p = prefs

            // If we have no persisted anchor or high-water time at all (e.g. fresh install / cleared data),
            // fail closed safely rather than inventing an ungrounded timestamp.
            if (bootSessionBaseTimestampMs <= 0L && highWaterServerTimeMs <= 0L) {
                return Pair(0L, false)
            }

            // Update last known uptime to detect subsequent reboots
            if (p != null && currentElapsed > p.getLong(KEY_LAST_KNOWN_UPTIME, 0L)) {
                p.edit().putLong(KEY_LAST_KNOWN_UPTIME, currentElapsed).apply()
            }

            // Within the same boot session, calculate authoritative time as the fixed base timestamp
            // plus the difference between current elapsed realtime and the fixed baseline.
            val baseTimestamp = if (bootSessionBaseTimestampMs > 0L) bootSessionBaseTimestampMs else highWaterServerTimeMs
            val baselineElapsed = bootSessionElapsedBaselineMs
            val deltaMs = (currentElapsed - baselineElapsed).coerceAtLeast(0L)
            val currentCalculated = baseTimestamp + deltaMs

            // Advance high-water mark monotonically if time progressed
            if (currentCalculated > highWaterServerTimeMs) {
                highWaterServerTimeMs = currentCalculated
                p?.edit()
                    ?.putLong(KEY_HIGH_WATER_TIME, highWaterServerTimeMs)
                    ?.putLong(KEY_LAST_SYSTEM_TIME, currentSystemTime)
                    ?.apply()
            }

            return Pair(currentCalculated, _isCalibrated.value)
        }
    }

    private val timeSyncMutex = Mutex()

    /**
     * Fetches authoritative server time from the Cloudflare Worker `/api/time` endpoint.
     * Enforces single-flight concurrency via [timeSyncMutex] and skips redundant network
     * roundtrips if the clock is already calibrated and [force] is false.
     */
    open suspend fun syncWithServer(force: Boolean = false): Result<Long> = withContext(Dispatchers.IO) {
        timeSyncMutex.withLock {
            if (!force && _isCalibrated.value) {
                val current = getAuthoritativeSubscriptionTime().first
                if (current > 0L) {
                    Log.d(TAG, "syncWithServer: Already calibrated; returning authoritative time ($current)")
                    return@withContext Result.success(current)
                }
            }

            val t0 = getCurrentElapsedRealtime()
            Log.d("SUB_PERF", "[SUB_PERF] syncWithServer (/api/time) request started")
            try {
                val request = Request.Builder()
                    .url("$workerBaseUrl/api/time")
                    .header("X-Client-Timestamp", getCurrentElapsedRealtime().toString())
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val tHttp = getCurrentElapsedRealtime()
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
    }

    /**
     * Checks if the current subscription state has exceeded the maximum allowed offline lease.
     * 
     * @param lastServerSyncTimeMs Timestamp when the subscription document was last verified with server.
     * @return true if still within the authorized offline lease, false if revalidation is strictly required.
     */
    open fun isWithinOfflineLease(lastServerSyncTimeMs: Long): Boolean {
        if (lastServerSyncTimeMs <= 0L) return false

        synchronized(lock) {
            val currentSystemTime = getCurrentSystemTime()

            // 1. Clock Rollback Detection:
            // If the system wall clock is behind highWaterServerTimeMs by more than the tolerance,
            // a backward clock manipulation has occurred. Fail closed immediately.
            if (highWaterServerTimeMs > 0L && currentSystemTime < (highWaterServerTimeMs - MAX_CLOCK_ROLLBACK_TOLERANCE_MS)) {
                Log.w(TAG, "Clock rollback detected: wall clock ($currentSystemTime) < high water ($highWaterServerTimeMs). Failing closed.")
                return false
            }

            val (currentTimeMs, isCalibrated) = getAuthoritativeSubscriptionTime()
            if (currentTimeMs <= 0L) return false

            // 2. Off-Time / Wall-Clock Advancement Detection (Power-off freezing prevention):
            // If the device was powered off across reboots, check if wall clock indicates
            // that more than MAX_OFFLINE_LEASE_MS has elapsed since the last app execution.
            val p = prefs
            val lastSystemTime = p?.getLong(KEY_LAST_SYSTEM_TIME, 0L) ?: 0L
            if (lastSystemTime > 0L && currentSystemTime > lastSystemTime) {
                val wallElapsedSinceLastRun = currentSystemTime - lastSystemTime
                if (wallElapsedSinceLastRun > MAX_OFFLINE_LEASE_MS) {
                    Log.w(TAG, "Device was offline/off longer than max lease ($wallElapsedSinceLastRun ms). Failing closed.")
                    return false
                }
            }

            // 3. Lease Duration Check:
            // Elapsed time since the last authoritative server sync must be <= MAX_OFFLINE_LEASE_MS (24h)
            val elapsedSinceSync = (currentTimeMs - lastServerSyncTimeMs).coerceAtLeast(0L)
            if (elapsedSinceSync > MAX_OFFLINE_LEASE_MS) {
                Log.w(TAG, "Offline lease expired: elapsedSinceSync ($elapsedSinceSync ms) > MAX ($MAX_OFFLINE_LEASE_MS ms).")
                return false
            }

            // 4. Uncalibrated Offline Reboot Boundary:
            // If the device has rebooted while offline, allow legitimate offline grace for up to
            // MAX_OFFLINE_REBOOTS_WITHOUT_SYNC reboots. If exceeded, fail closed until network re-anchor.
            if (!isCalibrated) {
                val rebootCount = p?.getInt(KEY_OFFLINE_REBOOT_COUNT, 0) ?: 0
                if (rebootCount > MAX_OFFLINE_REBOOTS_WITHOUT_SYNC) {
                    Log.w(TAG, "Excessive offline reboots ($rebootCount > $MAX_OFFLINE_REBOOTS_WITHOUT_SYNC) without server calibration. Failing closed.")
                    return false
                }
            }

            return true
        }
    }

    /**
     * Retrieves the current count of unverified offline reboots since the last server calibration.
     */
    open fun getOfflineRebootCount(): Int {
        return prefs?.getInt(KEY_OFFLINE_REBOOT_COUNT, 0) ?: 0
    }

    /**
     * Retrieves the persisted server sync timestamp for the given user ID.
     */
    open fun getLastServerSyncTime(uid: String): Long {
        if (uid.isBlank()) return 0L
        return prefs?.getLong("last_server_sync_$uid", 0L) ?: 0L
    }

    /**
     * Persists the authoritative server sync timestamp for the given user ID.
     */
    open fun recordLastServerSyncTime(uid: String, syncTimeMs: Long) {
        if (uid.isBlank() || syncTimeMs <= 0L) return
        prefs?.edit()?.putLong("last_server_sync_$uid", syncTimeMs)?.apply()
    }
}
