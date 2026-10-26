package com.reeltrak.app.services.tracking.engine.model

/**
 * The classification of a snapshot determines its eligibility for Ledger processing.
 */
enum class SnapshotClassification {
    DISCARDED,  // The screen is not a supported Reel/Short, or a blocking overlay is visible.
    PENDING,    // The screen is a Reel/Short, but the minimum deterministic identifiers (e.g. Creator) are not yet fully populated.
    INCONCLUSIVE, // The accessibility hierarchy did not provide enough evidence to classify this pass.
    COMMITTED   // The screen is a Reel/Short, and all deterministic identifiers are fully populated and stable.
}

/** A recognized overlay associated with a Reel surface, or an unrelated generic overlay. */
enum class InternalOverlayKind {
    NONE,
    REEL_COMMENTS,
    REEL_ENGAGEMENT,
    GENERIC
}

/**
 * A platform-agnostic deterministic identifier for a physical Reel or Short.
 * Detectors must normalize volatile data (e.g., caption expansion, translations) BEFORE generating this fingerprint.
 */
data class SemanticFingerprint(
    val primaryIdentifier: String = "",
    val secondaryIdentifier: String = "",
    val auxiliaryIdentifier: String = "",
    val platform: String = ""
) {
    val isEmpty: Boolean
        get() = primaryIdentifier.isBlank() && secondaryIdentifier.isBlank() && auxiliaryIdentifier.isBlank()

    /**
     * Determines whether this identity possesses sufficient identifying content
     * (creator AND caption or audio) to be safely persisted across service restarts.
     * Prevents creator-only or ambiguous identities from being persisted as universal identifiers.
     */
    val isReliableForPersistence: Boolean
        get() = !isEmpty && primaryIdentifier.isNotBlank() && (secondaryIdentifier.isNotBlank() || auxiliaryIdentifier.isNotBlank())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SemanticFingerprint) return false
        if (primaryIdentifier != other.primaryIdentifier) return false
        if (secondaryIdentifier != other.secondaryIdentifier) return false
        if (auxiliaryIdentifier != other.auxiliaryIdentifier) return false
        // Only enforce platform differentiation if both fingerprints declare non-empty platforms
        if (platform.isNotEmpty() && other.platform.isNotEmpty() && !platform.equals(other.platform, ignoreCase = true)) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = primaryIdentifier.hashCode()
        result = 31 * result + secondaryIdentifier.hashCode()
        result = 31 * result + auxiliaryIdentifier.hashCode()
        return result
    }

    companion object {
        fun isCaptionEnrichment(c1: String, c2: String): Boolean {
            if (c1.isEmpty() || c2.isEmpty()) return true
            if (c1.equals(c2, ignoreCase = true)) return true
            if (c1.startsWith(c2, ignoreCase = true) || c2.startsWith(c1, ignoreCase = true)) return true

            val clean1 = c1.removeSuffix("...").removeSuffix("…").removeSuffix("more").trim()
            val clean2 = c2.removeSuffix("...").removeSuffix("…").removeSuffix("more").trim()
            if (clean1.isNotEmpty() && clean2.isNotEmpty()) {
                if (clean1.startsWith(clean2, ignoreCase = true) || clean2.startsWith(clean1, ignoreCase = true)) return true
            }

            val minLen = minOf(clean1.length, clean2.length)
            if (minLen >= 10 && (clean1.contains(clean2, ignoreCase = true) || clean2.contains(clean1, ignoreCase = true))) return true

            // Substantial common prefix for minor truncation or streaming differences:
            // The common prefix must cover almost the entire shorter string (within 3 characters),
            // strictly rejecting distinct captions from the same creator that merely share introductory words.
            val commonPrefixLen = clean1.commonPrefixWith(clean2, ignoreCase = true).length
            if (minLen >= 15 && commonPrefixLen >= minLen - 3) return true

            // Harmless mutation / minor text updates on the same active reel:
            // Allows up to 10% character edits for dynamic badges, counter updates, or volatile tokens,
            // while rejecting distinct topics or different videos from the same creator.
            if (isHarmlessMutation(clean1, clean2)) return true

            return false
        }

        private fun isHarmlessMutation(s1: String, s2: String): Boolean {
            val minLen = minOf(s1.length, s2.length)
            if (minLen < 25) return false
            val maxLen = maxOf(s1.length, s2.length)
            val maxAllowedDistance = (maxLen * 0.08).toInt()
            if (maxAllowedDistance < 1) return false
            if (kotlin.math.abs(s1.length - s2.length) > maxAllowedDistance) return false

            return boundedLevenshteinDistance(s1, s2, maxAllowedDistance) <= maxAllowedDistance
        }

        private fun boundedLevenshteinDistance(s1: String, s2: String, maxLimit: Int): Int {
            val len1 = s1.length
            val len2 = s2.length
            if (kotlin.math.abs(len1 - len2) > maxLimit) return maxLimit + 1

            var prev = IntArray(len2 + 1) { it }
            var curr = IntArray(len2 + 1)

            for (i in 1..len1) {
                curr[0] = i
                var minRow = curr[0]
                for (j in 1..len2) {
                    val cost = if (s1[i - 1].equals(s2[j - 1], ignoreCase = true)) 0 else 1
                    curr[j] = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + cost
                    )
                    minRow = minOf(minRow, curr[j])
                }
                if (minRow > maxLimit) return maxLimit + 1
                val temp = prev
                prev = curr
                curr = temp
            }
            return prev[len2]
        }
    }

    /**
     * Determines whether two fingerprints represent the same semantic reel/creator context.
     *
     * Rules:
     * 1. Two empty fingerprints cannot be considered identical reels.
     * 2. Cross-platform deduplication namespace: Different non-empty platforms NEVER represent the same reel.
     * 3. If both snapshots possess a non-empty primary identifier (creator):
     *    - Different creators => DIFFERENT reels.
     *    - Same creator:
     *      - If both possess non-empty audio and audio differs => DIFFERENT reels!
     *      - If both possess non-empty captions:
     *        - Matching or enriched/expanded captions => SAME reel.
     *        - Distinct, non-overlapping captions => DIFFERENT reels!
     *      - If one snapshot has caption/audio and the other is empty, they match as an
     *        enrichment of the same active reel during progressive loading.
     *      - If both lack caption and audio (creator-only), identity is ambiguous;
     *        they match to prevent speculative increments on every frame.
     * 4. If primary identifiers are absent: strict equality or enrichment across secondary (caption)
     *    or auxiliary (audio) identifiers is required.
     */
    fun isSameAs(other: SemanticFingerprint): Boolean {
        if (this.isEmpty && other.isEmpty) return false

        // Cross-platform deduplication namespace: Different non-empty platforms NEVER represent the same reel.
        if (this.platform.isNotEmpty() && other.platform.isNotEmpty() &&
            !this.platform.equals(other.platform, ignoreCase = true)) {
            return false
        }

        if (this.primaryIdentifier.isNotEmpty() && other.primaryIdentifier.isNotEmpty()) {
            if (!this.primaryIdentifier.equals(other.primaryIdentifier, ignoreCase = true)) {
                return false
            }

            val hasThisCaption = this.secondaryIdentifier.isNotEmpty()
            val hasOtherCaption = other.secondaryIdentifier.isNotEmpty()
            val hasThisAudio = this.auxiliaryIdentifier.isNotEmpty()
            val hasOtherAudio = other.auxiliaryIdentifier.isNotEmpty()

            // Different audio on the same creator proves different physical reels
            if (hasThisAudio && hasOtherAudio &&
                !this.auxiliaryIdentifier.equals(other.auxiliaryIdentifier, ignoreCase = true)) {
                return false
            }

            if (hasThisCaption && hasOtherCaption) {
                if (this.secondaryIdentifier.equals(other.secondaryIdentifier, ignoreCase = true) ||
                    isCaptionEnrichment(this.secondaryIdentifier, other.secondaryIdentifier)) {
                    return true
                }
                return false
            }

            // Unconflicted: progressive enrichment during loading or ambiguous creator-only
            return true
        }

        if (this.primaryIdentifier.isEmpty() && other.primaryIdentifier.isEmpty()) {
            val hasCaptions = this.secondaryIdentifier.isNotEmpty() && other.secondaryIdentifier.isNotEmpty()
            val hasAudio = this.auxiliaryIdentifier.isNotEmpty() && other.auxiliaryIdentifier.isNotEmpty()
            if (hasCaptions && (this.secondaryIdentifier == other.secondaryIdentifier ||
                                isCaptionEnrichment(this.secondaryIdentifier, other.secondaryIdentifier))) return true
            if (hasAudio && this.auxiliaryIdentifier.equals(other.auxiliaryIdentifier, ignoreCase = true)) return true
            return false
        }

        if (this.secondaryIdentifier.isNotEmpty() && other.secondaryIdentifier.isNotEmpty()) {
            return this.secondaryIdentifier == other.secondaryIdentifier ||
                   isCaptionEnrichment(this.secondaryIdentifier, other.secondaryIdentifier)
        }

        return false
    }

    /**
     * Confidently checks if this fingerprint matches a previously recognized historical identity.
     *
     * Critical Safety Rule:
     * Creator name alone is NEVER considered sufficient proof that an incoming reel matches
     * an old reel from history. A match requires matching caption, matching audio, or both
     * having identical content.
     */
    fun matchesHistorical(historyItem: SemanticFingerprint): Boolean {
        if (this.isEmpty || historyItem.isEmpty) return false

        // Cross-platform deduplication namespace: Different non-empty platforms NEVER match in history.
        if (this.platform.isNotEmpty() && historyItem.platform.isNotEmpty() &&
            !this.platform.equals(historyItem.platform, ignoreCase = true)) {
            return false
        }

        if (this.primaryIdentifier.isNotEmpty() && historyItem.primaryIdentifier.isNotEmpty()) {
            if (!this.primaryIdentifier.equals(historyItem.primaryIdentifier, ignoreCase = true)) {
                return false
            }

            val hasBothCaptions = this.secondaryIdentifier.isNotEmpty() && historyItem.secondaryIdentifier.isNotEmpty()
            val hasBothAudio = this.auxiliaryIdentifier.isNotEmpty() && historyItem.auxiliaryIdentifier.isNotEmpty()

            if (hasBothCaptions) {
                if (this.secondaryIdentifier.equals(historyItem.secondaryIdentifier, ignoreCase = true) ||
                    isCaptionEnrichment(this.secondaryIdentifier, historyItem.secondaryIdentifier)) {
                    return true
                }
                return false
            }
            if (hasBothAudio) {
                return this.auxiliaryIdentifier.equals(historyItem.auxiliaryIdentifier, ignoreCase = true)
            }

            // Both have matching creator and neither has caption or audio
            if (this.secondaryIdentifier.isEmpty() && historyItem.secondaryIdentifier.isEmpty() &&
                this.auxiliaryIdentifier.isEmpty() && historyItem.auxiliaryIdentifier.isEmpty()) {
                return true
            }

            // One has caption/audio and the other does not: creator alone cannot prove identity
            return false
        }

        if (this.secondaryIdentifier.isNotEmpty() && historyItem.secondaryIdentifier.isNotEmpty()) {
            return this.secondaryIdentifier == historyItem.secondaryIdentifier ||
                   isCaptionEnrichment(this.secondaryIdentifier, historyItem.secondaryIdentifier)
        }
        if (this.auxiliaryIdentifier.isNotEmpty() && historyItem.auxiliaryIdentifier.isNotEmpty()) {
            return this.auxiliaryIdentifier.equals(historyItem.auxiliaryIdentifier, ignoreCase = true)
        }

        return false
    }

    /**
     * Enriches this fingerprint with non-empty metadata from another snapshot of the same reel.
     */
    fun enrichWith(other: SemanticFingerprint): SemanticFingerprint {
        return SemanticFingerprint(
            primaryIdentifier = if (this.primaryIdentifier.isNotEmpty()) this.primaryIdentifier else other.primaryIdentifier,
            secondaryIdentifier = if (this.secondaryIdentifier.isNotEmpty()) this.secondaryIdentifier else other.secondaryIdentifier,
            auxiliaryIdentifier = if (this.auxiliaryIdentifier.isNotEmpty()) this.auxiliaryIdentifier else other.auxiliaryIdentifier,
            platform = if (this.platform.isNotEmpty()) this.platform else other.platform
        )
    }
}

/**
 * A point-in-time capture of the Accessibility Tree analysis.
 */
data class DetectionSnapshot(
    val timestamp: Long = defaultElapsedRealtime(),
    val wallClockTimeMillis: Long = System.currentTimeMillis(),
    val classification: SnapshotClassification = SnapshotClassification.DISCARDED,
    val fingerprint: SemanticFingerprint = SemanticFingerprint(),
    val platform: String = "Unknown",
    val nodeCount: Int = 0,
    val isInternalOverlay: Boolean = false,
    val internalOverlayKind: InternalOverlayKind = InternalOverlayKind.NONE,
    val feedTransitionToken: Long = 0L
) {
    companion object {
        internal fun defaultElapsedRealtime(): Long {
            return try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: Throwable) {
                0L
            }
        }
    }
    val isCommitted: Boolean get() = classification == SnapshotClassification.COMMITTED
    val isDiscarded: Boolean get() = classification == SnapshotClassification.DISCARDED
    val isPending: Boolean get() = classification == SnapshotClassification.PENDING
    val isInconclusive: Boolean get() = classification == SnapshotClassification.INCONCLUSIVE
}
