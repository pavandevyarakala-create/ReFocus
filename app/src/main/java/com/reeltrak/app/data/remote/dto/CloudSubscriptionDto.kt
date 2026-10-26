package com.reeltrak.app.data.remote.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionStatus
import java.time.Instant
import java.util.Date

/**
 * Firestore Data Transfer Object (DTO) for reading the user's subscription & trial metadata.
 * Stored at path: /users/{uid}/subscription/status
 *
 * NOTE: Written exclusively by backend server / webhooks (Firebase Admin / Cloud Functions) or mock simulator in dev.
 * The Android client deserializes this document.
 */
data class CloudSubscriptionDto(
    @get:PropertyName("subscriptionStatus")
    @set:PropertyName("subscriptionStatus")
    var subscriptionStatus: String = SubscriptionStatus.NONE.name,

    @get:PropertyName("planId")
    @set:PropertyName("planId")
    var planId: String? = null,

    @get:PropertyName("subscriptionId")
    @set:PropertyName("subscriptionId")
    var subscriptionId: String? = null,

    @get:PropertyName("latestPaymentId")
    @set:PropertyName("latestPaymentId")
    var latestPaymentId: String? = null,

    @get:PropertyName("currentPeriodStart")
    @set:PropertyName("currentPeriodStart")
    var currentPeriodStart: Timestamp? = null,

    @get:PropertyName("currentPeriodEnd")
    @set:PropertyName("currentPeriodEnd")
    var currentPeriodEnd: Timestamp? = null,

    @get:PropertyName("trialStart")
    @set:PropertyName("trialStart")
    var trialStart: Timestamp? = null,

    @get:PropertyName("trialEnd")
    @set:PropertyName("trialEnd")
    var trialEnd: Timestamp? = null,

    @get:PropertyName("trialStartDate")
    @set:PropertyName("trialStartDate")
    var trialStartDate: Timestamp? = null,

    @get:PropertyName("trialEndDate")
    @set:PropertyName("trialEndDate")
    var trialEndDate: Timestamp? = null,

    @get:PropertyName("cancelAtPeriodEnd")
    @set:PropertyName("cancelAtPeriodEnd")
    var cancelAtPeriodEnd: Boolean = false,

    @get:PropertyName("nextBillingDate")
    @set:PropertyName("nextBillingDate")
    var nextBillingDate: Timestamp? = null,

    @get:PropertyName("lastWebhookEventId")
    @set:PropertyName("lastWebhookEventId")
    var lastWebhookEventId: String? = null,

    @get:PropertyName("updatedAt")
    @set:PropertyName("updatedAt")
    var updatedAt: Timestamp? = null,

    @get:PropertyName("premium")
    @set:PropertyName("premium")
    var premium: Boolean = false,

    @get:PropertyName("mandateStatus")
    @set:PropertyName("mandateStatus")
    var mandateStatus: String? = null
) {
    /**
     * Converts to domain SubscriptionInfo evaluating access against the authoritative server-anchored timestamp.
     * 
     * Security Evaluation:
     * 1. If status is EXPIRED, HALTED, or NONE -> strictly LOCKED (or FINAL_SYNC_GRACE if within 2 minutes of expiration).
     * 2. If isOfflineLeaseExpired is true -> strictly LOCKED (fails closed to prevent indefinite offline cache theft).
     * 3. TRIAL: Active if authoritativeNow < effectiveTrialEnd.
     * 4. ACTIVE: Active if authoritativeNow < endMillis (regardless of cancelAtPeriodEnd status until end).
     * 5. Post-Expiration Reconciliation: If authoritativeNow is within 2 minutes (120s) after expiration,
     *    transitions to AccessState.FINAL_SYNC_GRACE (reels locked, sync permitted).
     */
    fun toDomain(
        authoritativeNow: Long = 0L,
        isOfflineLeaseExpired: Boolean = false,
        lastServerVerificationMs: Long = 0L,
        trustedNow: Long? = null
    ): SubscriptionInfo {
        val nowCandidate = if (trustedNow != null && trustedNow > 0L) trustedNow else authoritativeNow
        val effectiveNow = if (nowCandidate > 0L) nowCandidate else System.currentTimeMillis()
        val status = try {
            SubscriptionStatus.valueOf(subscriptionStatus.uppercase())
        } catch (e: Exception) {
            SubscriptionStatus.NONE
        }

        val plan = SubscriptionPlan.fromId(planId)

        val startMillis = currentPeriodStart?.toDate()?.time
        val endMillis = currentPeriodEnd?.toDate()?.time
        
        // Support both trialStart/trialEnd and trialStartDate/trialEndDate
        val effectiveTrialStart = trialStart ?: trialStartDate
        val effectiveTrialEnd = trialEnd ?: trialEndDate
        val trialStartMillis = effectiveTrialStart?.toDate()?.time
        val trialEndMillis = effectiveTrialEnd?.toDate()?.time

        val nextBillingMillis = nextBillingDate?.toDate()?.time

        // Check if offline lease has expired -> Fail closed immediately
        if (isOfflineLeaseExpired) {
            return SubscriptionInfo(
                status = status,
                plan = plan,
                subscriptionId = subscriptionId,
                latestPaymentId = latestPaymentId,
                currentPeriodStart = startMillis,
                currentPeriodEnd = endMillis,
                trialStart = trialStartMillis,
                trialEnd = trialEndMillis,
                cancelAtPeriodEnd = cancelAtPeriodEnd,
                nextBillingDate = nextBillingMillis,
                accessState = AccessState.LOCKED,
                lastServerVerificationMs = lastServerVerificationMs
            )
        }

        // 1. Check Paid Subscription validity
        val hasValidPaidEnd = endMillis != null && endMillis > 0L && effectiveNow < endMillis
        val isPaidActive = (status == SubscriptionStatus.ACTIVE || 
                            status == SubscriptionStatus.PENDING || 
                            status == SubscriptionStatus.CANCELLED) && hasValidPaidEnd

        // 2. Check 7-Day Trial validity
        // Authoritative check: trial is active if paid is not active and trialEnd is in future
        val isTrialActive = !isPaidActive && trialEndMillis != null && trialEndMillis > 0L && effectiveNow < trialEndMillis

        // 3. Status reconciliation for contradictory server states
        // If trialEnd is in future, status resolves to TRIAL even if raw doc had EXPIRED or NONE
        val reconciledStatus = when {
            isPaidActive -> status
            isTrialActive -> SubscriptionStatus.TRIAL
            else -> status
        }

        // 4. Check 2-Minute Final-Sync Grace Window (Exactly 120 seconds post natural expiration)
        // Allowed ONLY if status is ACTIVE / TRIAL with natural expiration, NEVER on EXPIRED, HALTED, or CANCELLED/REFUNDED
        val FINAL_SYNC_WINDOW_MS = 2 * 60 * 1000L
        val isEligibleForGrace = reconciledStatus != SubscriptionStatus.EXPIRED && 
                                 reconciledStatus != SubscriptionStatus.HALTED && 
                                 reconciledStatus != SubscriptionStatus.CANCELLED
        val isWithinPaidGrace = isEligibleForGrace && cancelAtPeriodEnd && endMillis != null && endMillis > 0L &&
                effectiveNow >= endMillis && effectiveNow < (endMillis + FINAL_SYNC_WINDOW_MS)
        val isWithinTrialGrace = isEligibleForGrace && (reconciledStatus == SubscriptionStatus.TRIAL || reconciledStatus == SubscriptionStatus.NONE) &&
                trialEndMillis != null && trialEndMillis > 0L &&
                effectiveNow >= trialEndMillis && effectiveNow < (trialEndMillis + FINAL_SYNC_WINDOW_MS)

        // 5. Derive Centralized AccessState (Priority: Paid -> Trial -> FinalSyncGrace -> Locked)
        val derivedAccessState = when {
            isPaidActive -> AccessState.ACTIVE
            isTrialActive -> AccessState.TRIAL
            isWithinPaidGrace || isWithinTrialGrace -> AccessState.FINAL_SYNC_GRACE
            else -> AccessState.LOCKED
        }

        return SubscriptionInfo(
            status = reconciledStatus,
            plan = plan,
            subscriptionId = subscriptionId,
            latestPaymentId = latestPaymentId,
            currentPeriodStart = startMillis,
            currentPeriodEnd = endMillis,
            trialStart = trialStartMillis,
            trialEnd = trialEndMillis,
            cancelAtPeriodEnd = cancelAtPeriodEnd,
            nextBillingDate = nextBillingMillis,
            accessState = derivedAccessState,
            lastServerVerificationMs = lastServerVerificationMs
        )
    }

    companion object {
        /**
         * Safely parse a DocumentSnapshot into CloudSubscriptionDto handling both
         * com.google.firebase.Timestamp objects and legacy numeric epoch Longs.
         */
        fun fromSnapshot(snapshot: DocumentSnapshot): CloudSubscriptionDto {
            fun extractTimestamp(fieldName: String): Timestamp? {
                val value = snapshot.get(fieldName) ?: return null
                return when (value) {
                    is Timestamp -> value
                    is Date -> Timestamp(value)
                    is Number -> {
                        val num = value.toLong()
                        // If in seconds (e.g. 1_700_000_000), convert to milliseconds
                        val ms = if (num in 1L..99_999_999_999L) num * 1000L else num
                        Timestamp(Date(ms))
                    }
                    is String -> {
                        try {
                            val instant = Instant.parse(value)
                            Timestamp(Date(instant.toEpochMilli()))
                        } catch (e: Exception) {
                            try {
                                val num = value.toLong()
                                val ms = if (num in 1L..99_999_999_999L) num * 1000L else num
                                Timestamp(Date(ms))
                            } catch (e2: Exception) {
                                null
                            }
                        }
                    }
                    else -> null
                }
            }

            return CloudSubscriptionDto(
                subscriptionStatus = snapshot.getString("subscriptionStatus") ?: snapshot.getString("status") ?: SubscriptionStatus.NONE.name,
                planId = snapshot.getString("planId"),
                subscriptionId = snapshot.getString("subscriptionId"),
                latestPaymentId = snapshot.getString("latestPaymentId"),
                currentPeriodStart = extractTimestamp("currentPeriodStart") ?: extractTimestamp("currentPeriodStartDate") ?: extractTimestamp("current_period_start"),
                currentPeriodEnd = extractTimestamp("currentPeriodEnd") ?: extractTimestamp("currentPeriodEndDate") ?: extractTimestamp("current_period_end"),
                trialStart = extractTimestamp("trialStart") ?: extractTimestamp("trialStartDate") ?: extractTimestamp("trial_start") ?: extractTimestamp("trial_start_date"),
                trialEnd = extractTimestamp("trialEnd") ?: extractTimestamp("trialEndDate") ?: extractTimestamp("trial_end") ?: extractTimestamp("trial_end_date"),
                trialStartDate = extractTimestamp("trialStartDate"),
                trialEndDate = extractTimestamp("trialEndDate"),
                cancelAtPeriodEnd = snapshot.getBoolean("cancelAtPeriodEnd") ?: false,
                nextBillingDate = extractTimestamp("nextBillingDate"),
                lastWebhookEventId = snapshot.getString("lastWebhookEventId"),
                updatedAt = extractTimestamp("updatedAt"),
                premium = snapshot.getBoolean("premium") ?: snapshot.getBoolean("isPremium") ?: false,
                mandateStatus = snapshot.getString("mandateStatus")
            )
        }
    }
}
