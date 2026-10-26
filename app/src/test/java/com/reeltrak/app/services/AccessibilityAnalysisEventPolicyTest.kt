package com.reeltrak.app.services

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

class AccessibilityAnalysisEventPolicyTest {
    private fun findSourceFile(relativePath: String): File = listOf(
        File(relativePath), File("app/$relativePath"), File("../app/$relativePath")
    ).firstOrNull { it.exists() } ?: error("Source file not found: $relativePath")

    @Test
    fun `view focus is a relevant trigger on its own`() {
        val policy = AccessibilityAnalysisEventPolicy()

        assertTrue(policy.isRelevant(AccessibilityEvent.TYPE_VIEW_FOCUSED))
        assertFalse(policy.shouldDeferFocusEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED, 100L))
    }

    @Test
    fun `focus event during active analysis is queued for a serialized follow-up pass`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val policy = AccessibilityAnalysisEventPolicy()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val analyzed = Collections.synchronizedList(mutableListOf<Long>())
        val queue = LatestStateAnalysisQueue<Long>(scope = scope) { eventTime ->
            analyzed += eventTime
            if (eventTime == 100L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                secondFinished.complete(Unit)
            }
            true
        }

        try {
            queue.start()
            assertTrue(policy.isRelevant(AccessibilityEvent.TYPE_VIEW_FOCUSED))
            assertFalse(policy.shouldDeferFocusEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED, 100L))
            queue.request(100L)
            firstStarted.await()

            assertFalse(policy.shouldDeferFocusEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED, 160L))
            queue.request(160L)
            releaseFirst.complete(Unit)
            withTimeout(2_000L) { secondFinished.await() }
            assertEquals(listOf(100L, 160L), analyzed)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `focus event inside debounce window is deferred to trailing synchronization`() {
        val policy = AccessibilityAnalysisEventPolicy(debounceMs = 50L)

        assertFalse(policy.shouldDeferFocusEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED, 1_000L))
        assertTrue(policy.shouldDeferFocusEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED, 1_020L))
        assertFalse(policy.shouldDeferFocusEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 1_030L))

        val service = serviceSource()
        assertTrue(service.contains("if (analysisEventPolicy.shouldDeferFocusEvent(eventType, now))"))
        assertTrue(service.contains("scheduleOverlaySynchronization()"))
        assertTrue(service.contains("delay(EVENT_DEBOUNCE_MS + 20L)"))
    }

    @Test
    fun `opening or returning to a feed uses window or focus triggers and the existing queue`() {
        val policy = AccessibilityAnalysisEventPolicy()
        val windowState = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val content = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val focused = AccessibilityEvent.TYPE_VIEW_FOCUSED

        assertTrue(policy.isRelevant(windowState))
        assertTrue(policy.isRelevant(content))
        assertTrue(policy.isRelevant(focused))
        val service = serviceSource()
        val connected = service.substring(
            service.indexOf("override fun onServiceConnected()"),
            service.indexOf("override fun onAccessibilityEvent(event: AccessibilityEvent?)")
        )
        assertTrue(connected.contains("queue.request(TrackingAnalysisRequest("))
        assertTrue(connected.contains("analyze = ::processLatestUIState"))
    }

    @Test
    fun `leaving a feed with a discarded snapshot hides the overlay`() {
        val service = serviceSource()
        val processStart = service.indexOf("private suspend fun processLatestUIState")
        val processEnd = service.indexOf("private fun strictModeAuthorizedNow", processStart)
        val process = service.substring(processStart, processEnd)

        assertTrue(process.contains("else if (snapshot.isDiscarded)"))
        assertTrue(process.contains("sessionManager.transitionTo(SessionState.IDLE)"))
        assertTrue(process.contains("overlayStateManager.evaluateVisibility("))
        assertTrue(process.contains("isOnReels = isActiveScreen && isScreenReady"))
    }

    @Test
    fun `repeated focus events are debounced and trailing requests are coalesced`() {
        val policy = AccessibilityAnalysisEventPolicy(debounceMs = 50L)
        val focused = AccessibilityEvent.TYPE_VIEW_FOCUSED
        assertFalse(policy.shouldDeferFocusEvent(focused, 10_000L))

        val deferred = (1..20).count { index ->
            policy.shouldDeferFocusEvent(focused, 10_000L + index * 5L)
        }
        assertEquals(20, deferred)

        val service = serviceSource()
        val syncStart = service.indexOf("private fun scheduleOverlaySynchronization()")
        val syncEnd = service.indexOf("\n    }", syncStart)
        val sync = service.substring(syncStart, syncEnd)
        assertTrue(sync.contains("overlaySyncJob?.cancel()"))
        assertTrue(sync.contains("processingQueue?.request(TrackingAnalysisRequest(now, directionManager.snapshot()))"))
        assertFalse(sync.contains("transitionLedger.process"))
    }

    @Test
    fun `unrelated accessibility event types remain ignored`() {
        val policy = AccessibilityAnalysisEventPolicy()
        assertFalse(policy.isRelevant(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertFalse(policy.isRelevant(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
    }

    @Test
    fun `window package lookup failure falls back without escaping and releases returned root`() {
        val policy = AccessibilityAnalysisEventPolicy()
        var released = 0
        val resolved = policy.resolveWindowStatePackage(
            eventPackage = "com.instagram.android",
            isTrackedPackage = { false },
            rootProvider = { "root" },
            packageFromRoot = { throw IllegalStateException("stale root property") },
            releaseRoot = { released++ }
        )

        assertEquals("com.instagram.android", resolved)
        assertEquals(1, released)
    }

    @Test
    fun `root acquisition failure does not prevent preserving the event package`() {
        val policy = AccessibilityAnalysisEventPolicy()
        val resolved = policy.resolveWindowStatePackage(
            eventPackage = "com.google.android.youtube",
            isTrackedPackage = { false },
            rootProvider = { throw SecurityException("temporary root failure") },
            packageFromRoot = { error("must not read a missing root") },
            releaseRoot = { error("must not release a missing root") }
        )

        assertEquals("com.google.android.youtube", resolved)
    }

    @Test
    fun `tracked event package avoids unnecessary root lookup`() {
        val policy = AccessibilityAnalysisEventPolicy()
        val resolved = policy.resolveWindowStatePackage(
            eventPackage = "com.instagram.android",
            isTrackedPackage = { it == "com.instagram.android" },
            rootProvider = { error("tracked event package is sufficient") },
            packageFromRoot = { null },
            releaseRoot = { }
        )

        assertEquals("com.instagram.android", resolved)
    }

    @Test
    fun `service binds detector token and increment direction to the queued request`() {
        val service = serviceSource()
        assertTrue(service.contains("copy(feedTransitionToken = request.direction.feedTransitionToken)"))
        assertTrue(service.contains("transitionDirectionForLedger = request.direction"))
        assertTrue(service.contains("transitionDirectionForLedger?.direction == ScrollDirection.BACKWARD"))
        assertTrue(service.contains("val directionSnapshot = directionManager.snapshot()"))
        assertTrue(service.contains("TrackingAnalysisRequest(now, directionSnapshot)"))
        assertFalse(service.contains("copy(feedTransitionToken = directionManager.currentFeedTransitionToken)"))
    }

    @Test
    fun `active root package property is inside root cleanup try finally`() {
        val service = serviceSource()
        val rootRead = service.indexOf("private suspend fun processLatestUIState")
        val rootRelease = service.indexOf("rootNode.recycle()", rootRead)
        val packageRead = service.indexOf("val activePackage = rootNode.packageName", rootRead)
        assertTrue(packageRead > rootRead)
        assertTrue(rootRelease > packageRead)
        assertTrue(service.substring(rootRead, rootRelease).contains("finally"))
    }

    private fun serviceSource(): String = findSourceFile(
        "src/main/java/com/reeltrak/app/services/ReelTrackerService.kt"
    ).readText()
}
