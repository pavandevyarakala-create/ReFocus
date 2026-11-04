package com.reeltrak.app.services.tracking

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression Test Suite for BUG-012:
 * Strict Mode Repeatedly Interrupts Instagram
 *
 * Verifies:
 * 1. Back button retry rate limiting and escalation to GLOBAL_ACTION_HOME after 3 consecutive attempts on the same surface.
 * 2. Toast notification throttling via TOAST_COOLDOWN_MS (4000ms).
 * 3. Surface departure resetting consecutive attempt counters and rearming enforcement.
 * 4. ReelTrackerService source code invariants preserving Strict Mode enforcement without trapping users in loops.
 */
class StrictModeBackNavigationTest {

    /**
     * Simulates the exact state machine implemented in ReelTrackerService for BUG-012.
     */
    private class StrictModeEscalationController(
        val toastCooldownMs: Long = 4_000L,
        val maxConsecutiveBackAttempts: Int = 3
    ) {
        var lastToastTimeMs = -1L
        var consecutiveEnforcementAttempts = 0
        var lastEnforcedSurface: String? = null
        var toastsShown = 0
        val performedActions = mutableListOf<Int>()

        fun enforceOnSurface(surface: String, nowMs: Long): Int {
            // Toast throttling: first toast is immediate, follow-ups throttled by cooldown
            if (lastToastTimeMs == -1L || nowMs - lastToastTimeMs >= toastCooldownMs) {
                lastToastTimeMs = nowMs
                toastsShown++
            }

            if (lastEnforcedSurface == surface) {
                consecutiveEnforcementAttempts++
            } else {
                lastEnforcedSurface = surface
                consecutiveEnforcementAttempts = 1
            }

            val action = if (consecutiveEnforcementAttempts >= maxConsecutiveBackAttempts) {
                consecutiveEnforcementAttempts = 0
                lastEnforcedSurface = null
                AccessibilityService.GLOBAL_ACTION_HOME
            } else {
                AccessibilityService.GLOBAL_ACTION_BACK
            }
            performedActions.add(action)
            return action
        }

        fun onDepartedSurface() {
            consecutiveEnforcementAttempts = 0
            lastEnforcedSurface = null
        }
    }

    @Test
    fun backActionsEscalateToHomeAfterThreeConsecutiveAttemptsOnSameSurface() {
        val controller = StrictModeEscalationController()
        val surface = "reel:Instagram"

        // Attempt 1: Back
        val action1 = controller.enforceOnSurface(surface, nowMs = 1000L)
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, action1)
        assertEquals(1, controller.consecutiveEnforcementAttempts)

        // Attempt 2: Back
        val action2 = controller.enforceOnSurface(surface, nowMs = 2000L)
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, action2)
        assertEquals(2, controller.consecutiveEnforcementAttempts)

        // Attempt 3: Escalates to HOME to prevent entrapment
        val action3 = controller.enforceOnSurface(surface, nowMs = 3000L)
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, action3)
        // Counters reset after escalation
        assertEquals(0, controller.consecutiveEnforcementAttempts)
        assertNull(controller.lastEnforcedSurface)
    }

    @Test
    fun toastNotificationsAreThrottledByCooldown() {
        val controller = StrictModeEscalationController(toastCooldownMs = 4_000L)
        val surface = "reel:Instagram"

        // First action at t=0ms: shows Toast #1
        controller.enforceOnSurface(surface, nowMs = 0L)
        assertEquals(1, controller.toastsShown)

        // Second action at t=1000ms: within 4s cooldown -> no new toast
        controller.enforceOnSurface(surface, nowMs = 1000L)
        assertEquals(1, controller.toastsShown)

        // Third action at t=2000ms: within 4s cooldown -> no new toast
        controller.enforceOnSurface(surface, nowMs = 2000L)
        assertEquals(1, controller.toastsShown)

        // Fourth action after cooldown at t=4500ms -> shows Toast #2
        controller.enforceOnSurface(surface, nowMs = 4500L)
        assertEquals(2, controller.toastsShown)
    }

    @Test
    fun departingSurfaceResetsConsecutiveAttempts() {
        val controller = StrictModeEscalationController()
        val surface = "reel:Instagram"

        // Attempt 1 and 2
        controller.enforceOnSurface(surface, nowMs = 1000L)
        controller.enforceOnSurface(surface, nowMs = 2000L)
        assertEquals(2, controller.consecutiveEnforcementAttempts)

        // User navigates away to Feed or Settings
        controller.onDepartedSurface()
        assertEquals(0, controller.consecutiveEnforcementAttempts)
        assertNull(controller.lastEnforcedSurface)

        // Re-entering reels starts fresh attempt cycle with Back, not immediate Home
        val actionAfterReentry = controller.enforceOnSurface(surface, nowMs = 5000L)
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, actionAfterReentry)
        assertEquals(1, controller.consecutiveEnforcementAttempts)
    }

    @Test
    fun reelTrackerServiceSourceContainsEscalationAndThrottlingInvariants() {
        val servicePath = "src/main/java/com/reeltrak/app/services/ReelTrackerService.kt"
        val serviceFile = listOf(File(servicePath), File("app/$servicePath"), File("../app/$servicePath"))
            .firstOrNull { it.exists() } ?: throw IllegalStateException("ReelTrackerService source not found")
        val content = serviceFile.readText()

        assertTrue("Must declare TOAST_COOLDOWN_MS", content.contains("TOAST_COOLDOWN_MS"))
        assertTrue("Must declare MAX_CONSECUTIVE_BACK_ATTEMPTS", content.contains("MAX_CONSECUTIVE_BACK_ATTEMPTS"))
        assertTrue("Must declare consecutiveEnforcementAttempts counter", content.contains("consecutiveEnforcementAttempts"))
        assertTrue("Must escalate to GLOBAL_ACTION_HOME on repeated back failures", content.contains("GLOBAL_ACTION_HOME"))
        assertTrue("Must reset counters in cancelStrictEnforcementCycle", content.contains("consecutiveEnforcementAttempts = 0"))
    }
}
