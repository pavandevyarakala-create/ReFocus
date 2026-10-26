package com.reeltrak.app.core.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Centralized, production-grade manager for AdMob Rewarded Ads.
 *
 * Responsibilities:
 * 1. Asynchronous, non-blocking SDK initialization (guaranteeing 0 impact on startup/splash).
 * 2. Background preloading and single-instance caching of Rewarded Ads.
 * 3. Race condition and rapid double-tap protection via atomic presentation locks.
 * 4. Strict reward verification: callbacks execute ONLY upon confirmed [OnUserEarnedRewardListener] trigger.
 * 5. Automatic cache replenishment with exponential backoff on failure.
 * 6. Leak-free design: holds no strong references to UI Activities.
 */
@Singleton
class RewardedAdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RewardedAdManager"
        private const val DEFAULT_LOAD_TIMEOUT_MS = 6000L
        private const val MAX_RETRY_ATTEMPTS = 4
        private const val INITIAL_RETRY_DELAY_MS = 2000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isInitialized = AtomicBoolean(false)
    private val sdkInitDeferred = CompletableDeferred<Unit>()
    private val _isShowingAd = AtomicBoolean(false)
    private var retryAttempt = 0

    @Volatile
    private var currentRewardedAd: RewardedAd? = null

    private val _isAdReady = MutableStateFlow(false)
    val isAdReady: StateFlow<Boolean> = _isAdReady.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    @Volatile
    private var activeLoadDeferred: CompletableDeferred<Boolean>? = null
    private val loadLock = Any()

    /**
     * Initializes Google Mobile Ads SDK asynchronously.
     * Safe to call multiple times; only the first call triggers SDK initialization.
     * Guaranteed non-blocking and safe to call during post-splash app warmup.
     */
    fun initialize(onInitialized: () -> Unit = {}) {
        if (_isInitialized.getAndSet(true)) {
            scope.launch {
                try {
                    val completed = withTimeoutOrNull(5000L) {
                        sdkInitDeferred.await()
                    }
                    if (completed != null) {
                        onInitialized()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SDK already failed to initialize", e)
                }
            }
            return
        }

        Log.d(TAG, "Initializing Google Mobile Ads SDK asynchronously...")
        scope.launch(Dispatchers.IO) {
            try {
                MobileAds.initialize(context) { initializationStatus ->
                    Log.d(TAG, "AdMob SDK Initialized. Status: ${initializationStatus.adapterStatusMap}")
                    sdkInitDeferred.complete(Unit)
                    scope.launch(Dispatchers.Main) {
                        onInitialized()
                        // Warm up and cache the first rewarded ad immediately
                        preloadAd()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MobileAds", e)
                _isInitialized.set(false)
                sdkInitDeferred.completeExceptionally(e)
            }
        }
    }

    /**
     * Preloads a Rewarded Ad into cache if not already loaded or in progress.
     * Ensures MobileAds initialization is complete before making ad requests.
     */
    fun preloadAd(force: Boolean = false) {
        if (currentRewardedAd != null && !force) {
            Log.d(TAG, "Preload skipped: an ad is already cached and ready.")
            _isAdReady.value = true
            return
        }

        if (_isLoading.value && !force) {
            Log.d(TAG, "Preload skipped: an ad is already loading.")
            return
        }

        if (force) {
            retryAttempt = 0
        }

        scope.launch(Dispatchers.Main) {
            // Await MobileAds initialization if not yet completed (with 5s safety timeout)
            if (!sdkInitDeferred.isCompleted) {
                Log.d(TAG, "Preload waiting for MobileAds SDK initialization...")
                initialize()
                val initSuccess = withTimeoutOrNull(5000L) {
                    try {
                        sdkInitDeferred.await()
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (initSuccess != true) {
                    Log.w(TAG, "Ad preload aborted: MobileAds initialization timed out or failed")
                    _isLoading.value = false
                    _lastErrorMessage.value = "AdMob SDK initialization timed out or failed"
                    synchronized(loadLock) {
                        activeLoadDeferred?.complete(false)
                        activeLoadDeferred = null
                    }
                    return@launch
                }
            }

            if (currentRewardedAd != null && !force) {
                _isAdReady.value = true
                synchronized(loadLock) {
                    activeLoadDeferred?.complete(true)
                    activeLoadDeferred = null
                }
                return@launch
            }

            if (_isLoading.value && !force) {
                return@launch
            }

            val loadDeferred = CompletableDeferred<Boolean>()
            synchronized(loadLock) {
                activeLoadDeferred = loadDeferred
            }

            val adUnitId = AdMobConfig.rewardedAdUnitId
            Log.d(TAG, "Preloading rewarded ad with unit ID: $adUnitId (USE_TEST_ADS=${AdMobConfig.USE_TEST_ADS})")

            _isLoading.value = true
            _lastErrorMessage.value = null

            try {
                val adRequest = AdRequest.Builder().build()
                RewardedAd.load(
                    context,
                    adUnitId,
                    adRequest,
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(rewardedAd: RewardedAd) {
                            Log.d(TAG, "Rewarded ad successfully loaded and cached.")
                            currentRewardedAd = rewardedAd
                            retryAttempt = 0
                            _isAdReady.value = true
                            _isLoading.value = false
                            _lastErrorMessage.value = null
                            synchronized(loadLock) {
                                loadDeferred.complete(true)
                                if (activeLoadDeferred == loadDeferred) {
                                    activeLoadDeferred = null
                                }
                            }
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            val diagnostic = "[Code ${loadAdError.code}] Domain: ${loadAdError.domain}, Message: ${loadAdError.message}, ResponseId: ${loadAdError.responseInfo?.responseId}"
                            Log.w(TAG, "Rewarded ad failed to load: $diagnostic")
                            currentRewardedAd = null
                            _isAdReady.value = false
                            _isLoading.value = false
                            val errorMsg = when (loadAdError.code) {
                                AdRequest.ERROR_CODE_NO_FILL -> "Ad inventory currently unavailable."
                                AdRequest.ERROR_CODE_NETWORK_ERROR -> "Network error loading ad. Check your connection."
                                AdRequest.ERROR_CODE_INVALID_REQUEST -> "Invalid ad request configuration."
                                else -> loadAdError.message.ifBlank { "Ad failed to load." }
                            }
                            _lastErrorMessage.value = errorMsg
                            synchronized(loadLock) {
                                loadDeferred.complete(false)
                                if (activeLoadDeferred == loadDeferred) {
                                    activeLoadDeferred = null
                                }
                            }

                            // Exponential backoff retry strategy (capped at MAX_RETRY_ATTEMPTS)
                            if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                                val delayMs = (INITIAL_RETRY_DELAY_MS * (1 shl retryAttempt)).coerceAtMost(30000L)
                                retryAttempt++
                                Log.d(TAG, "Scheduling automatic ad reload retry #$retryAttempt in ${delayMs}ms...")
                                scope.launch {
                                    delay(delayMs)
                                    preloadAd(force = false)
                                }
                            } else {
                                Log.w(TAG, "Max automatic retry attempts ($MAX_RETRY_ATTEMPTS) reached for ad loading.")
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Exception during RewardedAd.load", e)
                currentRewardedAd = null
                _isAdReady.value = false
                _isLoading.value = false
                val errorMsg = e.message ?: "Failed to initiate ad request"
                _lastErrorMessage.value = errorMsg
                synchronized(loadLock) {
                    loadDeferred.complete(false)
                    if (activeLoadDeferred == loadDeferred) {
                        activeLoadDeferred = null
                    }
                }
            }
        }
    }

    /**
     * Shows the preloaded Rewarded Ad, or loads one on-demand with a timeout if not currently cached.
     * Guaranteed non-blocking: notifies caller immediately if an ad fails to load or present.
     * Returns a [Job] so the presentation request can be canceled cleanly if the user dismisses.
     *
     * @param activity Foreground activity to host the full-screen ad.
     * @param timeoutMs Maximum time to wait if an ad needs to be fetched on-demand.
     * @param onRewardEarned Invoked ONLY when user completes the video and earns the reward.
     * @param onDismissedWithoutReward Invoked if user closes/skips the video before earning reward.
     * @param onError Invoked immediately if ad fails to load, times out, or fails to present.
     */
    fun showRewardedAd(
        activity: Activity,
        timeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
        onRewardEarned: () -> Unit,
        onDismissedWithoutReward: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Job {
        // Activity lifecycle guard: do not present if activity is closing or dead
        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Show ad aborted: Activity is finishing or destroyed.")
            onError("Activity is not available to display ad.")
            return scope.launch { /* completed */ }
        }

        // Double-tap and concurrent presentation guard
        if (_isShowingAd.get()) {
            Log.w(TAG, "Show ad rejected: Another ad is currently presenting.")
            onError("An ad is already displaying.")
            return scope.launch { /* completed */ }
        }

        return scope.launch {
            // If no ad is cached, await the in-flight or on-demand fetch with non-blocking timeout
            if (currentRewardedAd == null) {
                val pendingDeferred = synchronized(loadLock) {
                    if (_isLoading.value && activeLoadDeferred != null) {
                        activeLoadDeferred
                    } else {
                        preloadAd(force = true)
                        activeLoadDeferred
                    }
                }

                val loadResult = withTimeoutOrNull(timeoutMs) {
                    pendingDeferred?.await()
                }

                if (!coroutineContext.isActive) {
                    Log.d(TAG, "Ad request canceled by caller before presentation.")
                    return@launch
                }

                if (loadResult != true || currentRewardedAd == null) {
                    val error = if (loadResult == null) {
                        "Ad request timed out. Please try again."
                    } else {
                        _lastErrorMessage.value ?: "Ad inventory currently unavailable. Please try again later."
                    }
                    Log.w(TAG, "Ad presentation aborted: $error")
                    onError(error)
                    return@launch
                }
            }

            if (!coroutineContext.isActive) {
                Log.d(TAG, "Ad request canceled by caller before presentation.")
                return@launch
            }

            if (activity.isFinishing || activity.isDestroyed) {
                Log.w(TAG, "Show ad aborted: Activity finished while loading.")
                onError("Activity is no longer active.")
                return@launch
            }

            val adToShow = currentRewardedAd
            if (adToShow == null) {
                onError(_lastErrorMessage.value ?: "Ad unavailable right now. Please try again later.")
                return@launch
            }

            // Lock presentation state
            _isShowingAd.set(true)
            var rewardEarned = false

            adToShow.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    Log.d(TAG, "Ad showed full screen content.")
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e(TAG, "Ad failed to show: [Code ${adError.code}] ${adError.message}")
                    _isShowingAd.set(false)
                    currentRewardedAd = null
                    _isAdReady.value = false
                    _lastErrorMessage.value = adError.message

                    scope.launch {
                        onError(adError.message)
                        // Replenish cache for subsequent requests
                        preloadAd()
                    }
                }

                override fun onAdDismissedFullScreenContent() {
                    Log.d(TAG, "Ad dismissed full screen content. Reward earned: $rewardEarned")
                    _isShowingAd.set(false)
                    currentRewardedAd = null
                    _isAdReady.value = false

                    scope.launch {
                        if (rewardEarned) {
                            onRewardEarned()
                        } else {
                            onDismissedWithoutReward()
                        }
                        // Automatically preload the NEXT ad ahead of time
                        preloadAd()
                    }
                }
            }

            try {
                adToShow.show(activity) { rewardItem ->
                    rewardEarned = true
                    Log.d(TAG, "User earned reward: ${rewardItem.amount} ${rewardItem.type}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Exception during ad presentation", t)
                _isShowingAd.set(false)
                currentRewardedAd = null
                _isAdReady.value = false
                onError(t.message ?: "Failed to display ad")
                preloadAd()
            }
        }
    }
}
