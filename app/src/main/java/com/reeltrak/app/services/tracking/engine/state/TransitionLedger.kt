package com.reeltrak.app.services.tracking.engine.state

import android.content.Context
import android.util.Log
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint

/**
 * Storage contract for persisting and restoring the rolling history of recognized reel identities
 * across accessibility service destruction, process death, and service restarts.
 */
interface HistoryStore {
    fun loadHistory(): List<SemanticFingerprint>
    fun saveHistory(history: List<SemanticFingerprint>)
    fun clearHistory()
    fun loadActiveFingerprint(): SemanticFingerprint? = null
    fun saveActiveFingerprint(fingerprint: SemanticFingerprint?) {}
    fun clearActiveFingerprint() {}
}

/** Result of synchronously deciding whether a candidate transition may be consumed. */
enum class TransitionAcceptance {
    ACCEPTED,
    /** Room already committed this same active fingerprint before an interrupted ledger commit. */
    ALREADY_ACCEPTED,
    SUPPRESSED,
    REJECTED
}

/**
 * Android SharedPreferences implementation of [HistoryStore].
 * Persists up to 10 reliable reel identities as a JSON array in private app preferences.
 * Only reliable identities possessing creator AND caption/audio are persisted in history; creator-only
 * identities are never persisted in history across service restarts, but active focal identity is
 * preserved separately to prevent duplicate counts on restart.
 */
class SharedPreferencesHistoryStore(
    private val context: Context,
    private val prefsName: String = PREFS_NAME
) : HistoryStore {
    companion object {
        const val PREFS_NAME = "reel_dedup_history_prefs"
        private const val KEY_HISTORY = "history_entries"
        private const val KEY_ACTIVE_FOCAL = "active_focal_entry"
    }

    override fun loadHistory(): List<SemanticFingerprint> {
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
            val jsonArray = org.json.JSONArray(jsonString)
            val result = mutableListOf<SemanticFingerprint>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val creator = obj.optString("creator", "")
                val caption = obj.optString("caption", "")
                val audio = obj.optString("audio", "")
                val platform = obj.optString("platform", "")
                val fp = SemanticFingerprint(creator, caption, audio, platform)
                if (fp.isReliableForPersistence) {
                    result.add(fp)
                }
            }
            return result.takeLast(TransitionLedger.MAX_HISTORY_SIZE)
        } catch (e: Exception) {
            Log.e("V3_Engine", "Failed to load history from SharedPreferences", e)
            return emptyList()
        }
    }

    override fun saveHistory(history: List<SemanticFingerprint>) {
        try {
            val reliableEntries = history.filter { it.isReliableForPersistence }
                .takeLast(TransitionLedger.MAX_HISTORY_SIZE)

            val jsonArray = org.json.JSONArray()
            for (fp in reliableEntries) {
                val obj = org.json.JSONObject()
                obj.put("creator", fp.primaryIdentifier)
                obj.put("caption", fp.secondaryIdentifier)
                obj.put("audio", fp.auxiliaryIdentifier)
                obj.put("platform", fp.platform)
                jsonArray.put(obj)
            }
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("V3_Engine", "Failed to save history to SharedPreferences", e)
        }
    }

    override fun clearHistory() {
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_HISTORY).apply()
        } catch (e: Exception) {
            Log.e("V3_Engine", "Failed to clear history from SharedPreferences", e)
        }
    }

    override fun loadActiveFingerprint(): SemanticFingerprint? {
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_ACTIVE_FOCAL, null) ?: return null
            val obj = org.json.JSONObject(jsonString)
            val creator = obj.optString("creator", "")
            val caption = obj.optString("caption", "")
            val audio = obj.optString("audio", "")
            val platform = obj.optString("platform", "")
            if (creator.isBlank() && caption.isBlank() && audio.isBlank()) return null
            return SemanticFingerprint(creator, caption, audio, platform)
        } catch (e: Exception) {
            Log.e("V3_Engine", "Failed to load active fingerprint from SharedPreferences", e)
            return null
        }
    }

    override fun saveActiveFingerprint(fingerprint: SemanticFingerprint?) {
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            if (fingerprint == null || fingerprint.isEmpty) {
                prefs.edit().remove(KEY_ACTIVE_FOCAL).apply()
            } else {
                val obj = org.json.JSONObject()
                obj.put("creator", fingerprint.primaryIdentifier)
                obj.put("caption", fingerprint.secondaryIdentifier)
                obj.put("audio", fingerprint.auxiliaryIdentifier)
                obj.put("platform", fingerprint.platform)
                prefs.edit().putString(KEY_ACTIVE_FOCAL, obj.toString()).apply()
            }
        } catch (e: Exception) {
            Log.e("V3_Engine", "Failed to save active fingerprint to SharedPreferences", e)
        }
    }

    override fun clearActiveFingerprint() {
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_ACTIVE_FOCAL).apply()
        } catch (e: Exception) {
            Log.e("V3_Engine", "Failed to clear active fingerprint from SharedPreferences", e)
        }
    }
}

