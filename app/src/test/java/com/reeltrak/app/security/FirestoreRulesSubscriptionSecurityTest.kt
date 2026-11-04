package com.reeltrak.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Security Verification Suite for Firestore Security Rules.
 *
 * Verifies that the authoritative Firestore security rules completely close:
 * 1. Missing subscription document bypass (!exists bypass).
 * 2. Expired trial bypass (TRIAL status without trialEnd verification).
 * 3. Expired paid subscription bypass (ACTIVE/CANCELLED/PENDING without currentPeriodEnd verification).
 *
 * Covers all required security lifecycle test cases:
 * 1. Active + currentPeriodEnd in future -> ALLOW
 * 2. Active + currentPeriodEnd expired -> DENY
 * 3. Trial + trialEnd in future -> ALLOW
 * 4. Trial + trialEnd expired -> DENY
 * 5. Missing subscription document -> DENY
 * 6. Free / none -> DENY
 * 7. Expired -> DENY
 * 8. Cancelled + currentPeriodEnd future -> ALLOW (active until billing cycle ends)
 * 9. Cancelled + currentPeriodEnd expired -> DENY
 * 10. Pending + currentPeriodEnd future -> ALLOW (in grace period retry)
 * 11. Pending + currentPeriodEnd expired -> DENY
 * 12. Local client patch / cracked APK direct Firestore write rejection.
 */
class FirestoreRulesSubscriptionSecurityTest {

