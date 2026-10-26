package com.reeltrak.app.services.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayAttachmentRecoveryTest {
    private fun findSourceFile(relativePath: String): File = listOf(
        File(relativePath), File("app/$relativePath"), File("../app/$relativePath")
    ).firstOrNull { it.exists() } ?: error("Source file not found: $relativePath")

    @Test
    fun `initial attachment can transition to attached`() {
        val policy = OverlayAttachmentRetryPolicy()

        assertTrue(policy.beginAttempt(nowMs = 0L))
        assertEquals(OverlayAttachmentRetryPolicy.State.ATTACHING, policy.state)
        policy.attachmentSucceeded()

        assertEquals(OverlayAttachmentRetryPolicy.State.ATTACHED, policy.state)
        assertFalse(policy.beginAttempt(nowMs = 10_000L))
    }

    @Test
    fun `failed initial attachment becomes retryable after backoff`() {
        val policy = OverlayAttachmentRetryPolicy()

        assertTrue(policy.beginAttempt(nowMs = 1_000L))
        policy.attachmentFailed(nowMs = 1_000L)
        assertEquals(OverlayAttachmentRetryPolicy.State.DETACHED, policy.state)
        assertFalse(policy.beginAttempt(nowMs = 1_499L))
        assertTrue(policy.beginAttempt(nowMs = 1_500L))
        policy.attachmentSucceeded()
        assertEquals(OverlayAttachmentRetryPolicy.State.ATTACHED, policy.state)
    }

    @Test
    fun `repeated events after success cannot start another window attachment`() {
        val policy = OverlayAttachmentRetryPolicy()
        assertTrue(policy.beginAttempt(nowMs = 0L))
        policy.attachmentSucceeded()

        repeat(20) { assertFalse(policy.beginAttempt(nowMs = it.toLong() + 1L)) }
        assertEquals(OverlayAttachmentRetryPolicy.State.ATTACHED, policy.state)
    }

    @Test
    fun `explicit detach allows a clean reattachment`() {
        val policy = OverlayAttachmentRetryPolicy()
        assertTrue(policy.beginAttempt(nowMs = 0L))
        policy.attachmentSucceeded()

        policy.detached()

        assertEquals(OverlayAttachmentRetryPolicy.State.DETACHED, policy.state)
        assertTrue(policy.beginAttempt(nowMs = 1L))
        policy.attachmentSucceeded()
        assertEquals(OverlayAttachmentRetryPolicy.State.ATTACHED, policy.state)
    }

    @Test
    fun `destroyed manager cannot retry`() {
        val policy = OverlayAttachmentRetryPolicy()
        assertTrue(policy.beginAttempt(nowMs = 0L))
        policy.attachmentFailed(nowMs = 0L)
        policy.destroy()

        assertEquals(OverlayAttachmentRetryPolicy.State.DESTROYED, policy.state)
        assertFalse(policy.beginAttempt(nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `repeated failures use bounded backoff and stop after three attempts`() {
        val policy = OverlayAttachmentRetryPolicy()
        assertTrue(policy.beginAttempt(nowMs = 0L))
        policy.attachmentFailed(nowMs = 0L)
        assertFalse(policy.beginAttempt(nowMs = 499L))

        assertTrue(policy.beginAttempt(nowMs = 500L))
        policy.attachmentFailed(nowMs = 500L)
        assertFalse(policy.beginAttempt(nowMs = 1_999L))

        assertTrue(policy.beginAttempt(nowMs = 2_000L))
        policy.attachmentFailed(nowMs = 2_000L)
        assertFalse(policy.beginAttempt(nowMs = Long.MAX_VALUE))
        assertEquals(OverlayAttachmentRetryPolicy.State.DETACHED, policy.state)
    }

    @Test
    fun `service counting trigger does not depend on attachment success`() {
        val service = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()
        val eventStart = service.indexOf("override fun onAccessibilityEvent(event: AccessibilityEvent?)")
        val eventEnd = service.indexOf("private fun scheduleOverlaySynchronization()", eventStart)
        assertTrue(eventStart >= 0 && eventEnd > eventStart)
        val eventPath = service.substring(eventStart, eventEnd)
        val attachRequest = eventPath.indexOf("ensureFloatingCounterAttached()")
        val analysisRequest = eventPath.indexOf("processingQueue?.request(TrackingAnalysisRequest(now")

        assertTrue(attachRequest >= 0)
        assertTrue(analysisRequest > attachRequest)
        assertFalse(eventPath.contains("if (!ensureFloatingCounterAttached"))
        assertFalse(eventPath.contains("if (floatingCounterManager?."))
    }

    @Test
    fun `service reuses manager on reconnect and attaches only one view per attached state`() {
        val service = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()
        val manager = findSourceFile("src/main/java/com/reeltrak/app/services/FloatingCounterManager.kt").readText()
        val connectedStart = service.indexOf("override fun onServiceConnected()")
        val connectedEnd = service.indexOf("// --- Unified Day & Stats Sync ---", connectedStart)
        val connectedPath = service.substring(connectedStart, connectedEnd)

        assertTrue(connectedPath.contains("ensureFloatingCounterAttached(resetRetryWindow = true)"))
        assertFalse(connectedPath.contains("floatingCounterManager = FloatingCounterManager("))
        assertTrue(manager.contains("attachmentPolicy.state == OverlayAttachmentRetryPolicy.State.ATTACHED"))
        assertEquals(1, Regex("windowManager\\.addView\\(").findAll(manager).count())
    }
}
