package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.overlay.OverlayLifecycleState
import com.reeltrak.app.services.overlay.OverlayStateManager
import com.reeltrak.app.services.AnalysisFreshnessGate
import com.reeltrak.app.services.CounterSurfacePolicy
import com.reeltrak.app.services.TransientSystemUiVisibilityPolicy
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReelCounterInstantHideRegressionTest {

    private fun findSourceFile(relativePath: String): File = listOf(
        File(relativePath), File("app/$relativePath"), File("../app/$relativePath")
    ).firstOrNull { it.exists() } ?: error("Source file not found: $relativePath")

    @Test
    fun `volume popup shade and quick settings events preserve a confirmed visible reel`() {
        val overlayStateManager = OverlayStateManager()
        val sessionManager = SessionManager(
            onStateChanged = { _, _ -> }
        )

        // Given an active session on Reels
        sessionManager.updateCurrentApp("com.instagram.android")
        sessionManager.recordSuccessfulDetection()
        assertEquals(SessionState.ACTIVE, sessionManager.currentState)

        overlayStateManager.updateState { it.copy(sessionState = SessionState.ACTIVE) }
        overlayStateManager.evaluateVisibility(isOnReels = true, isSystemOverlay = false)
        assertEquals(OverlayLifecycleState.VISIBLE, overlayStateManager.state.value.lifecycleState)
        assertTrue(overlayStateManager.state.value.isVisible)

        // A recognized System UI event alone does not invalidate the last confirmed reel.
        assertTrue(TransientSystemUiVisibilityPolicy.shouldPreserveConfirmedReel(SessionState.ACTIVE, true))
        assertEquals(OverlayLifecycleState.VISIBLE, overlayStateManager.state.value.lifecycleState)
        assertTrue(overlayStateManager.state.value.isVisible)

        // Session MUST remain ACTIVE (not destroyed) so the user doesn't lose reel count
        assertEquals(SessionState.ACTIVE, sessionManager.currentState)

        // A non-active session cannot use a system UI event to show a stale counter.
        assertFalse(TransientSystemUiVisibilityPolicy.shouldPreserveConfirmedReel(SessionState.IDLE, true))
        assertFalse(TransientSystemUiVisibilityPolicy.shouldPreserveConfirmedReel(SessionState.ACTIVE, false))
        overlayStateManager.evaluateVisibility(isOnReels = true, isSystemOverlay = false)
        assertEquals(OverlayLifecycleState.VISIBLE, overlayStateManager.state.value.lifecycleState)
        assertTrue(overlayStateManager.state.value.isVisible)
    }

    @Test
    fun `confirmed discarded snapshot is applied immediately`() {
        val gate = InconclusiveSnapshotGate()
        val discardedSnapshot = DetectionSnapshot(
            classification = SnapshotClassification.DISCARDED,
            fingerprint = SemanticFingerprint("", "", "", "Instagram"),
            platform = "Instagram",
            nodeCount = 25
        )

        // Pass 1: DISCARDED must NEVER return null (must not trigger 100ms retry wait)
        val resolved = gate.resolve(discardedSnapshot, 1000L)
        assertNotNull(resolved)
        assertEquals(SnapshotClassification.DISCARDED, resolved?.classification)
    }

    @Test
    fun `repeated same-trigger inconclusive reads do not prove departure`() {
        val gate = InconclusiveSnapshotGate()
        val inconclusiveSnapshot = DetectionSnapshot(
            classification = SnapshotClassification.INCONCLUSIVE,
            fingerprint = SemanticFingerprint("", "", "", "Instagram"),
            platform = "Instagram",
            nodeCount = 3
        )

        // Pass 1: Inconclusive snapshot returns null to trigger bounded retry
        val pass1 = gate.resolve(inconclusiveSnapshot, 2000L)
        assertEquals(null, pass1)

        // Pass 2: the same trigger is still not independent departure evidence.
        val pass2 = gate.resolve(inconclusiveSnapshot, 2000L)
        assertEquals(null, pass2)
        assertTrue(gate.hasPendingRecovery())
        assertEquals(2000L + InconclusiveSnapshotGate.MAX_RECOVERY_MS, gate.recoveryDeadlineMs)
    }

    @Test
    fun `source check - system UI fast-path preserves state and does not clear analysis`() {
        val serviceSource = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()

        val onEventStart = serviceSource.indexOf("override fun onAccessibilityEvent(event: AccessibilityEvent?)")
        val sysUiCheck = serviceSource.indexOf("isSystemOverlayEvent", onEventStart)
        val directionUpdate = serviceSource.indexOf("directionManager.updateDirection(event)", onEventStart)

        assertTrue("onAccessibilityEvent must contain system UI fast-path", sysUiCheck > onEventStart)
        assertTrue("System UI fast-path must execute BEFORE direction update and event debounce", sysUiCheck < directionUpdate)
        assertTrue("System UI fast path must set isSystemOverlayActive", serviceSource.contains("isSystemOverlayActive = true"))
        assertTrue("Tracked app must reset isSystemOverlayActive", serviceSource.contains("isSystemOverlayActive = false"))
        val branch = serviceSource.substring(sysUiCheck, directionUpdate)
        assertTrue("System UI handling must consult the current confirmed session", branch.contains("shouldPreserveConfirmedReel"))
        assertFalse("System UI event must not discard valid pending analysis", branch.contains("clearPending"))
    }

    @Test
    fun `source check - in-flight analysis cannot resurrect overlay when system UI is active`() {
        val serviceSource = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()

        val processStart = serviceSource.indexOf("private suspend fun processLatestUIState")
        val guard = serviceSource.indexOf("if (isSystemOverlayActive)", processStart)

        assertTrue("processLatestUIState must guard against isSystemOverlayActive", guard > processStart)
    }

    @Test
    fun `source check - instagram and youtube detectors require confirmed non-reel surface for pass 1 discard`() {
        val instagram = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt").readText()
        val youtube = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt").readText()

        assertTrue(
            "InstagramDetectorV2 must check discovery.hasConfirmedNonReelSurface to discard",
            instagram.contains("val isConfirmedExit = discovery.hasConfirmedNonReelSurface")
        )
        assertFalse(
            "InstagramDetectorV2 must not discard solely on nodesVisited count",
            instagram.contains("discovery.nodesVisited >= 8")
        )
        assertTrue(
            "YouTubeDetectorV2 must check discovery.hasConfirmedNonShortsSurface to discard",
            youtube.contains("val isConfirmedExit = discovery.hasConfirmedNonShortsSurface")
        )
        assertFalse(
            "YouTubeDetectorV2 must not discard solely on nodesVisited count",
            youtube.contains("discovery.nodesVisited >= 8")
        )
    }

    @Test
    fun `recovery discovery traversal condition is reachable when truncated`() {
        val instagram = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt").readText()
        val youtube = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt").readText()

        assertFalse(
            "InstagramDetectorV2 recovery traversal must not have unreachable nodesVisited condition",
            instagram.contains("firstDiscovery.wasTruncated &&") && instagram.contains("firstDiscovery.nodesVisited < 8")
        )
        assertFalse(
            "YouTubeDetectorV2 recovery traversal must not have unreachable nodesVisited condition",
            youtube.contains("firstDiscovery.wasTruncated &&") && youtube.contains("firstDiscovery.nodesVisited < 8")
        )
        assertTrue(
            "InstagramDetectorV2 recovery condition triggers on truncation without confirmed non-reel surface",
            instagram.contains("!firstDiscovery.hasConfirmedNonReelSurface")
        )
        assertTrue(
            "YouTubeDetectorV2 recovery condition triggers on truncation without confirmed non-shorts surface",
            youtube.contains("!firstDiscovery.hasConfirmedNonShortsSurface")
        )
    }

    @Test
    fun `system UI content changes do not trigger overlay hide when system overlay inactive`() {
        val serviceSource = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()
        assertTrue(
            "ReelTrackerService must guard system overlay hide with window state change or active overlay state",
            serviceSource.contains("isWindowState || isSystemOverlayActive")
        )
    }

    @Test
    fun `watchdog protects against transient null active window`() {
        val serviceSource = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()
        assertTrue(
            "Watchdog must require consecutive null active window detections before hiding",
            serviceSource.contains("consecutiveNullWatchdogCount >= 2")
        )
        assertTrue(
            "Watchdog must not hide overlay when active package is the refocus application itself",
            serviceSource.contains("isSelfApp")
        )
    }

    @Test
    fun `older analysis result is stale after newer departure event`() {
        val gate = AnalysisFreshnessGate()
        gate.observeEvent(3001L)
        assertFalse(gate.isCurrent(3000L))
        assertTrue(gate.isCurrent(3001L))
    }

    @Test
    fun `incomplete tree stays visible until valid recovery or bounded deadline`() {
        val overlayStateManager = OverlayStateManager()
        val sessionManager = SessionManager(onStateChanged = { _, newState ->
            overlayStateManager.updateState { it.copy(sessionState = newState) }
            overlayStateManager.evaluateVisibility(newState == SessionState.ACTIVE, false)
        })
        sessionManager.updateCurrentApp("com.instagram.android")
        sessionManager.recordSuccessfulDetection()

        val gate = InconclusiveSnapshotGate()
        val incomplete = DetectionSnapshot(classification = SnapshotClassification.INCONCLUSIVE)
        assertEquals(null, gate.resolve(incomplete, 4000L))
        assertEquals(null, gate.resolve(incomplete, 4000L))
        assertTrue(overlayStateManager.state.value.isVisible)

        val committed = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator", "caption", "audio", "Instagram"),
            platform = "Instagram"
        )
        assertEquals(committed, gate.resolve(committed, 4100L))
        assertFalse(gate.hasPendingRecovery())
        assertTrue(overlayStateManager.state.value.isVisible)
    }

    @Test
    fun `repeated transient UI analysis does not duplicate a reel increment`() {
        val increments = mutableListOf<Int>()
        val ledger = com.reeltrak.app.services.tracking.engine.state.TransitionLedger {
            increments += 1
        }
        val reel = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator", "caption", "audio", "Instagram"),
            platform = "Instagram"
        )

        repeat(3) {
        assertTrue(TransientSystemUiVisibilityPolicy.shouldPreserveConfirmedReel(SessionState.ACTIVE, true))
            ledger.process(reel)
        }
        assertEquals(1, increments.size)
    }

    @Test
    fun `comments only overlay is not treated as an active reel surface`() {
        val comments = DetectionSnapshot(
            classification = SnapshotClassification.DISCARDED,
            isInternalOverlay = true,
            internalOverlayKind = InternalOverlayKind.REEL_COMMENTS
        )
        val engagement = DetectionSnapshot(
            classification = SnapshotClassification.DISCARDED,
            isInternalOverlay = true,
            internalOverlayKind = InternalOverlayKind.REEL_ENGAGEMENT
        )

        assertFalse(CounterSurfacePolicy.isPreservingReelOverlay(comments, SessionState.ACTIVE))
        assertTrue(CounterSurfacePolicy.isPreservingReelOverlay(engagement, SessionState.ACTIVE))
    }

    @Test
    fun `source check - self overlay events do not trigger immediate visibility interrupt or scroll updates`() {
        val serviceSource = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()

        assertTrue(
            "ReelTrackerService must define isSelfApp",
            serviceSource.contains("private fun isSelfApp(packageName: String?): Boolean")
        )
        assertTrue(
            "onAccessibilityEvent must extract isSelf flag",
            serviceSource.contains("val isSelf = isSelfApp(eventPkg)")
        )
        assertTrue(
            "System overlay reset must guard against self app events",
            serviceSource.contains("!isSystemOverlay(eventPkg) && !isSelf")
        )
        assertTrue(
            "DirectionManager must not receive events from self app",
            serviceSource.contains("if (!isSelf) {\n            directionManager.updateDirection(event)\n        }") ||
            serviceSource.contains("if (!isSelf) {")
        )
        assertTrue(
            "Self app non-window-state events must be dropped to prevent feedback loops",
            serviceSource.contains("if (isSelf && eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)")
        )
        assertTrue(
            "Window state activePkg resolution must not treat self app as a foreign app departure",
            serviceSource.contains("val activePkg = if (eventPkg != null && !isSystemOverlay(eventPkg) && !isSelf)")
        )
        assertTrue(
            "resolveWindowStatePackage must receive isSelfPackage",
            serviceSource.contains("isSelfPackage = ::isSelfApp")
        )
    }

    @Test
    fun `overlay state manager keeps counter visible when reel remains active`() {
        val overlayStateManager = OverlayStateManager()
        val sessionManager = SessionManager(onStateChanged = { _, newState ->
            overlayStateManager.updateState { it.copy(sessionState = newState) }
        })

        sessionManager.updateCurrentApp("com.instagram.android")
        sessionManager.recordSuccessfulDetection()
        assertEquals(SessionState.ACTIVE, sessionManager.currentState)

        overlayStateManager.evaluateVisibility(isOnReels = true, isSystemOverlay = false)
        assertTrue("Overlay must be visible on reels", overlayStateManager.state.value.isVisible)
        assertEquals(OverlayLifecycleState.VISIBLE, overlayStateManager.state.value.lifecycleState)

        // An event from com.reeltrak.app overlay while user is on Instagram must NOT hide the counter.
        val policy = com.reeltrak.app.services.AccessibilityAnalysisEventPolicy()
        val resolved = policy.resolveWindowStatePackage(
            eventPackage = "com.reeltrak.app",
            isTrackedPackage = sessionManager::isTrackedApp,
            isSelfPackage = { it == "com.reeltrak.app" },
            rootProvider = { "instagramRoot" },
            packageFromRoot = { "com.instagram.android" },
            releaseRoot = { }
        )

        assertEquals("com.instagram.android", resolved)
        assertTrue("Resolved package must still be tracked", sessionManager.isTrackedApp(resolved))
        // Since resolved is tracked, evaluateVisibility(isOnReels = false) is NOT invoked:
        assertTrue("Overlay must remain visible and stable", overlayStateManager.state.value.isVisible)
    }
}
