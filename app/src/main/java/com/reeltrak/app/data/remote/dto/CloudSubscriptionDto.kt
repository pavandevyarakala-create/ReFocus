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
        val rawStatusUpper = subscriptionStatus.trim().uppercase()
        val status = when (rawStatusUpper) {
            "ACTIVE", "PREMIUM", "PRO", "PAID" -> SubscriptionStatus.ACTIVE
            "TRIAL" -> SubscriptionStatus.TRIAL
            "PENDING" -> SubscriptionStatus.PENDING
            "CANCELLED", "CANCELED" -> SubscriptionStatus.CANCELLED
            "EXPIRED" -> SubscriptionStatus.EXPIRED
            "HALTED" -> SubscriptionStatus.HALTED
            "PAST_DUE" -> SubscriptionStatus.PAST_DUE
            else -> try {
                SubscriptionStatus.valueOf(rawStatusUpper)
            } catch (e: Exception) {
                SubscriptionStatus.NONE
            }
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

        // Explicit server halt revokes access immediately
        if (status == SubscriptionStatus.HALTED) {
            return SubscriptionInfo(
                status = SubscriptionStatus.HALTED,
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
        // Paid is active if future endMillis exists (regardless of whether raw doc had EXPIRED/NONE/TRIAL/PREMIUM etc., except HALTED).
        // If endMillis is null, it fails closed to prevent unanchored privilege escalation in tampered documents.
        val hasValidPaidEnd = endMillis != null && endMillis > 0L && effectiveNow < endMillis
        val isPaidActive = hasValidPaidEnd

        // 2. Check 7-Day Trial validity
        // Authoritative check: trial is active if paid is not active and trialEnd is in future
        val hasValidTrialEnd = trialEndMillis != null && trialEndMillis > 0L && effectiveNow < trialEndMillis
        val isTrialActive = !isPaidActive && hasValidTrialEnd

        // 3. Status reconciliation for contradictory server states
        // - Preserve PENDING if paid period is still unexpired (grace period)
        // - Preserve CANCELLED if cancelled at period end
        // - Reconcile contradictory server state (e.g. doc had EXPIRED/NONE/TRIAL) to ACTIVE when paid end is in future
        val reconciledStatus = when {
            status == SubscriptionStatus.PENDING && isPaidActive -> SubscriptionStatus.PENDING
            (status == SubscriptionStatus.CANCELLED || cancelAtPeriodEnd) && isPaidActive -> SubscriptionStatus.CANCELLED
            isPaidActive -> SubscriptionStatus.ACTIVE
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

        val resolvedPlan = if (isPaidActive && plan == SubscriptionPlan.FREE) {
            SubscriptionPlan.MONTHLY
        } else {
            plan
        }

        return SubscriptionInfo(
            status = reconciledStatus,
            plan = resolvedPlan,
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
         * Safely parse a dynamic value into com.google.firebase.Timestamp handling:
         * - com.google.firebase.Timestamp objects
         * - java.util.Date objects
         * - Numeric epoch Longs/Numbers (seconds or milliseconds)
         * - ISO-8601 strings, numeric strings, and standard date strings
         * - Firestore REST API Map representation ({"seconds": ..., "nanoseconds": ...})
         */
        fun parseTimestamp(value: Any?): Timestamp? {
            if (value == null) return null
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
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) return null
                    try {
                        val instant = Instant.parse(trimmed)
                        return Timestamp(Date(instant.toEpochMilli()))
                    } catch (_: Exception) {}
                    try {
                        val num = trimmed.toLong()
                        val ms = if (num in 1L..99_999_999_999L) num * 1000L else num
                        return Timestamp(Date(ms))
                    } catch (_: Exception) {}
                    val formats = listOf(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                        "yyyy-MM-dd'T'HH:mm:ssX",
                        "yyyy-MM-dd HH:mm:ss",
                        "yyyy-MM-dd"
                    )
                    for (fmt in formats) {
                        try {
                            val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            val parsed = sdf.parse(trimmed)
                            if (parsed != null) return Timestamp(parsed)
                        } catch (_: Exception) {}
                    }
                    null
                }
                is Map<*, *> -> {
                    val secVal = value["seconds"] ?: value["_seconds"]
                    val nanoVal = value["nanoseconds"] ?: value["_nanoseconds"]
                    if (secVal is Number) {
                        val sec = secVal.toLong()
                        val nano = (nanoVal as? Number)?.toInt() ?: 0
                        return Timestamp(sec, nano)
                    }
                    val tsVal = value["timestampValue"]
                    if (tsVal != null) return parseTimestamp(tsVal)
                    val intVal = value["integerValue"]
                    if (intVal != null) return parseTimestamp(intVal)
                    val timeVal = value["time"]
                    if (timeVal != null) return parseTimestamp(timeVal)
                    null
                }
                else -> null
            }
        }

        /**
         * Safely parse a Map of Firestore document fields into CloudSubscriptionDto.
         */
        fun fromMap(data: Map<String, Any?>): CloudSubscriptionDto {
            fun extractTimestamp(vararg fieldNames: String): Timestamp? {
                for (name in fieldNames) {
                    val raw = data[name]
                    val ts = parseTimestamp(raw)
                    if (ts != null) return ts
                }
                return null
            }

            fun extractString(vararg fieldNames: String): String? {
                for (name in fieldNames) {
                    val raw = data[name]
                    if (raw is String && raw.isNotBlank()) return raw
                    if (raw != null && raw !is Map<*, *> && raw !is List<*>) {
                        val str = raw.toString()
                        if (str.isNotBlank()) return str
                    }
                }
                return null
            }

            fun extractBoolean(vararg fieldNames: String): Boolean {
                for (name in fieldNames) {
                    val raw = data[name]
                    if (raw is Boolean) return raw
                    if (raw is String) {
                        if (raw.equals("true", ignoreCase = true)) return true
                        if (raw.equals("false", ignoreCase = true)) return false
                    }
                }
                return false
            }

            return CloudSubscriptionDto(
                subscriptionStatus = extractString("subscriptionStatus", "status", "subscription_status", "state") ?: SubscriptionStatus.NONE.name,
                planId = extractString("planId", "plan_id", "plan"),
                subscriptionId = extractString("subscriptionId", "subscription_id", "subId", "sub_id"),
                latestPaymentId = extractString("latestPaymentId", "latest_payment_id", "paymentId", "payment_id"),
                currentPeriodStart = extractTimestamp(
                    "currentPeriodStart", "currentPeriodStartDate", "current_period_start", "current_period_start_date",
                    "startDate", "start_date", "periodStart", "period_start"
                ),
                currentPeriodEnd = extractTimestamp(
                    "currentPeriodEnd", "currentPeriodEndDate", "current_period_end", "current_period_end_date",
                    "expiryDate", "expiry_date", "expiresAt", "expires_at",
                    "expirationDate", "expiration_date", "expirationTime", "expiration_time",
                    "premiumExpiry", "premium_expiry", "premiumUntil", "premium_until",
                    "validUntil", "valid_until", "subscriptionEndDate", "subscription_end_date",
                    "periodEnd", "period_end", "endDate", "end_date"
                ),
                trialStart = extractTimestamp(
                    "trialStart", "trialStartDate", "trial_start", "trial_start_date"
                ),
                trialEnd = extractTimestamp(
                    "trialEnd", "trialEndDate", "trial_end", "trial_end_date",
                    "trialExpiresAt", "trial_expires_at", "trialExpiry", "trial_expiry"
                ),
                trialStartDate = extractTimestamp("trialStartDate", "trial_start_date"),
                trialEndDate = extractTimestamp("trialEndDate", "trial_end_date"),
                cancelAtPeriodEnd = extractBoolean("cancelAtPeriodEnd", "cancel_at_period_end", "canceled", "cancelled"),
                nextBillingDate = extractTimestamp(
                    "nextBillingDate", "next_billing_date", "nextBilling", "next_billing", "chargeAt", "charge_at"
                ),
                lastWebhookEventId = extractString("lastWebhookEventId", "last_webhook_event_id", "eventId", "event_id"),
                updatedAt = extractTimestamp("updatedAt", "updated_at", "lastUpdated", "last_updated"),
                premium = extractBoolean("premium", "isPremium", "is_premium", "pro", "isPro", "is_pro"),
                mandateStatus = extractString("mandateStatus", "mandate_status")
            )
        }

        /**
         * Safely parse a DocumentSnapshot into CloudSubscriptionDto handling:
         * - com.google.firebase.Timestamp objects
         * - java.util.Date objects
         * - Legacy numeric epoch Longs (seconds or milliseconds)
         * - ISO-8601 strings and standard date strings
         * - Firestore REST API Map representation ({"seconds": ..., "nanoseconds": ...})
         * - Aliased field names across backend/admin schemas
         */
        fun fromSnapshot(snapshot: DocumentSnapshot): CloudSubscriptionDto {
            val data = snapshot.data
            if (data != null) {
                return fromMap(data)
            }

            fun extractTimestamp(vararg fieldNames: String): Timestamp? {
                for (name in fieldNames) {
                    val raw = snapshot.get(name)
                    val ts = parseTimestamp(raw)
                    if (ts != null) return ts
                }
                return null
            }

            fun extractString(vararg fieldNames: String): String? {
                for (name in fieldNames) {
                    val raw = snapshot.getString(name)
                    if (!raw.isNullOrBlank()) return raw
                    val anyRaw = snapshot.get(name)
                    if (anyRaw is String && anyRaw.isNotBlank()) return anyRaw
                }
                return null
            }

            fun extractBoolean(vararg fieldNames: String): Boolean {
                for (name in fieldNames) {
                    val boolVal = snapshot.getBoolean(name)
                    if (boolVal != null) return boolVal
                    val anyVal = snapshot.get(name)
                    if (anyVal is Boolean) return anyVal
                    if (anyVal is String) {
                        if (anyVal.equals("true", ignoreCase = true)) return true
                        if (anyVal.equals("false", ignoreCase = true)) return false
                    }
                }
                return false
            }

            return CloudSubscriptionDto(
                subscriptionStatus = extractString("subscriptionStatus", "status", "subscription_status", "state") ?: SubscriptionStatus.NONE.name,
                planId = extractString("planId", "plan_id", "plan"),
                subscriptionId = extractString("subscriptionId", "subscription_id", "subId", "sub_id"),
                latestPaymentId = extractString("latestPaymentId", "latest_payment_id", "paymentId", "payment_id"),
                currentPeriodStart = extractTimestamp(
                    "currentPeriodStart", "currentPeriodStartDate", "current_period_start", "current_period_start_date",
                    "startDate", "start_date", "periodStart", "period_start"
                ),
                currentPeriodEnd = extractTimestamp(
                    "currentPeriodEnd", "currentPeriodEndDate", "current_period_end", "current_period_end_date",
                    "expiryDate", "expiry_date", "expiresAt", "expires_at",
                    "expirationDate", "expiration_date", "expirationTime", "expiration_time",
                    "premiumExpiry", "premium_expiry", "premiumUntil", "premium_until",
                    "validUntil", "valid_until", "subscriptionEndDate", "subscription_end_date",
                    "periodEnd", "period_end", "endDate", "end_date"
                ),
                trialStart = extractTimestamp(
                    "trialStart", "trialStartDate", "trial_start", "trial_start_date"
                ),
                trialEnd = extractTimestamp(
                    "trialEnd", "trialEndDate", "trial_end", "trial_end_date",
                    "trialExpiresAt", "trial_expires_at", "trialExpiry", "trial_expiry"
                ),
                trialStartDate = extractTimestamp("trialStartDate", "trial_start_date"),
                trialEndDate = extractTimestamp("trialEndDate", "trial_end_date"),
                cancelAtPeriodEnd = extractBoolean("cancelAtPeriodEnd", "cancel_at_period_end", "canceled", "cancelled"),
                nextBillingDate = extractTimestamp(
                    "nextBillingDate", "next_billing_date", "nextBilling", "next_billing", "chargeAt", "charge_at"
                ),
                lastWebhookEventId = extractString("lastWebhookEventId", "last_webhook_event_id", "eventId", "event_id"),
                updatedAt = extractTimestamp("updatedAt", "updated_at", "lastUpdated", "last_updated"),
                premium = extractBoolean("premium", "isPremium", "is_premium", "pro", "isPro", "is_pro"),
                mandateStatus = extractString("mandateStatus", "mandate_status")
            )
        }
    }
}
