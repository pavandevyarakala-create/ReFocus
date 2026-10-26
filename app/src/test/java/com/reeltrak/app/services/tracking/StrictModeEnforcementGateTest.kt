package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StrictModeEnforcementGateTest {
    private fun reel() = DetectionSnapshot(
        classification = SnapshotClassification.COMMITTED,
        fingerprint = SemanticFingerprint("creator"),
        platform = "Instagram"
    )

    private fun overlay(kind: InternalOverlayKind) = DetectionSnapshot(
        classification = SnapshotClassification.DISCARDED,
        platform = "Instagram",
        isInternalOverlay = true,
        internalOverlayKind = kind
    )

    @Test
    fun limitCrossingEnforcesImmediatelyOnlyForAuthorizedEntitledUser() {
        assertTrue(StrictModeEnforcementPolicy.shouldEnforce(true, true, 10, 10, reel(), false))
        assertFalse(StrictModeEnforcementPolicy.shouldEnforce(true, true, 10, 9, reel(), false))
        assertFalse(StrictModeEnforcementPolicy.shouldEnforce(false, true, 10, 10, reel(), false))
        assertFalse(StrictModeEnforcementPolicy.shouldEnforce(true, false, 10, 10, reel(), false))
    }

    @Test
    fun rateLimitedRetriesOnSameSurfacePreventSpamWhileAllowingConsecutiveActions() {
        val gate = StrictModeEnforcementGate()
        val token = gate.tryBegin("reel:Instagram", nowMs = 0L)
        assertNotNull(token)
        assertNull("Concurrent attempts are blocked while in-flight", gate.tryBegin("reel:Instagram", nowMs = 0L))
        assertTrue(gate.performOnce("reel:Instagram", token!!, nowMs = 0L) { true }!!)
        gate.finish(token)

        // Rapid events arriving before the cooldown cannot trigger another action
        assertNull(gate.tryBegin("reel:Instagram", nowMs = 500L))
        assertNull(gate.tryBegin("reel:Instagram", nowMs = 999L))

        // Once the cooldown passes, a follow-up action is permitted
        val secondToken = gate.tryBegin("reel:Instagram", nowMs = 1_000L)
        assertNotNull(secondToken)
        assertTrue(gate.performOnce("reel:Instagram", secondToken!!, nowMs = 1_000L) { true }!!)
        gate.finish(secondToken)

        // Subsequent rapid events are still rate-limited
        assertNull(gate.tryBegin("reel:Instagram", nowMs = 1_500L))
        assertNull(gate.tryBegin("reel:Instagram", nowMs = 1_999L))

        // After another cooldown, another action is allowed (no permanent lockout)
        val thirdToken = gate.tryBegin("reel:Instagram", nowMs = 2_000L)
        assertNotNull(thirdToken)
        assertTrue(gate.performOnce("reel:Instagram", thirdToken!!, nowMs = 2_000L) { true }!!)
        gate.finish(thirdToken)
    }

    @Test
    fun rejectedBackEnforcesCooldownBeforeNextAttempt() {
        val gate = StrictModeEnforcementGate()
        val token = gate.tryBegin("reel:Instagram", nowMs = 0L)!!
        assertFalse(gate.performOnce("reel:Instagram", token, nowMs = 0L) { false }!!)
        gate.finish(token)

        // Event storms cannot retry during the cooldown
        repeat(20) { offset ->
            assertNull(gate.tryBegin("reel:Instagram", nowMs = offset * 40L))
        }

        // Fresh attempt allowed after cooldown
        val retryToken = gate.tryBegin(
            "reel:Instagram",
            nowMs = StrictModeEnforcementGate.SAME_SURFACE_RECHECK_DELAY_MS
        )
        assertNotNull(retryToken)
        assertTrue(gate.performOnce("reel:Instagram", retryToken!!, nowMs = 1_000L) { true }!!)
        gate.finish(retryToken)
    }

    @Test
    fun deepBackstackCanUnwindConsecutiveReelsRateLimitedWithoutLockout() {
        val gate = StrictModeEnforcementGate()
        // Simulate user 5 reels deep in an Explore or Hashtag backstack
        var nowMs = 0L
        for (i in 1..5) {
            val token = gate.tryBegin("reel:Instagram", nowMs)
            assertNotNull("Reel $i in backstack must be able to begin enforcement", token)
            var actionExecuted = false
            val result = gate.performOnce("reel:Instagram", token!!, nowMs) {
                actionExecuted = true
                true
            }
            assertTrue("Action for reel $i must succeed", result == true)
            assertTrue("Action lambda must have executed for reel $i", actionExecuted)
            gate.finish(token)

            // Rapid events during back animation are blocked
            assertNull(gate.tryBegin("reel:Instagram", nowMs + 200L))
            assertNull(gate.tryBegin("reel:Instagram", nowMs + 800L))

            nowMs += 1_000L
        }
    }

    @Test
    fun nestedOverlaysAllowImmediateActionWhenSurfaceChanges() {
        val gate = StrictModeEnforcementGate()
        val commentKey = StrictModeEnforcementPolicy.surfaceKey(overlay(InternalOverlayKind.REEL_COMMENTS))!!
        assertTrue(commentKey.startsWith("comments:"))

        // 1. Dismiss comments sheet at t = 0ms
        val commentToken = gate.tryBegin(commentKey, nowMs = 0L)!!
        assertTrue(gate.performOnce(commentKey, commentToken, nowMs = 0L) { true }!!)
        gate.finish(commentToken)

        // Same surface is on cooldown
        assertNull(gate.tryBegin(commentKey, nowMs = 100L))

        // 2. Comments sheet closed, now active surface is the underlying Reel at t = 100ms
        // Because the surface changed from comments: to reel:, it should NOT be locked out by comments cooldown
        val reelKey = StrictModeEnforcementPolicy.surfaceKey(reel())!!
        val reelToken = gate.tryBegin(reelKey, nowMs = 100L)
        assertNotNull("Reel surface must be actionable immediately after comments sheet dismiss", reelToken)
        assertTrue(gate.performOnce(reelKey, reelToken!!, nowMs = 100L) { true }!!)
        gate.finish(reelToken)

        // Now reel surface is on cooldown until 100 + 1000 = 1100ms
        assertNull(gate.tryBegin(reelKey, nowMs = 500L))
        assertNull(gate.tryBegin(reelKey, nowMs = 1_099L))
        assertNotNull(gate.tryBegin(reelKey, nowMs = 1_100L))
    }

    @Test
    fun configurableCooldownConstructorParameterRespected() {
        val customCooldownMs = 400L
        val gate = StrictModeEnforcementGate(sameSurfaceCooldownMs = customCooldownMs)
        assertEquals(customCooldownMs, gate.sameSurfaceCooldownMs)

        val token = gate.tryBegin("reel:Instagram", nowMs = 0L)!!
        assertTrue(gate.performOnce("reel:Instagram", token, nowMs = 0L) { true }!!)
        gate.finish(token)

        assertNull(gate.tryBegin("reel:Instagram", nowMs = 399L))
        val nextToken = gate.tryBegin("reel:Instagram", nowMs = 400L)
        assertNotNull(nextToken)
    }

    @Test
    fun genericDialogsAndUnrelatedOverlaysAreNeverEnforcementTargets() {
        val generic = overlay(InternalOverlayKind.GENERIC)
        assertNull(StrictModeEnforcementPolicy.surfaceKey(generic))
        assertFalse(StrictModeEnforcementPolicy.shouldEnforce(true, true, 10, 10, generic, true))
        assertFalse(
            StrictModeEnforcementPolicy.shouldEnforce(
                true,
                true,
                10,
                10,
                overlay(InternalOverlayKind.REEL_COMMENTS),
                false
            )
        )
        assertTrue(
            StrictModeEnforcementPolicy.shouldEnforce(
                true,
                true,
                10,
                10,
                overlay(InternalOverlayKind.REEL_COMMENTS),
                true
            )
        )
    }

    @Test
    fun confirmedDepartureRearmsAndInvalidatesAnyStaleActionToken() {
        val gate = StrictModeEnforcementGate()
        val oldToken = gate.tryBegin("reel:Instagram", 0L)!!
        gate.observe(null)

        val newToken = gate.tryBegin("reel:Instagram", 0L)
        assertNotNull(newToken)
        assertNull(gate.performOnce("reel:Instagram", oldToken, nowMs = 0L) { true })
        assertTrue(gate.performOnce("reel:Instagram", newToken!!, nowMs = 0L) { true }!!)
    }

    @Test
    fun departureResetsCooldownAndAllowsImmediateReEntryEnforcement() {
        val gate = StrictModeEnforcementGate()
        val token = gate.tryBegin("reel:Instagram", nowMs = 0L)!!
        assertTrue(gate.performOnce("reel:Instagram", token, nowMs = 0L) { true }!!)
        gate.finish(token)

        // Without departure, rapid re-entry within 200ms would be blocked
        assertNull(gate.tryBegin("reel:Instagram", nowMs = 200L))

        // User departed to Feed at 250ms
        gate.observe(null)

        // User immediately re-opens Reels at 300ms -> should enforce immediately!
        val reEntryToken = gate.tryBegin("reel:Instagram", nowMs = 300L)
        assertNotNull("Departure must allow immediate re-entry enforcement", reEntryToken)
        assertTrue(gate.performOnce("reel:Instagram", reEntryToken!!, nowMs = 300L) { true }!!)
    }

    @Test
    fun lifecycleResetRearmsEnforcement() {
        val gate = StrictModeEnforcementGate()
        val token = gate.tryBegin("reel:Instagram", 0L)!!
        assertTrue(gate.performOnce("reel:Instagram", token, nowMs = 0L) { true }!!)
        gate.reset()
        assertNotNull(gate.tryBegin("reel:Instagram", 0L))
    }

    @Test
    fun bothDetectionPipelinesEvaluateStrictModeAfterLedgerProcessing() {
        val servicePath = "src/main/java/com/reeltrak/app/services/ReelTrackerService.kt"
        val serviceFile = listOf(File(servicePath), File("app/$servicePath"), File("../app/$servicePath"))
            .firstOrNull { it.exists() } ?: throw IllegalStateException("ReelTrackerService source not found")
        val service = serviceFile.readText()
        val eventLedger = service.indexOf("transitionLedger.process(snapshot)")
        val eventEvaluation = service.indexOf("evaluateStrictModeForSnapshot(snapshot, activePackage)", eventLedger)
        assertTrue("Unified event pipeline must evaluate after the ledger", eventLedger >= 0 && eventEvaluation > eventLedger)

        val syncStart = service.indexOf("private fun scheduleOverlaySynchronization()")
        val syncBody = if (syncStart >= 0) service.substring(syncStart) else ""
        assertTrue(
            "scheduleOverlaySynchronization must exist and route to queue",
            syncStart >= 0 && syncBody.contains("processingQueue?.request(TrackingAnalysisRequest(now")
        )
    }
}
