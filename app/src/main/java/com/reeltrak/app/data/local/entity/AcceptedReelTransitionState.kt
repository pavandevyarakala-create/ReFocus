package com.reeltrak.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint

/** Durable idempotency marker for the latest counted transition for an account and local day. */
@Entity(
    tableName = "accepted_reel_transition_state",
    primaryKeys = ["account_scope", "date_string"]
)
data class AcceptedReelTransitionState(
    @ColumnInfo(name = "account_scope") val accountScope: String,
    @ColumnInfo(name = "date_string") val dateString: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "primary_identifier") val primaryIdentifier: String,
    @ColumnInfo(name = "secondary_identifier") val secondaryIdentifier: String,
    @ColumnInfo(name = "auxiliary_identifier") val auxiliaryIdentifier: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long
) {
    fun matches(fingerprint: SemanticFingerprint): Boolean =
        SemanticFingerprint(primaryIdentifier, secondaryIdentifier, auxiliaryIdentifier, platform)
            .isSameAs(fingerprint)

    companion object {
        fun from(
            accountScope: String,
            dateString: String,
            fingerprint: SemanticFingerprint,
            updatedAtMs: Long
        ) = AcceptedReelTransitionState(
            accountScope = accountScope,
            dateString = dateString,
            platform = fingerprint.platform,
            primaryIdentifier = fingerprint.primaryIdentifier,
            secondaryIdentifier = fingerprint.secondaryIdentifier,
            auxiliaryIdentifier = fingerprint.auxiliaryIdentifier,
            updatedAtMs = updatedAtMs
        )
    }
}

/** Stable, non-reversible account namespace for local transition idempotency records. */
object ReelCountAccountScope {
    fun fromUid(uid: String?): String {
        if (uid.isNullOrBlank()) return "local"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
