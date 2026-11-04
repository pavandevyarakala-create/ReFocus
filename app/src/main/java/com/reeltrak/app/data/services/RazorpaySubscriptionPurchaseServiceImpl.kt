package com.reeltrak.app.data.services

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.services.CheckoutState
import com.reeltrak.app.domain.services.SubscriptionPurchaseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Production-ready Razorpay Android Standard Checkout Implementation for Recurring Subscriptions.
 */
@Singleton
class RazorpaySubscriptionPurchaseServiceImpl @Inject constructor(
    private val subscriptionRepository: dagger.Lazy<com.reeltrak.app.domain.repository.SubscriptionRepository>,
    private val httpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context
) : SubscriptionPurchaseService, PaymentResultWithDataListener {

    private data class PendingVerification(val uid: String, val subscriptionId: String, val paymentId: String, val signature: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _checkoutState = MutableStateFlow<CheckoutState>(CheckoutState.Idle)
    override val checkoutState: StateFlow<CheckoutState> = _checkoutState.asStateFlow()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL
    private val pendingRecoveryStore = PendingSubscriptionRecoveryStore(appContext)

    private var currentActivity: Activity? = null
    private var pendingSubscriptionId: String? = null
    private var pendingOwnerUid: String? = null
    @Volatile private var pendingVerification: PendingVerification? = null
    
    // Safety flags to prevent lifecycle-induced state resets
    @Volatile private var isCreatingSubscription: Boolean = false
    @Volatile private var isVerifyingOnBackend: Boolean = false
    @Volatile private var isCheckoutOpen: Boolean = false

    private val purchaseMutex = Mutex()

    fun attachActivity(activity: Activity) {
        this.currentActivity = activity
        preload(activity)
    }

    fun detachActivity() {
        this.currentActivity = null
    }

    /**
     * Called when the activity resumes.
     * We keep the pending checkout state and pendingSubscriptionId intact through activity transitions
     * so Razorpay onPaymentSuccess / onPaymentError callbacks can complete authoritatively.
     */
    fun onActivityResumed() {
        Log.d(TAG, "Activity resumed. Current checkoutState=${_checkoutState.value::class.java.simpleName}, pendingSubscriptionIdPresent=${pendingSubscriptionId != null}")
        val pending = pendingVerification
        val user = Firebase.auth.currentUser
        if (pending != null) {
            if (user == null || user.uid != pending.uid) {
                isVerifyingOnBackend = false
                _checkoutState.value = CheckoutState.Error("Sign in to the account used for this payment to finish verification.")
                return
            }
        }
        if (pending != null && !isVerifyingOnBackend) {
            isVerifyingOnBackend = true
            _checkoutState.value = CheckoutState.Verifying
            scope.launch {
                verifySubscriptionOnBackend(pending.uid, pending.subscriptionId, pending.paymentId, pending.signature)
            }
            return
        }

        if (pending == null && user != null && !isVerifyingOnBackend && !isCheckoutOpen) {
            val subscriptionId = pendingRecoveryStore.load(user.uid) ?: return
            isVerifyingOnBackend = true
            _checkoutState.value = CheckoutState.Verifying
            scope.launch { recoverPendingSubscription(user.uid, subscriptionId) }
        }
    }

    override fun preload() {
        val appContext = currentActivity?.applicationContext ?: return
        preloadInternal(appContext)
    }

    fun preload(activity: Activity) {
        val appContext = activity.applicationContext
        preloadInternal(appContext)
    }

    companion object {
        private const val TAG = "RazorpayService"
        private val isPreloaded = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private fun preloadInternal(appContext: android.content.Context) {
        if (!isPreloaded.compareAndSet(false, true)) {
            Log.d(TAG, "Razorpay Checkout already preloaded in this process; skipping redundant preload")
            return
        }
        scope.launch {
            try {
                Checkout.preload(appContext)
                Log.d(TAG, "Razorpay Checkout preloaded successfully")
            } catch (e: Exception) {
                isPreloaded.set(false)
                Log.w(TAG, "Failed to preload Razorpay Checkout in background", e)
            }
        }
    }

    override suspend fun initiatePurchase(plan: SubscriptionPlan) {
        val t0 = SystemClock.elapsedRealtime()
        Log.d("SUB_PERF", "[SUB_PERF] initiatePurchase started for plan=${plan.planId}")
        purchaseMutex.withLock {
            if (isCreatingSubscription || isVerifyingOnBackend || isCheckoutOpen || pendingVerification != null) {
                Log.d(TAG, "initiatePurchase ignored: purchase or verification already in flight")
                return
            }

            val user = Firebase.auth.currentUser
            if (user == null) {
                Log.w(TAG, "initiatePurchase called without authenticated Firebase user; aborting safely without error.")
                return
            }
            if (pendingRecoveryStore.load(user.uid) != null) {
                _checkoutState.value = CheckoutState.MandatePending("A previous subscription is still being confirmed. We’ll reconcile it before starting another checkout.")
                return
            }

            val activity = currentActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                _checkoutState.value = CheckoutState.Error("Activity context unavailable. Please try again.")
                return
            }

            _checkoutState.value = CheckoutState.CreatingOrder(plan)
            isCreatingSubscription = true
            pendingOwnerUid = user.uid

            withContext(Dispatchers.IO) {
                try {
                    val tToken = SystemClock.elapsedRealtime()
                    val tokenResult = user.getIdToken(false).await()
                    val idToken = tokenResult.token ?: throw IllegalStateException("Auth token missing.")
                    Log.d("SUB_PERF", "[SUB_PERF] Firebase ID token retrieved in ${SystemClock.elapsedRealtime() - tToken}ms")

                    val tApi = SystemClock.elapsedRealtime()
                    val requestPayload = JSONObject().apply { put("planId", plan.planId) }
                    val request = Request.Builder()
                        .url("$workerBaseUrl/api/create-subscription")
                        .addHeader("Authorization", "Bearer $idToken")
                        .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string()
                    Log.d("SUB_PERF", "[SUB_PERF] POST /api/create-subscription completed in ${SystemClock.elapsedRealtime() - tApi}ms, code=${response.code}")

                    if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                        val errorMsg = try {
                            JSONObject(responseBody ?: "{}").optString("error", "Failed to create subscription")
                        } catch (e: Exception) {
                            "Server error (${response.code})"
                        }
                        throw IllegalStateException(errorMsg)
                    }

                    val subJson = JSONObject(responseBody)
                    val subscriptionId = subJson.getString("subscriptionId")
                    val keyId = subJson.getString("keyId")
                    val amount = subJson.optInt("amount", 7900)
                    val currency = subJson.optString("currency", "INR")

                    pendingSubscriptionId = subscriptionId
                    if (!pendingRecoveryStore.save(user.uid, subscriptionId)) {
                        throw IllegalStateException("Unable to save local checkout recovery state. Please retry before authorizing payment.")
                    }
                    isCreatingSubscription = false

                    withContext(Dispatchers.Main) {
                        val liveActivity = currentActivity
                        if (liveActivity == null || liveActivity.isFinishing || liveActivity.isDestroyed) {
                            Log.w(TAG, "Activity no longer valid when opening Razorpay Checkout")
                            _checkoutState.value = CheckoutState.MandatePending("Checkout is ready to resume. Reopen this screen to continue securely.")
                            return@withContext
                        }
                        openRazorpayCheckout(liveActivity, subscriptionId, keyId, amount, currency, user.email, user.displayName)
                        Log.d("SUB_PERF", "[SUB_PERF] Razorpay Checkout launched in ${SystemClock.elapsedRealtime() - t0}ms total")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Purchase initiation failed", e)
                    isCreatingSubscription = false
                    pendingSubscriptionId = null
                    pendingOwnerUid = null
                    _checkoutState.value = CheckoutState.Error(e.localizedMessage ?: "Connection error")
                }
            }
        }
    }

    override fun resetCheckoutState() {
        if (isVerifyingOnBackend || isCreatingSubscription || isCheckoutOpen) return
        pendingVerification = null
        val uid = Firebase.auth.currentUser?.uid
        if (uid != null && pendingRecoveryStore.load(uid) != null) {
            _checkoutState.value = CheckoutState.Idle
            return
        }
        pendingSubscriptionId = null
        pendingOwnerUid = null
        _checkoutState.value = CheckoutState.Idle
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val t0 = SystemClock.elapsedRealtime()
        Log.d("SUB_PERF", "[SUB_PERF] onPaymentSuccess callback received")
        val paymentId = razorpayPaymentId?.takeIf { it.isNotBlank() }
            ?: paymentData?.paymentId?.takeIf { it.isNotBlank() }
            ?: paymentData?.data?.optString("razorpay_payment_id")?.takeIf { it.isNotBlank() }
            ?: paymentData?.data?.optString("payment_id")?.takeIf { it.isNotBlank() }

        val subscriptionId = try {
            paymentData?.data?.optString("razorpay_subscription_id")?.takeIf { it.isNotBlank() }
                ?: paymentData?.data?.optString("subscription_id")?.takeIf { it.isNotBlank() }
                ?: paymentData?.orderId?.takeIf { it.isNotBlank() }
                ?: pendingSubscriptionId
        } catch (e: Exception) {
            pendingSubscriptionId
        }

        val signature = paymentData?.signature?.takeIf { it.isNotBlank() }
            ?: paymentData?.data?.optString("razorpay_signature")?.takeIf { it.isNotBlank() }
            ?: paymentData?.data?.optString("signature")?.takeIf { it.isNotBlank() }

        val hasPaymentId = !paymentId.isNullOrBlank()
        val hasSubId = !subscriptionId.isNullOrBlank()
        val hasSignature = !signature.isNullOrBlank()

        Log.d(TAG, "onPaymentSuccess received: hasPaymentId=$hasPaymentId, hasSubId=$hasSubId, hasSignature=$hasSignature")

        if (!hasPaymentId || !hasSubId || !hasSignature) {
            Log.w(TAG, "onPaymentSuccess incomplete payload: paymentIdPresent=$hasPaymentId, subIdPresent=$hasSubId, signaturePresent=$hasSignature")
            _checkoutState.value = CheckoutState.Error("Payment details incomplete.")
            return
        }

        isVerifyingOnBackend = true
        isCheckoutOpen = false
        val uid = pendingOwnerUid ?: Firebase.auth.currentUser?.uid
        if (uid == null) {
            isVerifyingOnBackend = false
            _checkoutState.value = CheckoutState.Error("Sign in to the account used for this payment to finish verification.")
            return
        }
        pendingOwnerUid = uid
        if (!pendingRecoveryStore.save(uid, subscriptionId!!)) {
            Log.w(TAG, "Could not persist pending subscription reference; server verification will still be attempted.")
        }
        pendingVerification = PendingVerification(uid, subscriptionId, paymentId!!, signature!!)
        _checkoutState.value = CheckoutState.Verifying
        
        scope.launch {
            verifySubscriptionOnBackend(uid, subscriptionId, paymentId!!, signature!!, t0)
        }
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        isCreatingSubscription = false
        isVerifyingOnBackend = false
        isCheckoutOpen = false
        pendingVerification = null
        if (code == Checkout.PAYMENT_CANCELED) {
            pendingOwnerUid?.let { pendingRecoveryStore.clear(it) }
            pendingSubscriptionId = null
            pendingOwnerUid = null
        }
        
        Log.d(TAG, "onPaymentError received: code=$code, hasPaymentData=${paymentData != null}")

        val errorMsg = when (code) {
            Checkout.PAYMENT_CANCELED -> "Payment cancelled."
            Checkout.NETWORK_ERROR -> "Network error during payment."
            else -> {
                try {
                    val json = JSONObject(response ?: "{}")
                    json.optJSONObject("error")?.optString("description") 
                        ?: json.optString("description", "Payment failed.")
                } catch (e: Exception) {
                    "Payment failed."
                }
            }
        }
        _checkoutState.value = CheckoutState.Error(errorMsg)
    }

    private suspend fun verifySubscriptionOnBackend(
        expectedUid: String,
        subscriptionId: String,
        paymentId: String,
        signature: String,
        startTimeMs: Long = SystemClock.elapsedRealtime()
    ) {
        val t0 = startTimeMs
        Log.d(TAG, "verifySubscriptionOnBackend START")
        Log.d("SUB_PERF", "[SUB_PERF] verifySubscriptionOnBackend starting")
        val user = Firebase.auth.currentUser
        if (user == null || user.uid != expectedUid) {
            isVerifyingOnBackend = false
            _checkoutState.value = CheckoutState.Error("Sign in to the account used for this payment to finish verification.")
            return
        }

        val idToken = try {
            user.getIdToken(false).await().token
        } catch (authError: Exception) {
            isVerifyingOnBackend = false
            _checkoutState.value = CheckoutState.Error("Sign-in could not be refreshed. Sign in again to continue payment verification.")
            return
        }
        if (idToken.isNullOrBlank()) {
            isVerifyingOnBackend = false
            _checkoutState.value = CheckoutState.Error("Sign-in could not be refreshed. Sign in again to continue payment verification.")
            return
        }

        try {
            Log.d(TAG, "verifySubscriptionOnBackend Firebase ID token obtained")

            val verifyPayload = JSONObject().apply {
                put("subscription_id", subscriptionId)
                put("payment_id", paymentId)
                put("signature", signature)
            }

            val request = Request.Builder()
                .url("$workerBaseUrl/api/verify-subscription")
                .addHeader("Authorization", "Bearer $idToken")
                .post(verifyPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Log.d(TAG, "verifySubscriptionOnBackend HTTP REQUEST START: POST /api/verify-subscription")
            val tHttp = SystemClock.elapsedRealtime()
            val (responseCode, responseBody) = withSubscriptionVerificationRetry(
                execute = {
                    httpClient.newCall(request).execute().use { response ->
                        response.code to response.body?.string()
                    }
                },
                shouldRetry = { result -> isRetryableSubscriptionVerificationStatus(result.first) }
            )
            Log.d(TAG, "verifySubscriptionOnBackend HTTP RESPONSE: code=$responseCode")
            Log.d("SUB_PERF", "[SUB_PERF] POST /api/verify-subscription completed in ${SystemClock.elapsedRealtime() - tHttp}ms, code=$responseCode")

            Log.d(TAG, "verifySubscriptionOnBackend response body received: length=${responseBody?.length ?: 0}")

            if (responseCode in 200..299 && !responseBody.isNullOrBlank()) {
                val responseJson = JSONObject(responseBody)
                if (responseJson.optBoolean("success", false)) {
                    isVerifyingOnBackend = false
                    pendingSubscriptionId = null
                    val status = responseJson.optString("status")
                    val isMandatePending = responseJson.optBoolean("mandatePending", false) || status == "AUTHENTICATED"

                    if (status == "ACTIVE") {
                        pendingRecoveryStore.clear(expectedUid)
                        pendingOwnerUid = null
                        pendingVerification = null
                    } else {
                        pendingVerification = null
                    }

                    try {
                        subscriptionRepository.get().refreshSubscription()
                    } catch (refreshEx: Exception) {
                        Log.w(TAG, "Non-critical post-payment subscription refresh error: ${refreshEx.message}")
                    }

                    if (isMandatePending) {
                        Log.d("SUB_PERF", "[SUB_PERF] verifySubscriptionOnBackend MANDATE_PENDING in ${SystemClock.elapsedRealtime() - t0}ms total")
                        _checkoutState.value = CheckoutState.MandatePending("Mandate authorized. Activation will complete once payment confirms.")
                    } else {
                        Log.d("SUB_PERF", "[SUB_PERF] verifySubscriptionOnBackend SUCCESS in ${SystemClock.elapsedRealtime() - t0}ms total")
                        _checkoutState.value = CheckoutState.Success("Recurring subscription activated!")
                    }
                    return
                }
            }

            if (isRetryableSubscriptionVerificationStatus(responseCode)) {
                reconcilePendingPaymentLocally()
                return
            }

            val serverError = JSONObject(responseBody ?: "{}").optString("error", "Subscription verification failed")
            if (com.reeltrak.app.BuildConfig.DEBUG) {
                Log.e(TAG, "verifySubscriptionOnBackend SERVER ERROR: code=$responseCode")
            }
            throw Exception(serverError)

        } catch (e: Exception) {
            if (com.reeltrak.app.BuildConfig.DEBUG) {
                Log.e(TAG, "verifySubscriptionOnBackend EXCEPTION: ${e.javaClass.simpleName}")
            }
            if (e is IOException) {
                reconcilePendingPaymentLocally()
            } else {
                isVerifyingOnBackend = false
                pendingVerification = null
                _checkoutState.value = CheckoutState.Error(e.localizedMessage ?: "Verification error")
            }
        }
    }

    private fun openRazorpayCheckout(
        activity: Activity,
        subscriptionId: String,
        keyId: String,
        amount: Int,
        currency: String,
        email: String?,
        displayName: String?
    ) {
        try {
            val checkout = Checkout()
            checkout.setKeyID(keyId)
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
                email?.takeIf { it.isNotBlank() }?.let { prefill.put("email", it) }
                displayName?.takeIf { it.isNotBlank() }?.let { prefill.put("name", it) }
                if (prefill.length() > 0) put("prefill", prefill)
            }
            isCheckoutOpen = true
            _checkoutState.value = CheckoutState.CheckoutInProgress(subscriptionId)
            checkout.open(activity, checkoutOptions)
        } catch (checkoutError: Exception) {
            isCheckoutOpen = false
            Log.e(TAG, "Failed to launch Razorpay Checkout", checkoutError)
            _checkoutState.value = CheckoutState.MandatePending("Checkout remains available to resume. Reopen this screen to continue securely.")
        }
    }

    private suspend fun recoverPendingSubscription(uid: String, subscriptionId: String) {
        try {
            val user = Firebase.auth.currentUser
            if (user == null || user.uid != uid) {
                isVerifyingOnBackend = false
                _checkoutState.value = CheckoutState.Error("Sign in to the account used for this payment to finish verification.")
                return
            }
            pendingOwnerUid = uid
            pendingSubscriptionId = subscriptionId

            val idToken = try {
                user.getIdToken(false).await().token
            } catch (authError: Exception) {
                null
            }
            if (idToken.isNullOrBlank()) {
                isVerifyingOnBackend = false
                _checkoutState.value = CheckoutState.Error("Sign-in could not be refreshed. Sign in again to continue payment verification.")
                return
            }

            val request = Request.Builder()
                .url("$workerBaseUrl/api/restore-subscription")
                .addHeader("Authorization", "Bearer $idToken")
                .post(JSONObject().put("subscription_id", subscriptionId).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val (responseCode, responseBody) = withSubscriptionVerificationRetry(
                execute = {
                    httpClient.newCall(request).execute().use { response ->
                        response.code to response.body?.string()
                    }
                },
                shouldRetry = { result -> isRetryableSubscriptionVerificationStatus(result.first) }
            )

            if (responseCode in 200..299 && !responseBody.isNullOrBlank()) {
                val result = JSONObject(responseBody)
                if (!result.optBoolean("success", false)) {
                    isVerifyingOnBackend = false
                    _checkoutState.value = CheckoutState.MandatePending("Payment status is still being confirmed by the server. We’ll retry when you return to this screen.")
                    return
                }
                when (result.optString("status")) {
                    "ACTIVE" -> {
                        pendingRecoveryStore.clear(uid)
                        pendingSubscriptionId = null
                        pendingOwnerUid = null
                        isVerifyingOnBackend = false
                        pendingVerification = null
                        try {
                            subscriptionRepository.get().refreshSubscription()
                        } catch (refreshError: Exception) {
                            Log.w(TAG, "Post-recovery subscription refresh failed: ${refreshError.javaClass.simpleName}")
                        }
                        _checkoutState.value = CheckoutState.Success("Recurring subscription confirmed by the server.")
                    }
                    "CREATED" -> {
                        val returnedSubId = result.optString("subscriptionId")
                        val keyId = result.optString("keyId")
                        if (returnedSubId != subscriptionId || keyId.isBlank()) {
                            throw IllegalStateException("Worker returned an invalid checkout recovery response.")
                        }
                        val activity = currentActivity
                        if (activity == null || activity.isFinishing || activity.isDestroyed) {
                            isVerifyingOnBackend = false
                            _checkoutState.value = CheckoutState.MandatePending("Checkout is ready to resume. Reopen this screen to continue securely.")
                            return
                        }
                        isVerifyingOnBackend = false
                        withContext(Dispatchers.Main) {
                            openRazorpayCheckout(
                                activity,
                                subscriptionId,
                                keyId,
                                result.optInt("amount", 7900),
                                result.optString("currency", "INR"),
                                user.email,
                                user.displayName
                            )
                        }
                    }
                    "AUTHENTICATED", "PENDING" -> {
                        isVerifyingOnBackend = false
                        _checkoutState.value = CheckoutState.MandatePending("The server confirmed the mandate. Access will update after payment confirmation.")
                    }
                    "CANCELLED", "EXPIRED" -> {
                        pendingRecoveryStore.clear(uid)
                        pendingSubscriptionId = null
                        pendingOwnerUid = null
                        isVerifyingOnBackend = false
                        _checkoutState.value = CheckoutState.Error("The previous subscription is no longer active. You can start a new checkout.")
                    }
                    "NONE" -> {
                        pendingRecoveryStore.clear(uid)
                        pendingSubscriptionId = null
                        pendingOwnerUid = null
                        isVerifyingOnBackend = false
                        _checkoutState.value = CheckoutState.Error("No subscription was found for the previous checkout. You can start a new checkout.")
                    }
                    else -> {
                        isVerifyingOnBackend = false
                        _checkoutState.value = CheckoutState.MandatePending("Payment status is still being confirmed by the server. We’ll retry when you return to this screen.")
                    }
                }
                return
            }

            isVerifyingOnBackend = false
            if (responseCode == 400 || responseCode == 404) {
                pendingRecoveryStore.clear(uid)
                _checkoutState.value = CheckoutState.Error("The previous checkout could not be verified. You can start a new checkout.")
            } else {
                _checkoutState.value = CheckoutState.MandatePending("Payment status is still being confirmed by the server. We’ll retry when you return to this screen.")
            }
        } catch (recoveryError: Exception) {
            isVerifyingOnBackend = false
            Log.w(TAG, "Pending subscription recovery deferred: ${recoveryError.javaClass.simpleName}")
            _checkoutState.value = CheckoutState.MandatePending("Payment status is still being confirmed by the server. We’ll retry when you return to this screen.")
        }
    }

    private suspend fun reconcilePendingPaymentLocally() {
        try {
            subscriptionRepository.get().refreshSubscription()
        } catch (refreshEx: Exception) {
            Log.w(TAG, "Subscription refresh after verification delay failed: ${refreshEx.javaClass.simpleName}")
        }
        isVerifyingOnBackend = false
        _checkoutState.value = CheckoutState.MandatePending(
            "Payment was submitted. We’re confirming it securely; subscription access will update after server verification."
        )
    }
}