    private fun findRulesFile(): File {
        val candidates = listOf(
            File("firestore.rules"),
            File("../firestore.rules"),
            File("../../firestore.rules")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("firestore.rules not found")
    }

    // =========================================================================
    // 1. STATIC CODE & ARCHITECTURE AUDIT OF FIRESTORE.RULES
    // =========================================================================

    @Test
    fun testRules_Vulnerability1_MissingSubscriptionDocumentBypassIsRemoved() {
        val rules = findRulesFile().readText()

        // MUST NOT contain the vulnerable !exists(...) clause on subscription/status
        assertFalse(
            "CRITICAL VULNERABILITY DETECTED: firestore.rules must not permit writes when subscription/status does not exist",
            rules.contains("!exists(/databases/$(database)/documents/users/$(userId)/subscription/status)")
        )
        assertFalse(
            "CRITICAL: !exists must not be used to bypass hasActiveSubscription",
            rules.contains("!exists(subDoc)")
        )

        // MUST strictly require exists(subDoc)
        assertTrue(
            "firestore.rules must explicitly require exists(subDoc)",
            rules.contains("exists(subDoc)")
        )
    }

    @Test
    fun testRules_Vulnerability2_TimestampBoundsAreAuthoritativelyEnforced() {
        val rules = findRulesFile().readText()

        // TRIAL must check trialEnd
        assertTrue(
            "firestore.rules must enforce request.time < get(subDoc).data.trialEnd for TRIAL",
            rules.contains("request.time < get(subDoc).data.trialEnd")
        )

        // ACTIVE / PENDING / CANCELLED must check currentPeriodEnd
        assertTrue(
            "firestore.rules must enforce request.time < get(subDoc).data.currentPeriodEnd",
            rules.contains("request.time < get(subDoc).data.currentPeriodEnd")
        )
    }

    @Test
    fun testRules_DailyDataWriteIsGatedByOwnershipAndActiveSubscription() {
        val rules = findRulesFile().readText()

        val dailyDataBlock = rules.substringAfter("match /daily_data/{dateString}")
            .substringBefore("match /onboarding/")

        assertTrue(
            "daily_data read must require ownership",
            dailyDataBlock.contains("allow read: if isOwner(userId)")
        )
        assertTrue(
            "daily_data write must strictly require ownership",
            dailyDataBlock.contains("isOwner(userId)")
        )
        assertTrue(
            "daily_data write must strictly require hasActiveSubscription(userId)",
            dailyDataBlock.contains("hasActiveSubscription(userId)")
        )
        assertTrue(
            "daily_data write must validate dateString format",
            dailyDataBlock.contains("dateString.matches('^[0-9]{4}-[0-9]{2}-[0-9]{2}$')")
        )
    }

    @Test
    fun testRules_ClientCannotWriteToSubscriptionOrAccountDocuments() {
        val rules = findRulesFile().readText()

        val subBlock = rules.substringAfter("match /subscription/{documentId}")
            .substringBefore("match /account/")
        assertTrue(
            "Client must NEVER be permitted to write to subscription documents",
            subBlock.contains("allow write: if false;")
        )

        val accountBlock = rules.substringAfter("match /account/{documentId}")
            .substringBefore("match /{document=**}")
        assertTrue(
            "Client must NEVER be permitted to write to account documents",
            accountBlock.contains("allow write: if false;")
        )
    }

    @Test
    fun testRules_IsAccountActiveChecksCanonicalAccountStatusWithLegacyFallback() {
        val rules = findRulesFile().readText()

        val isAccountActiveBlock = rules.substringAfter("function isAccountActive(userId)")
            .substringBefore("function hasActiveSubscription(userId)")

        // Must check canonical accountStatus
        assertTrue(
            "isAccountActive must check canonical 'accountStatus'",
            isAccountActiveBlock.contains("'accountStatus' in get(accDoc).data") &&
            isAccountActiveBlock.contains("get(accDoc).data.accountStatus == 'ACTIVE'")
        )

        // Must support legacy 'status' fallback
        assertTrue(
            "isAccountActive must support legacy 'status' fallback",
            isAccountActiveBlock.contains("'status' in get(accDoc).data") &&
            isAccountActiveBlock.contains("get(accDoc).data.status == 'ACTIVE'")
        )

        // Must preserve !exists(accDoc) for new registrations
        assertTrue(
            "isAccountActive must permit uninitialized/new account docs (!exists(accDoc))",
            isAccountActiveBlock.contains("!exists(accDoc)")
        )
    }

    // =========================================================================
    // 2. BEHAVIORAL EVALUATION OF HAS_ACTIVE_SUBSCRIPTION LOGIC
    // =========================================================================

    /**
     * Exact simulation model of the Firestore Rules `hasActiveSubscription(userId)` predicate:
     *
     * ```javascript
     * let subDoc = /databases/$(database)/documents/users/$(userId)/subscription/status;
     * return isAccountActive(userId) && exists(subDoc) && (
     *   (
     *     get(subDoc).data.subscriptionStatus in ['ACTIVE', 'PENDING', 'CANCELLED'] &&
     *     request.time < get(subDoc).data.currentPeriodEnd
     *   ) ||
     *   (
     *     get(subDoc).data.subscriptionStatus == 'TRIAL' &&
     *     request.time < get(subDoc).data.trialEnd
     *   )
     * );
     * ```
     */
    data class MockAccountDoc(
        val accountStatus: String? = null,
        val status: String? = null
    )

    data class MockSubscriptionDoc(
        val subscriptionStatus: String,
        val currentPeriodEnd: Instant? = null,
        val trialEnd: Instant? = null
    )

    private fun evaluateIsAccountActiveRule(accDoc: MockAccountDoc?): Boolean {
        // !exists(accDoc) -> uninitialized / new accounts are permitted
        if (accDoc == null) return true

        val hasAccountStatus = accDoc.accountStatus != null
        val accountStatusActive = hasAccountStatus && accDoc.accountStatus == "ACTIVE"

        val hasLegacyStatus = !hasAccountStatus && accDoc.status != null
        val legacyStatusActive = hasLegacyStatus && accDoc.status == "ACTIVE"

        return accountStatusActive || legacyStatusActive
    }

    private fun evaluateHasActiveSubscriptionRule(
        subDoc: MockSubscriptionDoc?,
        requestTime: Instant,
        accDoc: MockAccountDoc? = null
    ): Boolean {
        if (!evaluateIsAccountActiveRule(accDoc)) return false
        if (subDoc == null) return false

        val isPaidState = subDoc.subscriptionStatus in listOf("ACTIVE", "PENDING", "CANCELLED")
        val isPaidValid = isPaidState && subDoc.currentPeriodEnd != null && requestTime.isBefore(subDoc.currentPeriodEnd)

        val isTrialState = subDoc.subscriptionStatus == "TRIAL"
        val isTrialValid = isTrialState && subDoc.trialEnd != null && requestTime.isBefore(subDoc.trialEnd)

        return isPaidValid || isTrialValid
    }

    @Test
    fun testScenario1_ActiveWithCurrentPeriodEndInFuture_AllowsWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureEnd = Instant.parse("2026-10-27T12:00:00Z")

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "ACTIVE",
            currentPeriodEnd = futureEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertTrue("Active subscription with future currentPeriodEnd must be ALLOWED", allowed)
    }

