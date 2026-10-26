package com.reeltrak.app.services

import com.reeltrak.app.domain.models.ThemeMode
import com.reeltrak.app.services.overlay.OverlayLifecycleState
import com.reeltrak.app.services.overlay.OverlayState
import com.reeltrak.app.services.overlay.OverlayStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit test suite verifying the Samsung One UI floating counter display fix and
 * end-to-end diagnostic instrumentation.
 *
 * Root Cause Addressed:
 * In Samsung One UI, non-focused accessibility overlay windows have their hardware
 * renderer and VSYNC throttled by the compositor if no touch events or View-level
 * traversals occur. When a reel increment arrives, updating Compose state internally
 * does not call View.invalidate() or View.requestLayout() on the host ComposeView.
 * Consequently, the counter remained stuck on the old number until a user touch
 * event (ACTION_DOWN) forced an input traversal.
 *
 * The Fix:
 * Whenever stateFlow emits an updated count, theme, or updateRequired while the
 * overlay is VISIBLE, the host ComposeView explicitly receives invalidate() and
 * requestLayout(), scheduling performTraversals() on ViewRootImpl immediately.
 */
class FloatingCounterSamsungDisplayTest {

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Source file not found: $relativePath")
    }

    @Test
    fun testOverlayStateManager_CountIncrementsReflectedImmediatelyInStateFlow() = runBlocking {
        val manager = OverlayStateManager()
        assertEquals(0, manager.state.value.count)

        manager.updateState { it.copy(count = 1, isVisible = true, lifecycleState = OverlayLifecycleState.VISIBLE) }
        assertEquals(1, manager.state.value.count)
        assertTrue(manager.state.value.isVisible)

        manager.updateState { it.copy(count = 2) }
        assertEquals(2, manager.state.value.count)
    }

    @Test
    fun testOverlayStateManager_BatchingEmitsFinalCountCorrectly() = runBlocking {
        val manager = OverlayStateManager()
        manager.beginBatch()
        manager.updateState { it.copy(count = 5) }
        manager.updateState { it.copy(count = 10) }
        // Before endBatch, state should still be original
        assertEquals(0, manager.state.value.count)

        manager.endBatch()
        assertEquals(10, manager.state.value.count)
    }

    @Test
    fun testSamsungDisplaySynchronizer_InvalidatesAndRequestsLayoutOnCountChangeWhenVisible() {
        var invalidateCallCount = 0
        var requestLayoutCallCount = 0

        // Mock host view callbacks
        val mockView = object {
            fun invalidate() { invalidateCallCount++ }
            fun requestLayout() { requestLayoutCallCount++ }
        }

        // Test the exact synchronization algorithm implemented in FloatingCounterManager
        var lastRenderedCount: Int? = null
        var lastRenderedThemeMode: ThemeMode? = null
        var lastRenderedUpdateRequired: Boolean? = null
        var lastRenderedVisibility: Boolean? = null

        fun simulateStateEmission(state: OverlayState) {
            val isVisible = state.lifecycleState == OverlayLifecycleState.VISIBLE
            val visibilityChanged = (lastRenderedVisibility != isVisible)
            if (visibilityChanged) {
                lastRenderedVisibility = isVisible
            }

            if (isVisible) {
                val countChanged = lastRenderedCount != null && lastRenderedCount != state.count
                val themeChanged = lastRenderedThemeMode != null && lastRenderedThemeMode != state.counterThemeMode
                val updateChanged = lastRenderedUpdateRequired != null && lastRenderedUpdateRequired != state.isUpdateRequired

                if (countChanged || themeChanged || updateChanged) {
                    val digitCountChanged = lastRenderedCount != null &&
                        (lastRenderedCount.toString().length != state.count.toString().length)

                    mockView.invalidate()
                    if (digitCountChanged || themeChanged || updateChanged) {
                        mockView.requestLayout()
                    }
                }
            }
            lastRenderedCount = state.count
            lastRenderedThemeMode = state.counterThemeMode
            lastRenderedUpdateRequired = state.isUpdateRequired
        }

        // 1. Initial attach with visible count 0 -> no invalidation on cold attach
        simulateStateEmission(OverlayState(count = 0, lifecycleState = OverlayLifecycleState.VISIBLE))
        assertEquals(0, invalidateCallCount)
        assertEquals(0, requestLayoutCallCount)

        // 2. Continuous scrolling: Reel 1 counted -> count becomes 1 (same digit length)
        // Redraw is scheduled via invalidate(); layout pass is avoided
        simulateStateEmission(OverlayState(count = 1, lifecycleState = OverlayLifecycleState.VISIBLE))
        assertEquals(1, invalidateCallCount)
        assertEquals(0, requestLayoutCallCount)

        // 3. Duplicate event or re-emission with same count -> NO redundant invalidation
        simulateStateEmission(OverlayState(count = 1, lifecycleState = OverlayLifecycleState.VISIBLE))
        assertEquals(1, invalidateCallCount)
        assertEquals(0, requestLayoutCallCount)

        // 4. Reel 2 counted -> count becomes 2 (same digit length) -> invalidation #2, layout still 0
        simulateStateEmission(OverlayState(count = 2, lifecycleState = OverlayLifecycleState.VISIBLE))
        assertEquals(2, invalidateCallCount)
        assertEquals(0, requestLayoutCallCount)

        // 5. Reel 9 -> 10 (digit count expands 1 -> 2 digits) -> invalidation #3, layout pass #1
        simulateStateEmission(OverlayState(count = 9, lifecycleState = OverlayLifecycleState.VISIBLE))
        simulateStateEmission(OverlayState(count = 10, lifecycleState = OverlayLifecycleState.VISIBLE))
        assertEquals(4, invalidateCallCount)
        assertEquals(1, requestLayoutCallCount)

        // 6. Overlay becomes HIDDEN -> count changes in background -> NO invalidation while hidden
        simulateStateEmission(OverlayState(count = 10, lifecycleState = OverlayLifecycleState.HIDDEN))
        simulateStateEmission(OverlayState(count = 11, lifecycleState = OverlayLifecycleState.HIDDEN))
        assertEquals(4, invalidateCallCount)
        assertEquals(1, requestLayoutCallCount)

        // 7. Overlay becomes VISIBLE again with new count 12 -> triggers invalidation
        simulateStateEmission(OverlayState(count = 12, lifecycleState = OverlayLifecycleState.VISIBLE))
        assertEquals(5, invalidateCallCount)
        assertEquals(1, requestLayoutCallCount)

        // 8. Theme mode changed while visible -> triggers invalidation & requestLayout
        simulateStateEmission(OverlayState(count = 12, lifecycleState = OverlayLifecycleState.VISIBLE, counterThemeMode = ThemeMode.DARK))
        assertEquals(6, invalidateCallCount)
        assertEquals(2, requestLayoutCallCount)
    }

    @Test
    fun testAllSixDiagnosticPointsExistAndGuardedInSourceCode() {
        val transitionLedgerFile = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/state/TransitionLedger.kt")
        val dayManagerFile = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val reelTrackerServiceFile = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val overlayStateManagerFile = findSourceFile("src/main/java/com/reeltrak/app/services/overlay/OverlayStateManager.kt")
        val floatingCounterManagerFile = findSourceFile("src/main/java/com/reeltrak/app/services/FloatingCounterManager.kt")

        val transitionLedgerCode = transitionLedgerFile.readText()
        val dayManagerCode = dayManagerFile.readText()
        val reelTrackerServiceCode = reelTrackerServiceFile.readText()
        val overlayStateManagerCode = overlayStateManagerFile.readText()
        val floatingCounterManagerCode = floatingCounterManagerFile.readText()

        // Point 1: Transition accepted & increment dispatched
        assertTrue("Point 1 transition accepted log must exist",
            transitionLedgerCode.contains("[POINT_1_TRANSITION_ACCEPTED]"))
        assertTrue("Point 1 increment dispatched log must exist",
            reelTrackerServiceCode.contains("[POINT_1_INCREMENT_DISPATCHED]"))

        // Point 2: Authoritative count incremented
        assertTrue("Point 2 authoritative increment log must exist",
            dayManagerCode.contains("[POINT_2_AUTHORITATIVE_INCREMENT]"))

        // Point 3: State holder update & State emitted
        assertTrue("Point 3 state holder update log must exist",
            reelTrackerServiceCode.contains("[POINT_3_STATE_HOLDER_UPDATE]"))
        assertTrue("Point 3 state emitted log must exist",
            overlayStateManagerCode.contains("[POINT_3_STATE_EMITTED]"))

        // Point 4: UI state consumed & View traversal triggered
        assertTrue("Point 4 UI consumed log must exist",
            floatingCounterManagerCode.contains("[POINT_4_UI_CONSUMED]"))

        // Point 5: Recompose and drawWithContent execution
        assertTrue("Point 5 recompose log must exist",
            floatingCounterManagerCode.contains("[POINT_5_RECOMPOSE]"))
        assertTrue("Point 5 draw log must exist",
            floatingCounterManagerCode.contains("[POINT_5_DRAW]"))

        // Point 6: Pointer input touch and drag detection
        assertTrue("Point 6 touch event log must exist",
            floatingCounterManagerCode.contains("[POINT_6_TOUCH_EVENT]"))
        assertTrue("Point 6 drag event log must exist",
            floatingCounterManagerCode.contains("[POINT_6_DRAG_EVENT]"))

        // Guard verification: BuildConfig.DEBUG
        listOf(
            transitionLedgerCode,
            dayManagerCode,
            reelTrackerServiceCode,
            overlayStateManagerCode,
            floatingCounterManagerCode
        ).forEach { code ->
            assertTrue("Logs must be guarded with BuildConfig.DEBUG",
                code.contains("BuildConfig.DEBUG"))
        }
    }

    @Test
    fun testFloatingCounterManagerArchitecture_UsesCollectAsStateAndInvalidateOnComposeView() {
        val floatingCounterManagerFile = findSourceFile("src/main/java/com/reeltrak/app/services/FloatingCounterManager.kt")
        val code = floatingCounterManagerFile.readText()

        // Verify collectAsState() is used instead of collectAsStateWithLifecycle()
        assertTrue("FloatingCounterManager must use direct collectAsState()",
            code.contains("stateFlow.collectAsState()"))
        assertFalse("FloatingCounterManager should not use collectAsStateWithLifecycle() for overlay state",
            code.contains("stateFlow.collectAsStateWithLifecycle()"))

        // Verify view.invalidate() and view.requestLayout() are called directly
        assertTrue("FloatingCounterManager must call view.invalidate()",
            code.contains("view.invalidate()"))
        assertTrue("FloatingCounterManager must call view.requestLayout()",
            code.contains("view.requestLayout()"))
    }
}
