package com.reeltrak.app.services

/** System UI events alone cannot invalidate a previously confirmed reel surface. */
internal object TransientSystemUiVisibilityPolicy {
    fun shouldPreserveConfirmedReel(
        sessionState: com.reeltrak.app.services.tracking.SessionState,
        isScreenReady: Boolean
    ): Boolean = isScreenReady && (
        sessionState == com.reeltrak.app.services.tracking.SessionState.ENTERING_REELS ||
            sessionState == com.reeltrak.app.services.tracking.SessionState.ACTIVE ||
            sessionState == com.reeltrak.app.services.tracking.SessionState.PAUSED
        )
}
