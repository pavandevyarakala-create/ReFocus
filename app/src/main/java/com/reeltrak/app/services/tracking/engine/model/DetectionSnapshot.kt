package com.reeltrak.app.services.tracking.engine.model

/**
 * The classification of a snapshot determines its eligibility for Ledger processing.
 */
enum class SnapshotClassification {
    DISCARDED,  // The screen is not a supported Reel/Short, or a blocking overlay is visible.
    PENDING,    // The screen is a Reel/Short, but the minimum deterministic identifiers (e.g. Creator) are not yet fully populated.
    COMMITTED   // The screen is a Reel/Short, and all deterministic identifiers are fully populated and stable.
}

/**
 * A platform-agnostic deterministic identifier for a physical Reel or Short.
 * Detectors must normalize volatile data (e.g., caption expansion, translations) BEFORE generating this fingerprint.
 */
data class SemanticFingerprint(
    val primaryIdentifier: String = "",
    val secondaryIdentifier: String = "",
    val auxiliaryIdentifier: String = ""
) {
    /**
     * Strict equality for fingerprint matching.
     * Because the Ledger exclusively evaluates COMMITTED snapshots, these identifiers are guaranteed
     * to be fully populated and normalized by the detector. No heuristics are permitted here.
     */
    fun isSameAs(other: SemanticFingerprint): Boolean {
        // Two empty fingerprints cannot be considered mathematically identical reels.
        if (this.primaryIdentifier.isEmpty() && other.primaryIdentifier.isEmpty()) return false
        
        return this.primaryIdentifier == other.primaryIdentifier &&
               this.secondaryIdentifier == other.secondaryIdentifier &&
               this.auxiliaryIdentifier == other.auxiliaryIdentifier
    }
}

/**
 * A point-in-time capture of the Accessibility Tree analysis.
 */
data class DetectionSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val classification: SnapshotClassification = SnapshotClassification.DISCARDED,
    val fingerprint: SemanticFingerprint = SemanticFingerprint(),
    val platform: String = "Unknown",
    val nodeCount: Int = 0,
    val isInternalOverlay: Boolean = false
) {
    val isCommitted: Boolean get() = classification == SnapshotClassification.COMMITTED
    val isDiscarded: Boolean get() = classification == SnapshotClassification.DISCARDED
    val isPending: Boolean get() = classification == SnapshotClassification.PENDING
}