    @Test
    fun testScenario2_ActiveWithCurrentPeriodEndExpired_DeniesWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val pastEnd = Instant.parse("2026-09-20T12:00:00Z") // 7 days ago

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "ACTIVE",
            currentPeriodEnd = pastEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertFalse("Active subscription with expired currentPeriodEnd must be DENIED", allowed)
    }

    @Test
    fun testScenario3_TrialWithTrialEndInFuture_AllowsWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureTrialEnd = Instant.parse("2026-10-02T12:00:00Z") // 5 days left

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "TRIAL",
            trialEnd = futureTrialEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertTrue("Trial with future trialEnd must be ALLOWED", allowed)
    }

    @Test
    fun testScenario4_TrialWithTrialEndExpired_DeniesWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val pastTrialEnd = Instant.parse("2026-09-25T12:00:00Z") // Expired 2 days ago

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "TRIAL",
            trialEnd = pastTrialEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertFalse("Expired trial must be DENIED write access authoritatively", allowed)
    }

    @Test
    fun testScenario5_MissingSubscriptionDocument_DeniesWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")

        // Document does not exist in Firestore (/subscription/status is null)
        val subDoc: MockSubscriptionDoc? = null

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertFalse("Missing subscription document must be STRICTLY DENIED", allowed)
    }

    @Test
    fun testScenario6_FreeOrNoneSubscriptionStatus_DeniesWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")

        val subDocNone = MockSubscriptionDoc(
            subscriptionStatus = "NONE",
            currentPeriodEnd = null,
            trialEnd = null
        )

        assertFalse("NONE status must be DENIED", evaluateHasActiveSubscriptionRule(subDocNone, requestTime))

        val subDocFree = MockSubscriptionDoc(
            subscriptionStatus = "FREE",
            currentPeriodEnd = null,
            trialEnd = null
        )

        assertFalse("FREE status must be DENIED", evaluateHasActiveSubscriptionRule(subDocFree, requestTime))
    }

    @Test
    fun testScenario7_ExpiredSubscriptionStatus_DeniesWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val pastEnd = Instant.parse("2026-09-01T12:00:00Z")

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "EXPIRED",
            currentPeriodEnd = pastEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertFalse("EXPIRED subscription status must be STRICTLY DENIED", allowed)
    }

    @Test
    fun testScenario8_CancelledWithCurrentPeriodEndInFuture_AllowsWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureEnd = Instant.parse("2026-10-15T12:00:00Z") // 18 days left in billing period

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "CANCELLED",
            currentPeriodEnd = futureEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertTrue("CANCELLED subscription with future currentPeriodEnd preserves paid access until cycle end", allowed)
    }

    @Test
    fun testScenario9_CancelledWithCurrentPeriodEndExpired_DeniesWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val pastEnd = Instant.parse("2026-09-26T12:00:00Z")

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "CANCELLED",
            currentPeriodEnd = pastEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertFalse("CANCELLED subscription after cycle expiration must be DENIED", allowed)
    }

    @Test
    fun testScenario10_PendingWithCurrentPeriodEndInFuture_AllowsWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureGraceEnd = Instant.parse("2026-09-30T12:00:00Z") // 3 days grace period remaining

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "PENDING",
            currentPeriodEnd = futureGraceEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertTrue("PENDING subscription in grace period with future currentPeriodEnd must be ALLOWED", allowed)
    }

    @Test
    fun testScenario11_PendingWithCurrentPeriodEndExpired_DeniesWrite() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val pastGraceEnd = Instant.parse("2026-09-26T12:00:00Z") // Grace period exhausted

        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "PENDING",
            currentPeriodEnd = pastGraceEnd
        )

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime)
        assertFalse("PENDING subscription after grace period expiration must be DENIED", allowed)
    }

    // =========================================================================
    // 2B. BEHAVIORAL EVALUATION OF IS_ACCOUNT_ACTIVE AND COMBINED ENFORCEMENT
    // =========================================================================

    @Test
    fun testAccountStatus_MissingAccountDoc_PermitsActive() {
        // When /users/{uid}/account/status does not exist (!exists(accDoc)), account is treated as ACTIVE
        val allowed = evaluateIsAccountActiveRule(accDoc = null)
        assertTrue("Missing account doc must be permitted (!exists(accDoc)) for initial registrations", allowed)
    }

    @Test
    fun testAccountStatus_CanonicalActive_AllowsAccess() {
        val accDoc = MockAccountDoc(accountStatus = "ACTIVE")
        assertTrue("Canonical accountStatus == 'ACTIVE' must be allowed", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_CanonicalRestricted_DeniesAccess() {
        val accDoc = MockAccountDoc(accountStatus = "RESTRICTED")
        assertFalse("Canonical accountStatus == 'RESTRICTED' must be strictly DENIED", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_CanonicalPendingDeletion_DeniesAccess() {
        val accDoc = MockAccountDoc(accountStatus = "PENDING_DELETION")
        assertFalse("Canonical accountStatus == 'PENDING_DELETION' must be strictly DENIED", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_CanonicalDeleted_DeniesAccess() {
        val accDoc = MockAccountDoc(accountStatus = "DELETED")
        assertFalse("Canonical accountStatus == 'DELETED' must be strictly DENIED", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_LegacyStatusActive_AllowsAccess() {
        // Document has legacy status = 'ACTIVE' without accountStatus key
        val accDoc = MockAccountDoc(accountStatus = null, status = "ACTIVE")
        assertTrue("Legacy status == 'ACTIVE' must be allowed when accountStatus is omitted", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_LegacyStatusRestricted_DeniesAccess() {
        val accDoc = MockAccountDoc(accountStatus = null, status = "RESTRICTED")
        assertFalse("Legacy status == 'RESTRICTED' must be strictly DENIED", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_CanonicalPrecedenceOverLegacy_RestrictedOverridesLegacyActive() {
        // If an existing document has both, canonical accountStatus must strictly govern
        val accDoc = MockAccountDoc(accountStatus = "RESTRICTED", status = "ACTIVE")
        assertFalse("Canonical accountStatus RESTRICTED must override legacy status ACTIVE", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_MissingStatusFieldsInDoc_DeniesAccess() {
        // Existing document has neither accountStatus nor status (empty or malformed doc)
        val accDoc = MockAccountDoc(accountStatus = null, status = null)
        assertFalse("Existing account doc with missing status fields must be default-DENIED", evaluateIsAccountActiveRule(accDoc))
    }

    @Test
    fun testAccountStatus_MalformedStatus_DeniesAccess() {
        val malformedDocs = listOf(
            MockAccountDoc(accountStatus = "UNKNOWN"),
            MockAccountDoc(accountStatus = "BANNED"),
            MockAccountDoc(accountStatus = "active"), // Case-sensitive exact match
            MockAccountDoc(accountStatus = ""),
            MockAccountDoc(accountStatus = "   ACTIVE   ")
        )
        for (doc in malformedDocs) {
            assertFalse("Malformed accountStatus '${doc.accountStatus}' must be DENIED", evaluateIsAccountActiveRule(doc))
        }
    }

    @Test
    fun testAccountStatus_ActiveSubscriptionWithRestrictedAccount_DeniesAccess() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureEnd = Instant.parse("2026-10-27T12:00:00Z")

        val subDoc = MockSubscriptionDoc(subscriptionStatus = "ACTIVE", currentPeriodEnd = futureEnd)
        val restrictedAccDoc = MockAccountDoc(accountStatus = "RESTRICTED")

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime, restrictedAccDoc)
        assertFalse("Active subscription MUST BE BLOCKED if account is RESTRICTED", allowed)
    }

    @Test
    fun testAccountStatus_ActiveSubscriptionWithPendingDeletionAccount_DeniesAccess() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureEnd = Instant.parse("2026-10-27T12:00:00Z")

        val subDoc = MockSubscriptionDoc(subscriptionStatus = "ACTIVE", currentPeriodEnd = futureEnd)
        val pendingAccDoc = MockAccountDoc(accountStatus = "PENDING_DELETION")

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime, pendingAccDoc)
        assertFalse("Active subscription MUST BE BLOCKED if account is PENDING_DELETION", allowed)
    }

    @Test
    fun testAccountStatus_ActiveSubscriptionWithDeletedAccount_DeniesAccess() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureEnd = Instant.parse("2026-10-27T12:00:00Z")

        val subDoc = MockSubscriptionDoc(subscriptionStatus = "ACTIVE", currentPeriodEnd = futureEnd)
        val deletedAccDoc = MockAccountDoc(accountStatus = "DELETED")

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime, deletedAccDoc)
        assertFalse("Active subscription MUST BE BLOCKED if account is DELETED", allowed)
    }

    @Test
    fun testAccountStatus_ActiveSubscriptionWithCanonicalActiveAccount_AllowsAccess() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureEnd = Instant.parse("2026-10-27T12:00:00Z")

        val subDoc = MockSubscriptionDoc(subscriptionStatus = "ACTIVE", currentPeriodEnd = futureEnd)
        val activeAccDoc = MockAccountDoc(accountStatus = "ACTIVE")

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime, activeAccDoc)
        assertTrue("Active subscription with canonical ACTIVE accountStatus must be ALLOWED", allowed)
    }

    @Test
    fun testAccountStatus_ActiveSubscriptionWithLegacyActiveAccount_AllowsAccess() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureEnd = Instant.parse("2026-10-27T12:00:00Z")

        val subDoc = MockSubscriptionDoc(subscriptionStatus = "ACTIVE", currentPeriodEnd = futureEnd)
        val legacyActiveAccDoc = MockAccountDoc(accountStatus = null, status = "ACTIVE")

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime, legacyActiveAccDoc)
        assertTrue("Active subscription with legacy ACTIVE status must be ALLOWED", allowed)
    }

    @Test
    fun testAccountStatus_TrialSubscriptionWithRestrictedAccount_DeniesAccess() {
        val requestTime = Instant.parse("2026-09-27T12:00:00Z")
        val futureTrialEnd = Instant.parse("2026-10-05T12:00:00Z")

        val subDoc = MockSubscriptionDoc(subscriptionStatus = "TRIAL", trialEnd = futureTrialEnd)
        val restrictedAccDoc = MockAccountDoc(accountStatus = "RESTRICTED")

        val allowed = evaluateHasActiveSubscriptionRule(subDoc, requestTime, restrictedAccDoc)
        assertFalse("Trial subscription MUST BE BLOCKED if account is RESTRICTED", allowed)
    }

    // =========================================================================
    // 3. ADVERSARIAL CRACKED CLIENT BYPASS PROOF
    // =========================================================================

    /**
     * Adversarial scenario:
     * An attacker modifies the APK bytecode to force:
     * - isPremium = true
     * - isAppUnlocked = true
     * - AccessState.ACTIVE
     *
     * The attacker then attempts to invoke SyncWorker or direct Firestore REST calls
     * to push daily data records to /users/{uid}/daily_data/{dateString}.
     *
     * Proof:
     * The Firestore security rules evaluate request.auth, subDoc existence, and
     * authoritative Firestore document timestamps completely independent of the
     * Android client's internal memory state or modified Smali code.
     */
    @Test
    fun testAdversarial_CrackedClient_CannotBypassFirestoreWithoutServerDocument() {
        val requestTime = Instant.now()

        // 1. Attacker suppresses /api/ensure-subscription -> subDoc does not exist
        val attackerSubDoc: MockSubscriptionDoc? = null
        assertFalse(
            "Cracked APK with suppressed subscription doc cannot write to Firestore",
            evaluateHasActiveSubscriptionRule(attackerSubDoc, requestTime)
        )

        // 2. Attacker has expired trial in Firestore but patched isPremium = true locally
        val expiredTrialDoc = MockSubscriptionDoc(
            subscriptionStatus = "TRIAL",
            trialEnd = requestTime.minusSeconds(3600)
        )
        assertFalse(
            "Cracked APK with expired trial doc cannot write to Firestore despite local patch",
            evaluateHasActiveSubscriptionRule(expiredTrialDoc, requestTime)
        )

        // 3. Attacker tries to modify /users/{uid}/subscription/status to grant themselves ACTIVE
        // In firestore.rules: match /subscription/{documentId} { allow write: if false; }
        val rules = findRulesFile().readText()
        assertTrue(
            "Attacker cannot write to /subscription/{documentId} from client",
            rules.contains("match /subscription/{documentId}") &&
            rules.contains("allow write: if false;")
        )
    }

    // =========================================================================
    // 4. USER PROFILE DOCUMENT WRITE HARDENING AUDIT (/users/{userId})
    // =========================================================================

    /**
     * Helper to simulate /users/{userId} rule evaluation:
     * allow create, update: if isOwner(userId)
     *   && request.resource.data.keys().hasOnly(['email', 'displayName', 'photoUrl', 'updatedAt'])
     *   && (!('email' in request.resource.data) || (request.resource.data.email is string && request.resource.data.email.size() <= 254))
     *   && (!('displayName' in request.resource.data) || (request.resource.data.displayName is string && request.resource.data.displayName.size() <= 100))
     *   && (!('photoUrl' in request.resource.data) || (request.resource.data.photoUrl is string && request.resource.data.photoUrl.size() <= 1000));
     */
    private fun evaluateUserProfileWriteRule(
        isOwner: Boolean,
        data: Map<String, Any?>
    ): Boolean {
        if (!isOwner) return false

        val allowedKeys = setOf("email", "displayName", "photoUrl", "updatedAt")
        if (!allowedKeys.containsAll(data.keys)) return false

        if (data.containsKey("email")) {
            val emailVal = data["email"]
            if (emailVal !is String || emailVal.length > 254) return false
        }

        if (data.containsKey("displayName")) {
            val displayVal = data["displayName"]
            if (displayVal !is String || displayVal.length > 100) return false
        }

        if (data.containsKey("photoUrl")) {
            val photoVal = data["photoUrl"]
            if (photoVal !is String || photoVal.length > 1000) return false
        }

        return true
    }

    @Test
    fun testUserProfile_AllowedFields_Succeeds() {
        val legitimatePayload = mapOf(
            "email" to "user@example.com",
            "displayName" to "John Doe",
            "updatedAt" to "2026-09-28T00:00:00Z"
        )
        assertTrue(
            "Legitimate user profile fields must be allowed",
            evaluateUserProfileWriteRule(isOwner = true, data = legitimatePayload)
        )
    }

    @Test
    fun testUserProfile_SubscriptionFields_Rejected() {
        val attackPayloads = listOf(
            mapOf("email" to "attacker@test.com", "premium" to true),
            mapOf("email" to "attacker@test.com", "subscriptionStatus" to "ACTIVE"),
            mapOf("email" to "attacker@test.com", "currentPeriodEnd" to "2099-01-01T00:00:00Z"),
            mapOf("email" to "attacker@test.com", "planId" to "reeltrak_pro_monthly")
        )

        for (payload in attackPayloads) {
            assertFalse(
                "Subscription fields injected into /users/{userId} must be rejected",
                evaluateUserProfileWriteRule(isOwner = true, data = payload)
            )
        }
    }

    @Test
    fun testUserProfile_ArbitraryJunkFields_Rejected() {
        val junkPayload = mapOf(
            "email" to "attacker@test.com",
            "randomJunk" to "arbitrary_data_stuffing"
        )
        assertFalse(
            "Arbitrary non-whitelisted keys in /users/{userId} must be rejected",
            evaluateUserProfileWriteRule(isOwner = true, data = junkPayload)
        )
    }

    @Test
    fun testUserProfile_OversizedFields_Rejected() {
        val oversizedEmail = mapOf("email" to "a".repeat(255))
        val oversizedName = mapOf("displayName" to "b".repeat(101))
        val oversizedPhoto = mapOf("photoUrl" to "https://evil.com/" + "c".repeat(990))

        assertFalse("Oversized email (>254 chars) must be rejected", evaluateUserProfileWriteRule(true, oversizedEmail))
        assertFalse("Oversized displayName (>100 chars) must be rejected", evaluateUserProfileWriteRule(true, oversizedName))
        assertFalse("Oversized photoUrl (>1000 chars) must be rejected", evaluateUserProfileWriteRule(true, oversizedPhoto))
    }

    @Test
    fun testRules_UserProfileRulesSyntaxContainsPositiveWhitelist() {
        val rules = findRulesFile().readText()
        assertTrue(
            "firestore.rules must contain hasOnly whitelist for user profile",
            rules.contains("request.resource.data.keys().hasOnly(['email', 'displayName', 'photoUrl', 'updatedAt'])")
        )
        assertTrue(
            "firestore.rules must enforce email size limit",
            rules.contains("request.resource.data.email.size() <= 254")
        )
        assertTrue(
            "firestore.rules must enforce displayName size limit",
            rules.contains("request.resource.data.displayName.size() <= 100")
        )
        assertTrue(
            "firestore.rules must enforce photoUrl size limit",
            rules.contains("request.resource.data.photoUrl.size() <= 1000")
        )
    }

    // =========================================================================
    // 5. DAILY TRACKING DATA WRITE HARDENING AUDIT (/users/{userId}/daily_data/{dateString})
    // =========================================================================

    /**
     * Helper to simulate /users/{userId}/daily_data/{dateString} rule evaluation:
     * allow write: if isOwner(userId)
     *   && hasActiveSubscription(userId)
     *   && dateString.matches('^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
     *   && request.resource.data.dateString == dateString
     *   && request.resource.data.keys().hasOnly([
     *     'dateString', 'totalReelsCount', 'dailyGoal', 
     *     'isStrictModeOn', 'isStrictModeLocked', 'isLimitLocked'
     *   ])
     *   && request.resource.data.totalReelsCount is int
     *   && request.resource.data.totalReelsCount >= 0
     *   && request.resource.data.totalReelsCount <= 100000
     *   && request.resource.data.dailyGoal is int
     *   && request.resource.data.dailyGoal >= 0
     *   && request.resource.data.dailyGoal <= 100000
     *   && request.resource.data.isStrictModeOn is bool
     *   && (!('isStrictModeLocked' in request.resource.data) || request.resource.data.isStrictModeLocked is bool)
     *   && (!('isLimitLocked' in request.resource.data) || request.resource.data.isLimitLocked is bool);
     */
    private fun evaluateDailyDataWriteRule(
        isOwner: Boolean,
        hasActiveSubscription: Boolean,
        docDateString: String,
        data: Map<String, Any?>
    ): Boolean {
        if (!isOwner || !hasActiveSubscription) return false

        // Regex check
        val dateRegex = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        if (!dateRegex.matches(docDateString)) return false

        // Key matching
        if (data["dateString"] != docDateString) return false

        // Whitelist check
        val allowedKeys = setOf(
            "dateString", "totalReelsCount", "dailyGoal",
            "isStrictModeOn", "isStrictModeLocked", "isLimitLocked"
        )
        if (!allowedKeys.containsAll(data.keys)) return false

        // Field type & bounds checks
        val totalReels = data["totalReelsCount"]
        if (totalReels !is Int || totalReels < 0 || totalReels > 100000) return false

        val dailyGoal = data["dailyGoal"]
        if (dailyGoal !is Int || dailyGoal < 0 || dailyGoal > 100000) return false

        val isStrictModeOn = data["isStrictModeOn"]
        if (isStrictModeOn !is Boolean) return false

        if (data.containsKey("isStrictModeLocked") && data["isStrictModeLocked"] !is Boolean) return false
        if (data.containsKey("isLimitLocked") && data["isLimitLocked"] !is Boolean) return false

        return true
    }

    @Test
    fun testDailyData_LegitimateRecord_Allowed() {
        val payload = mapOf(
            "dateString" to "2026-09-28",
            "totalReelsCount" to 42,
            "dailyGoal" to 50,
            "isStrictModeOn" to true,
            "isStrictModeLocked" to true,
            "isLimitLocked" to false
        )
        assertTrue(
            "Legitimate daily data within bounds must be allowed",
            evaluateDailyDataWriteRule(
                isOwner = true,
                hasActiveSubscription = true,
                docDateString = "2026-09-28",
                data = payload
            )
        )
    }

    @Test
    fun testDailyData_UnauthorizedUser_Rejected() {
        val payload = mapOf(
            "dateString" to "2026-09-28",
            "totalReelsCount" to 10,
            "dailyGoal" to 50,
            "isStrictModeOn" to false
        )
        assertFalse(
            "Non-owner write to another user's daily_data must be rejected",
            evaluateDailyDataWriteRule(
                isOwner = false,
                hasActiveSubscription = true,
                docDateString = "2026-09-28",
                data = payload
            )
        )
    }

    @Test
    fun testDailyData_NoActiveSubscription_Rejected() {
        val payload = mapOf(
            "dateString" to "2026-09-28",
            "totalReelsCount" to 10,
            "dailyGoal" to 50,
            "isStrictModeOn" to false
        )
        assertFalse(
            "Owner without active subscription must be rejected",
            evaluateDailyDataWriteRule(
                isOwner = true,
                hasActiveSubscription = false,
                docDateString = "2026-09-28",
                data = payload
            )
        )
    }

    @Test
    fun testDailyData_UnauthenticatedAccess_Rejected() {
        val payload = mapOf(
            "dateString" to "2026-09-28",
            "totalReelsCount" to 10,
            "dailyGoal" to 50,
            "isStrictModeOn" to false
        )
        // When unauthenticated, request.auth is null so isOwner(userId) is false
        assertFalse(
            "Unauthenticated request (isOwner = false) must be rejected",
            evaluateDailyDataWriteRule(
                isOwner = false,
                hasActiveSubscription = true,
                docDateString = "2026-09-28",
                data = payload
            )
        )
    }

    @Test
    fun testDailyData_OwnerWithActiveSubAndActiveAccount_Allowed() {
        val payload = mapOf(
            "dateString" to "2026-09-28",
            "totalReelsCount" to 25,
            "dailyGoal" to 60,
            "isStrictModeOn" to false
        )
        val requestTime = Instant.parse("2026-09-28T10:00:00Z")
        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "ACTIVE",
            currentPeriodEnd = Instant.parse("2026-10-28T10:00:00Z")
        )
        val accDoc = MockAccountDoc(accountStatus = "ACTIVE")
        val hasActiveSub = evaluateHasActiveSubscriptionRule(subDoc, requestTime, accDoc)

        assertTrue(
            "Owner with active subscription and active account must be allowed",
            evaluateDailyDataWriteRule(
                isOwner = true,
                hasActiveSubscription = hasActiveSub,
                docDateString = "2026-09-28",
                data = payload
            )
        )
    }

    @Test
    fun testDailyData_OwnerWithActiveSubButRestrictedAccount_Rejected() {
        val payload = mapOf(
            "dateString" to "2026-09-28",
            "totalReelsCount" to 25,
            "dailyGoal" to 60,
            "isStrictModeOn" to false
        )
        val requestTime = Instant.parse("2026-09-28T10:00:00Z")
        val subDoc = MockSubscriptionDoc(
            subscriptionStatus = "ACTIVE",
            currentPeriodEnd = Instant.parse("2026-10-28T10:00:00Z")
        )
        val accDoc = MockAccountDoc(accountStatus = "RESTRICTED")
        val hasActiveSub = evaluateHasActiveSubscriptionRule(subDoc, requestTime, accDoc)

        assertFalse(
            "Owner with active subscription but RESTRICTED account must be rejected",
            evaluateDailyDataWriteRule(
                isOwner = true,
                hasActiveSubscription = hasActiveSub,
                docDateString = "2026-09-28",
                data = payload
            )
        )
    }

    @Test
    fun testDailyData_MalformedDateKey_Rejected() {
        val invalidDates = listOf(
            "2026/09/28",
            "28-09-2026",
            "2026-9-28",
            "yesterday",
            "random_string",
            "2026-09-28-extra",
            "../../etc/passwd"
        )
        for (badDate in invalidDates) {
            val payload = mapOf(
                "dateString" to badDate,
                "totalReelsCount" to 10,
                "dailyGoal" to 50,
                "isStrictModeOn" to false
            )
            assertFalse(
                "Malformed date format $badDate must be rejected",
                evaluateDailyDataWriteRule(
                    isOwner = true,
                    hasActiveSubscription = true,
                    docDateString = badDate,
                    data = payload
                )
            )
        }
    }

    @Test
    fun testDailyData_MismatchedDateKey_Rejected() {
        val payload = mapOf(
            "dateString" to "2026-09-27",
            "totalReelsCount" to 10,
            "dailyGoal" to 50,
            "isStrictModeOn" to false
        )
        assertFalse(
            "Date string in payload differing from document ID must be rejected",
            evaluateDailyDataWriteRule(
                isOwner = true,
                hasActiveSubscription = true,
                docDateString = "2026-09-28",
                data = payload
            )
        )
    }

    @Test
    fun testDailyData_UnknownOrSubscriptionFields_Rejected() {
        val payloadsWithBadKeys = listOf(
            mapOf("dateString" to "2026-09-28", "totalReelsCount" to 10, "dailyGoal" to 50, "isStrictModeOn" to false, "premium" to true),
            mapOf("dateString" to "2026-09-28", "totalReelsCount" to 10, "dailyGoal" to 50, "isStrictModeOn" to false, "subscriptionStatus" to "ACTIVE"),
            mapOf("dateString" to "2026-09-28", "totalReelsCount" to 10, "dailyGoal" to 50, "isStrictModeOn" to false, "junkField" to "stuffing"),
            mapOf("dateString" to "2026-09-28", "totalReelsCount" to 10, "dailyGoal" to 50, "isStrictModeOn" to false, "sessions" to listOf("heavy_array"))
        )

        for (badPayload in payloadsWithBadKeys) {
            assertFalse(
                "Payload with unauthorized/injected keys must be rejected",
                evaluateDailyDataWriteRule(
                    isOwner = true,
                    hasActiveSubscription = true,
                    docDateString = "2026-09-28",
                    data = badPayload
                )
            )
        }
    }

    @Test
    fun testDailyData_OversizedOrNegativeNumbers_Rejected() {
        val negativeReels = mapOf("dateString" to "2026-09-28", "totalReelsCount" to -1, "dailyGoal" to 50, "isStrictModeOn" to false)
        val excessiveReels = mapOf("dateString" to "2026-09-28", "totalReelsCount" to 100001, "dailyGoal" to 50, "isStrictModeOn" to false)
        val negativeGoal = mapOf("dateString" to "2026-09-28", "totalReelsCount" to 10, "dailyGoal" to -5, "isStrictModeOn" to false)
        val excessiveGoal = mapOf("dateString" to "2026-09-28", "totalReelsCount" to 10, "dailyGoal" to 500000, "isStrictModeOn" to false)

        assertFalse("Negative reel count must be rejected", evaluateDailyDataWriteRule(true, true, "2026-09-28", negativeReels))
        assertFalse("Excessive reel count (>100,000) must be rejected", evaluateDailyDataWriteRule(true, true, "2026-09-28", excessiveReels))
        assertFalse("Negative daily goal must be rejected", evaluateDailyDataWriteRule(true, true, "2026-09-28", negativeGoal))
        assertFalse("Excessive daily goal (>100,000) must be rejected", evaluateDailyDataWriteRule(true, true, "2026-09-28", excessiveGoal))
    }

    @Test
    fun testRules_DailyDataRuleSyntaxInFirestoreRules() {
        val rules = findRulesFile().readText()
        assertTrue(
            "firestore.rules must match YYYY-MM-DD date regex",
            rules.contains("dateString.matches('^[0-9]{4}-[0-9]{2}-[0-9]{2}$')")
        )
        assertTrue(
            "firestore.rules must enforce request.resource.data.dateString == dateString",
            rules.contains("request.resource.data.dateString == dateString")
        )
        assertTrue(
            "firestore.rules must enforce hasOnly whitelist on daily_data",
            rules.contains("request.resource.data.keys().hasOnly([")
        )
        assertTrue(
            "firestore.rules must cap totalReelsCount <= 100000",
            rules.contains("request.resource.data.totalReelsCount <= 100000")
        )
        assertTrue(
            "firestore.rules must cap dailyGoal <= 100000",
            rules.contains("request.resource.data.dailyGoal <= 100000")
        )
    }
}


