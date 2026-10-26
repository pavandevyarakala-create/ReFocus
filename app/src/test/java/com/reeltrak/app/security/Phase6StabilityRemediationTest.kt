package com.reeltrak.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 6 Stability & Reliability Remediation Verification Tests
 *
 * Verifies:
 * - P6-01: ReelTrackerService does not use hashCode for date tracking and uses collision-free String comparison.
 * - P6-02: DayManager broadcast receiver explicitly filters actions and safely ignores null/unexpected intents.
 * - P6-03: FloatingCounterManager cancels active snap animations and nulls the animator reference in detach().
 */
class Phase6StabilityRemediationTest {

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
    fun testP6_01_ReelTrackerService_DateTracking_NoHashCode() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        // Verify hashCode is no longer used for epoch day / date string comparison
        assertFalse(
            "ReelTrackerService must not use dateStr.hashCode() for tracking date changes",
            content.contains("dateStr.hashCode()")
        )
        assertFalse(
            "ReelTrackerService must not maintain lastProcessedEpochDay",
            content.contains("lastProcessedEpochDay")
        )

        // Verify lastProcessedDateString is used
        assertTrue(
            "ReelTrackerService must use lastProcessedDateString for clean date tracking",
            content.contains("lastProcessedDateString")
        )
        assertTrue(
            "ReelTrackerService must check dateStr != lastProcessedDateString",
            content.contains("dateStr != lastProcessedDateString")
        )
    }

    @Test
    fun testP6_02_DayManager_BroadcastReceiverActionValidation() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val content = file.readText()

        // Verify action extraction and null-safe filtering
        assertTrue(
            "DayManager receiver must null-check intent?.action",
            content.contains("val action = intent?.action ?: return")
        )
        assertTrue(
            "DayManager receiver must explicitly check ACTION_DATE_CHANGED",
            content.contains("action == Intent.ACTION_DATE_CHANGED")
        )
        assertTrue(
            "DayManager receiver must explicitly check ACTION_TIME_CHANGED",
            content.contains("action == Intent.ACTION_TIME_CHANGED")
        )
        assertTrue(
            "DayManager receiver must explicitly check ACTION_TIMEZONE_CHANGED",
            content.contains("action == Intent.ACTION_TIMEZONE_CHANGED")
        )
    }

    @Test
    fun testP6_03_FloatingCounterManager_SnapAnimatorCancelledOnDetach() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/FloatingCounterManager.kt")
        val content = file.readText()

        // Verify detach() cancels snapAnimator
        assertTrue(
            "FloatingCounterManager must cancel snapAnimator in detach()",
            content.contains("snapAnimator?.cancel()")
        )
        assertTrue(
            "FloatingCounterManager must null out snapAnimator in detach()",
            content.contains("snapAnimator = null")
        )

        // Verify snapAnimator cleanup occurs before windowManager.removeView
        val detachIndex = content.indexOf("private fun detach()")
        assertTrue("detach() function must exist", detachIndex != -1)
        val detachBody = content.substring(detachIndex, content.indexOf("}", detachIndex))

        val cancelIndex = detachBody.indexOf("snapAnimator?.cancel()")
        val removeViewIndex = detachBody.indexOf("windowManager.removeView")
        assertTrue("snapAnimator?.cancel() must occur before windowManager.removeView", cancelIndex < removeViewIndex)
    }
}
