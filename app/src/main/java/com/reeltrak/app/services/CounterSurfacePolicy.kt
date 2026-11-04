package com.reeltrak.app.services

import com.reeltrak.app.services.tracking.SessionState
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind

/** Determines whether a detected internal overlay still represents active reel content. */
internal object CounterSurfacePolicy {
    fun isPreservingReelOverlay(snapshot: DetectionSnapshot, sessionState: SessionState): Boolean =
        snapshot.isInternalOverlay &&
            snapshot.internalOverlayKind != InternalOverlayKind.REEL_COMMENTS &&
            (sessionState == SessionState.ACTIVE || sessionState == SessionState.PAUSED)
}
