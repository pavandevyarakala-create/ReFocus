package com.reeltrak.app.data.services

import android.app.Activity
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-ready Razorpay Android Standard Checkout Implementation for Recurring Subscriptions.
 */
@Singleton
class RazorpaySubscriptionPurchaseServiceImpl @Inject constructor(
    private val subscriptionRepository: dagger.Lazy<com.reeltrak.app.domain.repository.SubscriptionRepository>,
    private val httpClient: OkHttpClient
) : SubscriptionPurchaseService, PaymentResultWithDataListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _checkoutState = MutableStateFlow<CheckoutState>(CheckoutState.Idle)
    override val checkoutState: StateFlow<CheckoutState> = _checkoutState.asStateFlow()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL

    private var currentActivity: Activity? = null
    private var pendingSubscriptionId: String? = null
    
    // Safety flags to prevent lifecycle-induced state resets
    @Volatile private var isCreatingSubscription: Boolean = false
    @Volatile private var isVerifyingOnBackend: Boolean = false

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
            if (isCreatingSubscription || isVerifyingOnBackend) {
                Log.d(TAG, "initiatePurchase ignored: purchase or verification already in flight")
                return
            }

            val user = Firebase.auth.currentUser
            if (user == null) {
                Log.w(TAG, "initiatePurchase called without authenticated Firebase user; aborting safely without error.")
                return
            }

            val activity = currentActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                _checkoutState.value = CheckoutState.Error("Activity context unavailable. Please try again.")
                return
            }

            _checkoutState.value = CheckoutState.CreatingOrder(plan)
            isCreatingSubscription = true

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

                    pendingSubscriptionId = subscriptionId
                    isCreatingSubscription = false

                    withContext(Dispatchers.Main) {
                        val liveActivity = currentActivity
                        if (liveActivity == null || liveActivity.isFinishing || liveActivity.isDestroyed) {
                            Log.w(TAG, "Activity no longer valid when opening Razorpay Checkout")
                            _checkoutState.value = CheckoutState.Error("Screen context unavailable. Please try again.")
                            return@withContext
                        }

                        try {
                            val checkout = Checkout()
                            checkout.setKeyID(keyId)

                            val checkoutOptions = JSONObject().apply {
                                put("name", "Re:Focus")
                                put("description", "Re:Focus Monthly Autopay")
                                put("subscription_id", subscriptionId)
                                put("theme.color", "#151619")
                                put("recurring", "1")
                                put("prefill", JSONObject().apply {
                                    put("email", user.email ?: "")
                                    put("name", user.displayName ?: "")
                                })
                            }

                            _checkoutState.value = CheckoutState.CheckoutInProgress(subscriptionId)
                            Log.d("SUB_PERF", "[SUB_PERF] Razorpay Checkout launching in ${SystemClock.elapsedRealtime() - t0}ms total")
                            checkout.open(liveActivity, checkoutOptions)
                        } catch (checkoutEx: Exception) {
                            Log.e(TAG, "Failed to launch Razorpay Checkout", checkoutEx)
                            _checkoutState.value = CheckoutState.Error(checkoutEx.localizedMessage ?: "Unable to launch checkout")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Purchase initiation failed", e)
                    isCreatingSubscription = false
                    pendingSubscriptionId = null
                    _checkoutState.value = CheckoutState.Error(e.localizedMessage ?: "Connection error")
                }
            }
        }
    }

    override fun resetCheckoutState() {
        if (isVerifyingOnBackend || isCreatingSubscription) return
        pendingSubscriptionId = null
        _checkoutState.value = CheckoutState.Idle
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val t0 = SystemClock.elapsedRealtime()
        Log.d("SUB_PERF", "[SUB_PERF] onPaymentSuccess callback received")
        val paymentId = razorpayPaymentId ?: paymentData?.paymentId
        val subscriptionId = try {
            paymentData?.data?.optString("razorpay_subscription_id")?.takeIf { it.isNotBlank() }
                ?: pendingSubscriptionId
        } catch (e: Exception) {
            pendingSubscriptionId
        }
        val signature = paymentData?.signature

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
        _checkoutState.value = CheckoutState.Verifying
        
        scope.launch {
            verifySubscriptionOnBackend(subscriptionId!!, paymentId!!, signature!!, t0)
        }
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        isCreatingSubscription = false
        isVerifyingOnBackend = false
        pendingSubscriptionId = null
        
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
        subscriptionId: String,
        paymentId: String,
        signature: String,
        startTimeMs: Long = SystemClock.elapsedRealtime()
    ) {
        val t0 = startTimeMs
        Log.d(TAG, "verifySubscriptionOnBackend START")
        Log.d("SUB_PERF", "[SUB_PERF] verifySubscriptionOnBackend starting")
        val user = Firebase.auth.currentUser
        if (user == null) {
            isVerifyingOnBackend = false
            _checkoutState.value = CheckoutState.Error("Authentication lost.")
            return
        }

        try {
            val tToken = SystemClock.elapsedRealtime()
            Log.d(TAG, "verifySubscriptionOnBackend requesting Firebase ID token")
            val tokenResult = user.getIdToken(false).await()
            val idToken = tokenResult.token
            Log.d(TAG, "verifySubscriptionOnBackend Firebase ID token obtained: ${idToken != null}")
            Log.d("SUB_PERF", "[SUB_PERF] Firebase ID token obtained in ${SystemClock.elapsedRealtime() - tToken}ms")

            if (idToken.isNullOrBlank()) {
                throw IllegalStateException("Firebase ID token is null or empty.")
            }

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
            val response = httpClient.newCall(request).execute()
            Log.d(TAG, "verifySubscriptionOnBackend HTTP RESPONSE: code=${response.code}")
            Log.d("SUB_PERF", "[SUB_PERF] POST /api/verify-subscription completed in ${SystemClock.elapsedRealtime() - tHttp}ms, code=${response.code}")

            val responseBody = response.body?.string()
            Log.d(TAG, "verifySubscriptionOnBackend response body received: length=${responseBody?.length ?: 0}")

            if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                val responseJson = JSONObject(responseBody)
                if (responseJson.optBoolean("success", false)) {
                    isVerifyingOnBackend = false
                    pendingSubscriptionId = null
                    val status = responseJson.optString("status")
                    val isMandatePending = responseJson.optBoolean("mandatePending", false) || status == "AUTHENTICATED"

                    if (isMandatePending) {
                        Log.d("SUB_PERF", "[SUB_PERF] verifySubscriptionOnBackend MANDATE_PENDING in ${SystemClock.elapsedRealtime() - t0}ms total")
                        _checkoutState.value = CheckoutState.MandatePending("Mandate authorized. Activation will complete once payment confirms.")
                    } else {
                        Log.d("SUB_PERF", "[SUB_PERF] verifySubscriptionOnBackend SUCCESS in ${SystemClock.elapsedRealtime() - t0}ms total")
                        _checkoutState.value = CheckoutState.Success("Recurring subscription activated!")
                    }
                    scope.launch {
                        try {
                            subscriptionRepository.get().refreshSubscription()
                        } catch (refreshEx: Exception) {
                            Log.w(TAG, "Non-critical post-payment subscription refresh error: ${refreshEx.message}")
                        }
                    }
                    return
                }
            }

            val serverError = JSONObject(responseBody ?: "{}").optString("error", "Subscription verification failed")
            if (com.reeltrak.app.BuildConfig.DEBUG) {
                Log.e(TAG, "verifySubscriptionOnBackend SERVER ERROR: code=${response.code}")
            }
            throw Exception(serverError)

        } catch (e: Exception) {
            if (com.reeltrak.app.BuildConfig.DEBUG) {
                Log.e(TAG, "verifySubscriptionOnBackend EXCEPTION: ${e.javaClass.simpleName}")
            }
            isVerifyingOnBackend = false
            _checkoutState.value = CheckoutState.Error(e.localizedMessage ?: "Verification error")
        }
    }
}

