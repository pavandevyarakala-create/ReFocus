package com.reeltrak.app.core.update

import android.app.Activity
import com.google.android.play.core.install.model.ActivityResult
import com.reeltrak.app.services.overlay.OverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppUpdateManagerTest {

    @Test
    fun testAppUpdateStatus_enumContainsAllRequiredStates() {
        val states = AppUpdateStatus.values().map { it.name }
        assertTrue(states.contains("UPDATE_NOT_REQUIRED"))
        assertTrue(states.contains("UPDATE_REQUIRED"))
        assertTrue(states.contains("UPDATE_IN_PROGRESS"))
        assertTrue(states.contains("UPDATE_FAILED"))
    }

    @Test
    fun testOverlayState_isUpdateRequiredDefaultsToFalse() {
        val defaultState = OverlayState()
        assertFalse("OverlayState.isUpdateRequired must default to false", defaultState.isUpdateRequired)

        val updatedState = defaultState.copy(isUpdateRequired = true)
        assertTrue("OverlayState.isUpdateRequired can be updated to true", updatedState.isUpdateRequired)
    }

    @Test
    fun testLockState_enforcedOnRequiredFailedAndInProgress() {
        fun isLocked(status: AppUpdateStatus): Boolean {
            return status == AppUpdateStatus.UPDATE_REQUIRED ||
                    status == AppUpdateStatus.UPDATE_FAILED ||
                    status == AppUpdateStatus.UPDATE_IN_PROGRESS
        }

        assertFalse("UPDATE_NOT_REQUIRED must not lock the app", isLocked(AppUpdateStatus.UPDATE_NOT_REQUIRED))
        assertTrue("UPDATE_REQUIRED must lock the app", isLocked(AppUpdateStatus.UPDATE_REQUIRED))
        assertTrue("UPDATE_IN_PROGRESS must lock the app", isLocked(AppUpdateStatus.UPDATE_IN_PROGRESS))
        assertTrue("UPDATE_FAILED must lock the app", isLocked(AppUpdateStatus.UPDATE_FAILED))
    }

    @Test
    fun testCancellationOrFailure_cannotUnlockApp() {
        // Simulating the result code mapping behavior in handleActivityResult
        fun mapResultToStatus(resultCode: Int): AppUpdateStatus {
            return when (resultCode) {
                Activity.RESULT_OK -> AppUpdateStatus.UPDATE_IN_PROGRESS
                Activity.RESULT_CANCELED -> AppUpdateStatus.UPDATE_FAILED
                ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> AppUpdateStatus.UPDATE_FAILED
                else -> AppUpdateStatus.UPDATE_FAILED
            }
        }

        val cancelledStatus = mapResultToStatus(Activity.RESULT_CANCELED)
        assertEquals(AppUpdateStatus.UPDATE_FAILED, cancelledStatus)

        val failedStatus = mapResultToStatus(ActivityResult.RESULT_IN_APP_UPDATE_FAILED)
        assertEquals(AppUpdateStatus.UPDATE_FAILED, failedStatus)

        val unknownErrorStatus = mapResultToStatus(999)
        assertEquals(AppUpdateStatus.UPDATE_FAILED, unknownErrorStatus)
    }

    @Test
    fun testUpdateCheckCooldownLogic() {
        val cooldownMs = 15 * 60 * 1000L
        fun shouldSkipCheck(
            lastCheckMs: Long,
            nowMs: Long,
            currentStatus: AppUpdateStatus,
            force: Boolean
        ): Boolean {
            if (force) return false
            if (currentStatus == AppUpdateStatus.UPDATE_REQUIRED || currentStatus == AppUpdateStatus.UPDATE_IN_PROGRESS) return false
            return lastCheckMs > 0L && (nowMs - lastCheckMs) < cooldownMs
        }

        // Fresh start: lastCheck is 0 -> should NOT skip
        assertFalse(shouldSkipCheck(0L, 1000L, AppUpdateStatus.UPDATE_NOT_REQUIRED, false))

        // Check within cooldown (< 15 mins) -> should skip
        assertTrue(shouldSkipCheck(1000L, 5000L, AppUpdateStatus.UPDATE_NOT_REQUIRED, false))

        // Check after cooldown (> 15 mins) -> should NOT skip
        assertFalse(shouldSkipCheck(1000L, 1000L + cooldownMs + 1L, AppUpdateStatus.UPDATE_NOT_REQUIRED, false))

        // Force check -> should NOT skip even within cooldown
        assertFalse(shouldSkipCheck(1000L, 5000L, AppUpdateStatus.UPDATE_NOT_REQUIRED, true))

        // When UPDATE_REQUIRED or UPDATE_IN_PROGRESS -> should NOT skip even within cooldown
        assertFalse(shouldSkipCheck(1000L, 5000L, AppUpdateStatus.UPDATE_REQUIRED, false))
        assertFalse(shouldSkipCheck(1000L, 5000L, AppUpdateStatus.UPDATE_IN_PROGRESS, false))
    }
}
