package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind

/** Serializes enforcement and allows rate-limited retries while remaining on a restricted surface. */
internal class StrictModeEnforcementGate(
    val sameSurfaceCooldownMs: Long = DEFAULT_SAME_SURFACE_COOLDOWN_MS
) {
    companion object {
        const val DEFAULT_SAME_SURFACE_COOLDOWN_MS = 1_000L
        const val SAME_SURFACE_RECHECK_DELAY_MS = DEFAULT_SAME_SURFACE_COOLDOWN_MS
    }

    private val lock = Any()
    private var inFlight = false
    private var lastActedSurface: String? = null
    private var nextAllowedSameSurfaceActionTimeMs = 0L
    private var generation = 0L

    fun tryBegin(surface: String, nowMs: Long): Long? = synchronized(lock) {
        if (inFlight) return@synchronized null
        if (lastActedSurface == surface && nowMs < nextAllowedSameSurfaceActionTimeMs) {
            return@synchronized null
        }
        inFlight = true
        generation += 1
        generation
    }

    fun performOnce(surface: String, token: Long, nowMs: Long, action: () -> Boolean): Boolean? = synchronized(lock) {
        if (!inFlight || generation != token) return@synchronized null
        if (lastActedSurface == surface && nowMs < nextAllowedSameSurfaceActionTimeMs) return@synchronized null

        lastActedSurface = surface
        nextAllowedSameSurfaceActionTimeMs = nowMs + sameSurfaceCooldownMs
        try {
            action()
        } catch (failure: Exception) {
            throw failure
        }
    }

    fun finish(token: Long) = synchronized(lock) {
        if (generation == token) inFlight = false
    }

    /** A null surface is a fresh detection that confirms departure from the restricted UI. */
    fun observe(surface: String?) = synchronized(lock) {
        if (surface == null) {
            generation += 1
            inFlight = false
            lastActedSurface = null
            nextAllowedSameSurfaceActionTimeMs = 0L
        }
    }

    fun reset() = synchronized(lock) {
        generation += 1
        inFlight = false
        lastActedSurface = null
        nextAllowedSameSurfaceActionTimeMs = 0L
    }
}

internal object StrictModeEnforcementPolicy {
    fun surfaceKey(snapshot: DetectionSnapshot): String? = when {
        snapshot.isCommitted || snapshot.isPending -> "reel:${snapshot.platform}"
        snapshot.isInternalOverlay && snapshot.internalOverlayKind == InternalOverlayKind.REEL_COMMENTS ->
            "comments:${snapshot.platform}"
        snapshot.isInternalOverlay && snapshot.internalOverlayKind == InternalOverlayKind.REEL_ENGAGEMENT ->
            "engagement:${snapshot.platform}"
        else -> null
    }

    fun shouldEnforce(
        isEntitled: Boolean,
        isStrictModeActive: Boolean,
        dailyGoal: Int,
        count: Int,
        snapshot: DetectionSnapshot,
        isReelSessionActive: Boolean
    ): Boolean {
        val isReelSurface = snapshot.isCommitted || snapshot.isPending ||
            (isReelSessionActive && surfaceKey(snapshot) != null)
        return isEntitled && isStrictModeActive && dailyGoal > 0 && count >= dailyGoal && isReelSurface
    }
}
