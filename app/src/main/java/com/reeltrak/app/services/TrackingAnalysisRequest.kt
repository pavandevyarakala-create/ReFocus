package com.reeltrak.app.services

import com.reeltrak.app.services.accessibility.snapshot.DirectionStateSnapshot

/** A latest-state tree analysis request with the scroll evidence captured when it was queued. */
internal data class TrackingAnalysisRequest(
    val eventTime: Long,
    val direction: DirectionStateSnapshot
)