/**
 * The V3 Deterministic Transition Ledger with 10-Identity Rolling History and Persistence.
 * Completely replaces the heuristic-driven State Machine.
 * Operates EXCLUSIVELY on COMMITTED snapshots.
 *
 * Maintains a history of the 10 most recently recognized unique reel identities,
 * separate from the currently active reel.
 *
 * NOTE: The history is intentionally bounded at 10 identities. Once an identity
 * is evicted, revisiting that reel may result in another count.
 */
class TransitionLedger(
    private val onIncrement: () -> Unit
) {
    var onHistoricalMatch: (() -> Unit)? = null
    /**
     * Optional transactional callback used by production. ACCEPTED dispatches the count,
     * SUPPRESSED consumes an intentional non-counting transition (for example a backward swipe),
     * and REJECTED leaves the focal identity and history untouched.
     */
    @Volatile
    var transitionAcceptance: (() -> TransitionAcceptance)? = null
        private set
    @Volatile
    var onTransitionCommitted: ((TransitionAcceptance) -> Unit)? = null
        private set
    private var historyStore: HistoryStore? = null

    constructor(
        onIncrement: () -> Unit,
        historyStore: HistoryStore?
    ) : this(onIncrement) {
        this.historyStore = historyStore
        restoreHistoryFromStore()
    }

    constructor(
        onIncrement: () -> Unit,
        onHistoricalMatch: (() -> Unit)?,
        historyStore: HistoryStore?,
        transitionAcceptance: () -> TransitionAcceptance,
        onTransitionCommitted: ((TransitionAcceptance) -> Unit)? = null
    ) : this(onIncrement) {
        this.onHistoricalMatch = onHistoricalMatch
        this.historyStore = historyStore
        this.transitionAcceptance = transitionAcceptance
        this.onTransitionCommitted = onTransitionCommitted
        restoreHistoryFromStore()
    }

    constructor(
        onIncrement: () -> Unit,
        onHistoricalMatch: (() -> Unit)?,
        historyStore: HistoryStore? = null
    ) : this(onIncrement) {
        this.onHistoricalMatch = onHistoricalMatch
        this.historyStore = historyStore
        restoreHistoryFromStore()
    }

    companion object {
        const val MAX_HISTORY_SIZE = 10
    }

    private val lock = Any()

    /**
     * The identity of the currently active focal reel.
     */
    private var activeFingerprint: SemanticFingerprint? = null

    /**
     * Monotonic timestamp tracking the most recently accepted committed snapshot.
     * Prevents stale snapshots arriving out of order from corrupting active state.
     */
    private var lastProcessedTimestamp: Long = 0L
    private var lastConsumedFeedTransitionToken: Long = 0L

    val latestProcessedTimestamp: Long
        get() = synchronized(lock) { lastProcessedTimestamp }

    val currentConsumedFeedTransitionToken: Long
        get() = synchronized(lock) { lastConsumedFeedTransitionToken }

    /**
     * The 10 most recently recognized unique reel identities, separate from [activeFingerprint].
     * Preserved across temporary app switches, screen pauses, internal overlays, and service restarts.
     */
    private val history = java.util.ArrayDeque<SemanticFingerprint>(MAX_HISTORY_SIZE)

    private fun restoreHistoryFromStore() {
        historyStore?.let { store ->
            val loaded = store.loadHistory()
            val restoredActive = store.loadActiveFingerprint()
            synchronized(lock) {
                if (restoredActive != null && !restoredActive.isEmpty) {
                    activeFingerprint = restoredActive
                    Log.d("V3_Engine", "TransitionLedger restored active focal identity: $restoredActive")
                }
                for (fp in loaded.takeLast(MAX_HISTORY_SIZE)) {
                    if (fp.isReliableForPersistence && history.none { it.matchesHistorical(fp) }) {
                        history.addLast(fp)
                    }
                }
                if (history.isNotEmpty()) {
                    Log.d("V3_Engine", "TransitionLedger restored ${history.size} historical identities from persistent storage.")
                }
            }
        }
    }

    /**
     * Evaluates a snapshot and deterministically decides if a physical transition occurred.
     * 
     * @return true if the snapshot was processed (COMMITTED or DISCARDED),
     *         false if it was ignored (PENDING) or its increment was rejected.
     */
    fun process(snapshot: DetectionSnapshot): Boolean {
        Log.d("V3_Engine", "Ledger.process(platform=${snapshot.platform}, state=${snapshot.classification})")
        // Discarded snapshots mean the user has temporarily opened a menu (e.g. comments)
        // or left the Reel surface. We ignore the snapshot but PRESERVE the activeFingerprint and history.
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
        val rawIncoming = snapshot.fingerprint
        if (rawIncoming.isEmpty || rawIncoming.primaryIdentifier.isBlank()) {
            Log.d("V3_Engine", "  REJECTED: Incomplete fingerprint (Missing creator)")
            return false
        }
        val incoming = if (rawIncoming.platform.isEmpty() && snapshot.platform.isNotEmpty() && snapshot.platform != "Unknown") {
            rawIncoming.copy(platform = snapshot.platform)
        } else {
            rawIncoming
        }

        synchronized(lock) {
            // Reject stale snapshots arriving out of order (BUG-E01)
            if (snapshot.timestamp > 0L && lastProcessedTimestamp > 0L && snapshot.timestamp < lastProcessedTimestamp) {
                Log.w("V3_Engine", "  REJECTED: Stale snapshot (${snapshot.timestamp} < $lastProcessedTimestamp)")
                return false
            }

            val currentActive = activeFingerprint

            // 1. Cold start / initial focal reel
            if (currentActive == null) {
                // Check if this reel is already recognized in the preserved 10-entry history
                // (e.g. returning to the same reel after a temporary app switch or screen off)
                val matchedHistorical = history.firstOrNull { incoming.matchesHistorical(it) }
                if (matchedHistorical != null) {
                    val enriched = matchedHistorical.enrichWith(incoming)
                    Log.d("V3_Engine", "  RE-ESTABLISHED: Known reel from history -> $enriched")
                    activeFingerprint = enriched
                    lastProcessedTimestamp = maxOf(lastProcessedTimestamp, snapshot.timestamp)
                    saveActiveFingerprintLocked(enriched)
                    updateHistoryEntryLocked(matchedHistorical, enriched)
                    notifyHistoricalMatch()
                    return true
                }

                // Do not mutate the ledger until production has accepted the increment.
                val acceptance = acceptCandidateTransition() ?: return false

                // Confidently identified new initial reel
                Log.d("V3_Engine", "  ACCEPTED: Initial Reel Established -> $incoming")
                lastConsumedFeedTransitionToken = maxOf(lastConsumedFeedTransitionToken, snapshot.feedTransitionToken)
                activeFingerprint = incoming
                lastProcessedTimestamp = maxOf(lastProcessedTimestamp, snapshot.timestamp)
                saveActiveFingerprintLocked(incoming)
                addToHistoryLocked(incoming)
                notifyTransitionCommitted(acceptance)
                return true
            }

            // 2. Active Reel Match: Snapshot belongs to the same physical reel currently in focus
            if (currentActive.isSameAs(incoming)) {
                val isConfirmedForwardTransition = snapshot.feedTransitionToken > 0L &&
                    snapshot.feedTransitionToken > lastConsumedFeedTransitionToken &&
                    (currentActive.secondaryIdentifier.isBlank() || incoming.secondaryIdentifier.isBlank())

                if (isConfirmedForwardTransition) {
                    val acceptance = acceptCandidateTransition() ?: return false
                    Log.d("V3_Engine", "  ACCEPTED: Confirmed feed transition for metadata-free reel (token=${snapshot.feedTransitionToken})")
                    lastConsumedFeedTransitionToken = snapshot.feedTransitionToken
                    activeFingerprint = incoming
                    lastProcessedTimestamp = maxOf(lastProcessedTimestamp, snapshot.timestamp)
                    saveActiveFingerprintLocked(incoming)
                    addToHistoryLocked(incoming)
                    notifyTransitionCommitted(acceptance)
                    return true
                }
                // The reel has mutated or enriched its metadata (e.g. caption loaded 100ms later).
                // Absorb without incrementing, and enrich active and history records if incoming has more details.
                lastProcessedTimestamp = maxOf(lastProcessedTimestamp, snapshot.timestamp)
                val enriched = currentActive.enrichWith(incoming)
                if (enriched != currentActive) {
                    activeFingerprint = enriched
                    saveActiveFingerprintLocked(enriched)
                    updateHistoryEntryLocked(currentActive, enriched)
                    Log.d("V3_Engine", "  ENRICHED: Active reel metadata enriched -> $enriched")
                } else {
                    Log.d("V3_Engine", "  REJECTED: Same Fingerprint as active. (Still on same reel)")
                }
                return true
            }

            // 3. Rolling History Match: Check if user swiped backward or returned to a previously counted reel
            val matchedHistorical = history.firstOrNull { incoming.matchesHistorical(it) }
            if (matchedHistorical != null) {
                lastProcessedTimestamp = maxOf(lastProcessedTimestamp, snapshot.timestamp)
                val enriched = matchedHistorical.enrichWith(incoming)
                activeFingerprint = enriched
                saveActiveFingerprintLocked(enriched)
                updateHistoryEntryLocked(matchedHistorical, enriched)
                lastConsumedFeedTransitionToken = maxOf(lastConsumedFeedTransitionToken, snapshot.feedTransitionToken)
                Log.d("V3_Engine", "  REVISIT: Reel matched in 10-entry history -> $enriched. Focal updated without increment.")
                notifyHistoricalMatch()
                return true
            }

            // 4. Genuine New Reel Transition Detected!
            Log.d("V3_Engine", "  ACCEPTED: Deterministic Transition Detected!")
            Log.d("V3_Engine", "    From: $currentActive")
            Log.d("V3_Engine", "    To:   $incoming")
            if (com.reeltrak.app.BuildConfig.DEBUG) {
                Log.d("COUNTER_DIAG", "[POINT_1_TRANSITION_ACCEPTED] Platform=${snapshot.platform}, from=$currentActive, to=$incoming, thread=${Thread.currentThread().name}")
            }
            
            // Acceptance runs before any ledger mutation. A rejected or throwing callback leaves
            // the same snapshot eligible for a later explicit analysis after its gate changes.
            val acceptance = acceptCandidateTransition() ?: return false

            // Instantly transfer focal ownership and record into bounded rolling history
            lastConsumedFeedTransitionToken = maxOf(lastConsumedFeedTransitionToken, snapshot.feedTransitionToken)
            activeFingerprint = incoming
            lastProcessedTimestamp = maxOf(lastProcessedTimestamp, snapshot.timestamp)
            saveActiveFingerprintLocked(incoming)
            addToHistoryLocked(incoming)
            notifyTransitionCommitted(acceptance)
            return true
        }
    }

    private fun acceptCandidateTransition(): TransitionAcceptance? {
        val acceptance = transitionAcceptance?.invoke() ?: run {
            onIncrement()
            return TransitionAcceptance.ACCEPTED
        }
        return acceptance.takeUnless { it == TransitionAcceptance.REJECTED }
    }

    private fun notifyTransitionCommitted(acceptance: TransitionAcceptance) {
        try {
            onTransitionCommitted?.invoke(acceptance)
        } catch (failure: Exception) {
            // The transition and its count dispatch are already committed. Do not reopen it:
            // replay could dispatch a duplicate daily increment.
            Log.e("V3_Engine", "Post-commit transition callback failed; transition remains consumed", failure)
        }
    }

    private fun saveActiveFingerprintLocked(fingerprint: SemanticFingerprint?) {
        try {
            historyStore?.saveActiveFingerprint(fingerprint)
        } catch (failure: Exception) {
            Log.e("V3_Engine", "Failed to persist active fingerprint", failure)
        }
    }

    private fun notifyHistoricalMatch() {
        try {
            onHistoricalMatch?.invoke()
        } catch (failure: Exception) {
            Log.e("V3_Engine", "Historical-match callback failed", failure)
        }
    }

    private fun addToHistoryLocked(fingerprint: SemanticFingerprint) {
        if (history.size >= MAX_HISTORY_SIZE) {
            val evicted = history.removeFirst()
            Log.d("V3_Engine", "  EVICTED: Oldest history entry evicted -> $evicted")
        }
        history.addLast(fingerprint)
        persistHistoryLocked()
    }

    private fun updateHistoryEntryLocked(oldEntry: SemanticFingerprint, newEntry: SemanticFingerprint) {
        if (history.isEmpty()) return
        val list = history.toList()
        history.clear()
        for (item in list) {
            if (item == oldEntry || item.matchesHistorical(oldEntry)) {
                history.addLast(newEntry)
            } else {
                history.addLast(item)
            }
        }
        persistHistoryLocked()
    }

    private fun persistHistoryLocked() {
        try {
            historyStore?.saveHistory(history.filter { it.isReliableForPersistence })
        } catch (e: Exception) {
            Log.e("V3_Engine", "Failed to persist history to HistoryStore", e)
        }
    }

    /**
     * Unlatches the active focal reel while PRESERVING the 10-entry rolling history.
     * Used when the user temporarily leaves the Reels surface (e.g. app switch, screen off, feed,
     * or service recreation).
     */
    fun clearActive() {
        synchronized(lock) {
            Log.d("V3_Engine", "Ledger: Clearing active focal reel (history preserved).")
            lastConsumedFeedTransitionToken = 0L
            activeFingerprint = null
            lastProcessedTimestamp = 0L
            try {
                historyStore?.clearActiveFingerprint()
            } catch (e: Exception) {
                Log.e("V3_Engine", "Failed to clear active fingerprint", e)
            }
        }
    }

    /**
     * Full reset of active focal reel AND history.
     * Clears both in-memory history and persistent storage.
     */
    fun resetFull() {
        synchronized(lock) {
            Log.d("V3_Engine", "Ledger: Full reset (Clearing active reel and rolling history).")
            lastConsumedFeedTransitionToken = 0L
            activeFingerprint = null
            lastProcessedTimestamp = 0L
            history.clear()
            try {
                historyStore?.clearHistory()
                historyStore?.clearActiveFingerprint()
            } catch (e: Exception) {
                Log.e("V3_Engine", "Failed to clear persistent history", e)
            }
        }
    }

    /**
     * Resets active state. Preserves history by default for transient lifecycle pauses,
     * or fully resets if [clearHistory] is true.
     */
    fun reset(clearHistory: Boolean = false) {
        synchronized(lock) {
            lastConsumedFeedTransitionToken = 0L
        }
        synchronized(lock) {
            activeFingerprint = null
            lastProcessedTimestamp = 0L
            try {
                historyStore?.clearActiveFingerprint()
            } catch (e: Exception) {
                Log.e("V3_Engine", "Failed to clear active fingerprint", e)
            }
            if (clearHistory) {
                history.clear()
                try {
                    historyStore?.clearHistory()
                } catch (e: Exception) {
                    Log.e("V3_Engine", "Failed to clear persistent history", e)
                }
            }
        }
    }

    /**
     * Direct inspection of the active focal fingerprint for testing and diagnostics.
     */
    val currentActiveFingerprint: SemanticFingerprint?
        get() = synchronized(lock) { activeFingerprint }

    /**
     * Direct snapshot of the 10-entry rolling history for testing and diagnostics.
     */
    val historySnapshot: List<SemanticFingerprint>
        get() = synchronized(lock) { history.toList() }
}
