package com.reeltrak.app.domain.models

/**
 * Represents the immutable, stable identity of a reel.
 * Excludes all transient UI mutations (captions, likes, comments).
 */
data class ReelIdentity(
    val usernameHash: Int,
    val audioHash: Int,
    val isAd: Boolean = false
) {
    /**
     * A reel is only considered structurally identical if both the user and audio match.
     * Ads are given a unique random hash to force a state mismatch (so they can be handled separately).
     */
    fun getStableHash(): Int {
        if (isAd) return (Math.random() * 100000).toInt() // Ads always force a transition
        return usernameHash * 31 + audioHash
    }

    companion object {
        val EMPTY = ReelIdentity(0, 0)
    }
}
