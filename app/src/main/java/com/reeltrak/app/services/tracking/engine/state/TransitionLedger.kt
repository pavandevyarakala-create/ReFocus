package com.reeltrak.app.services.tracking.engine.state

import android.util.Log
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint

/**
 * The V3 Deterministic Transition Ledger.
 * Completely replaces the heuristic-driven State Machine.
 * Operates EXCLUSIVELY on COMMITTED snapshots.
 */
class TransitionLedger(
    private val onIncrement: () -> Unit
) {
    /**
     * The identity of the last successfully counted reel.
     * We preserve this across session resets (e.g. when leaving the app temporarily)
     * to prevent double-counting the same reel when the user returns.
     */
    private var lastCountedFingerprint: SemanticFingerprint? = null

    /**
     * Evaluates a snapshot and deterministically decides if a physical transition occurred.
     * 
     * @return true if the snapshot was processed (COMMITTED or DISCARDED), 
     *         false if it was ignored (PENDING).
     */
    fun process(snapshot: DetectionSnapshot): Boolean {
        Log.d("V3_Engine", "Ledger.process(platform=${snapshot.platform}, state=${snapshot.classification})")
        // Discarded snapshots mean the user has temporarily opened a menu (e.g. comments)
        // or left the Reel surface. We ignore the snapshot but PRESERVE the lastCountedFingerprint.
        // This mathematically prevents double-counting when the menu is closed and the same reel is revealed.
        if (snapshot.isDiscarded) {
            Log.d("V3_Engine", "  REJECTED: Snapshot is DISCARDED (Overlays or non-Reels)")
            return true
        }

        // Pending snapshots represent transient loading or scroll physics.
        // The ledger strictly ignores them. No state changes, no increments.
        if (snapshot.isPending) {
            Log.d("V3_Engine", "  PENDING: Snapshot is PENDING (Missing metadata)")
            return false
        }

        // We are now mathematically guaranteed the snapshot is COMMITTED.
        // It possesses a stable, fully populated Minimum Valid Fingerprint.
        val newFingerprint = snapshot.fingerprint

        if (lastCountedFingerprint == null) {
            // Cold start: establish the first detected reel as the baseline for this session.
            Log.d("V3_Engine", "  INITIALIZING: Baseline established -> $newFingerprint")
            lastCountedFingerprint = newFingerprint
            return true
        }

        // The strongest conclusion that can be drawn from two COMMITTED snapshots
        // with distinct fingerprints is that a physical reel transition occurred.
        if (!lastCountedFingerprint!!.isSameAs(newFingerprint)) {
            Log.d("V3_Engine", "  ACCEPTED: Deterministic Transition Detected!")
            Log.d("V3_Engine", "    From: $lastCountedFingerprint")
            Log.d("V3_Engine", "    To:   $newFingerprint")
            
            onIncrement()
            
            // Instantly transfer focal ownership.
            lastCountedFingerprint = newFingerprint
        } else {
            // The reel has mutated but its semantic identity remains the same.
            // This is absorbed silently.
            Log.d("V3_Engine", "  REJECTED: Same Fingerprint as active. (Still on same reel)")
        }

        return true
    }

    /**
     * Resets transient ledger state.
     * We clear the fingerprint to ensure the next session establishes a fresh baseline.
     */
    fun reset() {
        Log.d("V3_Engine", "Ledger: Resetting (Clearing baseline fingerprint).")
        lastCountedFingerprint = null
    }
}
