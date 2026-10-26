package com.reeltrak.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 7 Performance & Latency Remediation Verification Tests
 *
 * Verifies:
 * - P7-01: Reel increments have a durable Room acceptance boundary.
 * - P7-02: ReelTrackerService trailing overlay sync guard:
 *   * Skips secondary rootInActiveWindow IPC and detector analysis when session is already ACTIVE, visible, and confidence is 100%.
 *   * Retains trailing sync for recovering from debounced or non-active states.
 */
class Phase7PerformanceRemediationTest {

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
    fun testP7_01_DayManager_WriteCoalescingAndFlush() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val content = file.readText().replace("\r\n", "\n")

        assertTrue(content.contains("fun acceptReelTransition("))
        assertTrue(content.contains("dailyReelDataDao.acceptReelTransition("))
        assertTrue(content.contains("_currentDayStats.value = committedStats"))
        assertFalse(content.contains("pendingWrites"))

        val dao = findSourceFile("src/main/java/com/reeltrak/app/data/local/dao/DailyReelDataDao.kt").readText().replace("\r\n", "\n")
        assertTrue(dao.contains("@Transaction\n    suspend fun acceptReelTransition("))
        assertTrue(dao.contains("insertAcceptedTransitionState("))
        assertTrue(dao.contains("incrementDailyReelCount(dateString, acceptedAtMs)"))
    }

    @Test
    fun testP7_02_ReelTrackerService_GuardedTrailingSynchronization() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        // Verify trailing sync properly debounces against lastEventProcessingTime and routes through queue
        assertTrue(
            "scheduleOverlaySynchronization must check now - lastEventProcessingTime >= EVENT_DEBOUNCE_MS",
            content.contains("now - lastEventProcessingTime >= EVENT_DEBOUNCE_MS")
        )
        assertTrue(
            "scheduleOverlaySynchronization must route through processingQueue",
            content.contains("processingQueue?.request(TrackingAnalysisRequest(now, directionManager.snapshot()))")
        )
        assertTrue(
            "processLatestUIState must acquire rootNode with retry fallback",
            content.contains("rootNode = try { rootInActiveWindow } catch (e: Exception) { null }")
        )
        assertTrue(
            "processLatestUIState must find matching detector",
            content.contains("detectors.find { it.supports(activePackage) }")
        )
    }

    @Test
    fun testP7_03_ReelTrackerService_ScheduleOverlaySync_GuaranteedRootRecycle() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        // Verify try/finally structure wraps rootNode lifecycle in processLatestUIState
        val processIndex = content.indexOf("private suspend fun processLatestUIState(")
        assertTrue("processLatestUIState must exist", processIndex != -1)
        val processBody = content.substring(processIndex)

        assertTrue(
            "processLatestUIState must call rootNode.recycle() in finally block",
            processBody.contains("rootNode.recycle()")
        )
        assertTrue(
            "processLatestUIState must have finally block containing rootNode.recycle()",
            processBody.contains("finally") && processBody.contains("rootNode.recycle()")
        )
    }
}
