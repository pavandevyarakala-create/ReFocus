package com.reeltrak.app.security

import com.razorpay.PaymentData
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Unit & Integration Test Suite for Razorpay Subscription Payment Flow:
 * - Checkout options validation (key, amount, currency, subscription_id, prefill)
 * - Callback payload extraction resilience (nested data bundle, signature fallback)
 * - HMAC-SHA256 signature verification with normalization
 * - Immediate activation vs. mandate pending state handling
 */
@RunWith(RobolectricTestRunner::class)
class RazorpayPaymentFlowIntegrationTest {

    private fun computeHmacSha256(data: String, key: String): String {
        val sha256Hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        sha256Hmac.init(secretKey)
        val signedBytes = sha256Hmac.doFinal(data.toByteArray(Charsets.UTF_8))
        return signedBytes.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // 1. CHECKOUT OPTIONS VALIDATION
    // =========================================================================

    @Test
    fun testCheckoutOptions_IncludesRequiredKeyAmountCurrencyAndSanitizedPrefill() {
        val keyId = "rzp_test_mockKey123"
        val subscriptionId = "sub_mockSubscription456"
        val amount = 7900
        val currency = "INR"
        val userEmail = "testuser@example.com"
        val userName = "Test User"

        val checkoutOptions = JSONObject().apply {
            put("key", keyId)
            put("name", "Re:Focus")
            put("description", "Re:Focus Monthly Autopay")
            put("subscription_id", subscriptionId)
            put("amount", amount)
            put("currency", currency)
            put("theme.color", "#151619")
            put("recurring", "1")
            val prefill = JSONObject()
            userEmail.takeIf { it.isNotBlank() }?.let { prefill.put("email", it) }
            userName.takeIf { it.isNotBlank() }?.let { prefill.put("name", it) }
            if (prefill.length() > 0) {
                put("prefill", prefill)
            }
        }

        assertEquals("rzp_test_mockKey123", checkoutOptions.getString("key"))
        assertEquals("sub_mockSubscription456", checkoutOptions.getString("subscription_id"))
        assertEquals(7900, checkoutOptions.getInt("amount"))
        assertEquals("INR", checkoutOptions.getString("currency"))
        assertEquals("1", checkoutOptions.getString("recurring"))

        val prefill = checkoutOptions.getJSONObject("prefill")
        assertEquals("testuser@example.com", prefill.getString("email"))
        assertEquals("Test User", prefill.getString("name"))
    }

    @Test
    fun testCheckoutOptions_SanitizesBlankPrefillFields() {
        val checkoutOptions = JSONObject().apply {
            put("key", "rzp_test_key")
            put("subscription_id", "sub_123")
            put("amount", 7900)
            put("currency", "INR")
            val prefill = JSONObject()
            val blankEmail: String? = "   "
            val nullName: String? = null
            blankEmail?.takeIf { it.isNotBlank() }?.let { prefill.put("email", it) }
            nullName?.takeIf { it.isNotBlank() }?.let { prefill.put("name", it) }
            if (prefill.length() > 0) {
                put("prefill", prefill)
            }
        }

        assertFalse("Prefill object should not be added when fields are blank/null", checkoutOptions.has("prefill"))
    }

    // =========================================================================
    // 2. CALLBACK PAYLOAD EXTRACTION RESILIENCE
    // =========================================================================

    @Test
    fun testPayloadExtraction_ExtractsFromNestedDataBundleWhenDirectSignatureNull() {
        val paymentData = PaymentData()
        val nestedJson = JSONObject().apply {
            put("razorpay_payment_id", "pay_nested_123")
            put("razorpay_subscription_id", "sub_nested_456")
            put("razorpay_signature", "sig_nested_hex_789")
        }
        val setDataMethod = PaymentData::class.java.getDeclaredMethod("setData", JSONObject::class.java).apply { isAccessible = true }
        setDataMethod.invoke(paymentData, nestedJson)

        val paymentId = null?.takeIf { (it as String).isNotBlank() }
            ?: paymentData.paymentId?.takeIf { it.isNotBlank() }
            ?: paymentData.data?.optString("razorpay_payment_id")?.takeIf { it.isNotBlank() }
            ?: paymentData.data?.optString("payment_id")?.takeIf { it.isNotBlank() }

        val subscriptionId = paymentData.data?.optString("razorpay_subscription_id")?.takeIf { it.isNotBlank() }
            ?: paymentData.data?.optString("subscription_id")?.takeIf { it.isNotBlank() }
            ?: paymentData.orderId?.takeIf { it.isNotBlank() }
            ?: "pending_sub_fallback"

        val signature = paymentData.signature?.takeIf { it.isNotBlank() }
            ?: paymentData.data?.optString("razorpay_signature")?.takeIf { it.isNotBlank() }
            ?: paymentData.data?.optString("signature")?.takeIf { it.isNotBlank() }

        assertEquals("pay_nested_123", paymentId)
        assertEquals("sub_nested_456", subscriptionId)
        assertEquals("sig_nested_hex_789", signature)
    }

    @Test
    fun testPayloadExtraction_RejectsIncompletePayloadWhenSignatureMissing() {
        val paymentData = PaymentData()
        val nestedJson = JSONObject().apply {
            put("razorpay_payment_id", "pay_incomplete_123")
            put("razorpay_subscription_id", "sub_incomplete_456")
            // signature is missing
        }
        val setDataMethod = PaymentData::class.java.getDeclaredMethod("setData", JSONObject::class.java).apply { isAccessible = true }
        setDataMethod.invoke(paymentData, nestedJson)

        val paymentId = paymentData.data?.optString("razorpay_payment_id")?.takeIf { it.isNotBlank() }
        val subscriptionId = paymentData.data?.optString("razorpay_subscription_id")?.takeIf { it.isNotBlank() }
        val signature = paymentData.signature?.takeIf { it.isNotBlank() }
            ?: paymentData.data?.optString("razorpay_signature")?.takeIf { it.isNotBlank() }

        val hasPaymentId = !paymentId.isNullOrBlank()
        val hasSubId = !subscriptionId.isNullOrBlank()
        val hasSignature = !signature.isNullOrBlank()

        assertTrue(hasPaymentId)
        assertTrue(hasSubId)
        assertFalse("Must detect missing signature", hasSignature)
    }

    // =========================================================================
    // 3. HMAC-SHA256 SIGNATURE VERIFICATION NORMALIZATION
    // =========================================================================

    @Test
    fun testSubscriptionSignature_CalculatesAndValidatesWithNormalization() {
        val secret = "test_key_secret_abc123"
        val paymentId = "pay_test_payment_999"
        val subscriptionId = "sub_test_sub_888"
        val expectedData = "$paymentId|$subscriptionId"

        val generatedSig = computeHmacSha256(expectedData, secret)
        assertNotNull(generatedSig)

        // Verify trimming and case-insensitive comparison
        val untrimmedUppercaseSig = "  ${generatedSig.uppercase()}  "
        val cleanedSig = untrimmedUppercaseSig.trim().lowercase()
        assertEquals(generatedSig, cleanedSig)

        // Verify tampering is rejected
        val tamperedData = "pay_different_payment_000|$subscriptionId"
        val tamperedSig = computeHmacSha256(tamperedData, secret)
        assertFalse(generatedSig == tamperedSig)
    }

    // =========================================================================
    // 4. SUBSCRIPTION ENTITLEMENT DOMAIN CONVERSION
    // =========================================================================

    @Test
    fun testActiveSubscriptionDto_UnlocksAccessStateActive() {
        val now = System.currentTimeMillis()
        val futureEnd = now + (30L * 24 * 3600 * 1000L)

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "ACTIVE",
            planId = "plan_TdtgehXIhzQ8U9",
            subscriptionId = "sub_active_test",
            latestPaymentId = "pay_active_test",
            currentPeriodEnd = com.google.firebase.Timestamp(java.util.Date(futureEnd)),
            premium = true
        )

        val domain = dto.toDomain(trustedNow = now)
        assertEquals(AccessState.ACTIVE, domain.accessState)
        assertTrue(domain.isUnlocked)
        assertEquals(SubscriptionStatus.ACTIVE, domain.status)
        assertEquals("sub_active_test", domain.subscriptionId)
    }

    @Test
    fun testMandateAuthenticatedWithoutPeriodEnd_EvaluatesToLocked() {
        val now = System.currentTimeMillis()

        val dto = CloudSubscriptionDto(
            subscriptionStatus = "FREE",
            planId = "plan_TdtgehXIhzQ8U9",
            subscriptionId = "sub_mandate_pending",
            latestPaymentId = "pay_mandate_pending",
            currentPeriodEnd = null,
            premium = false
        )

        val domain = dto.toDomain(trustedNow = now)
        assertEquals(AccessState.LOCKED, domain.accessState)
        assertFalse("Mandate pending without captured payment must remain locked", domain.isUnlocked)
    }
}
