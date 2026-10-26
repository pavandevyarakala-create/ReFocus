package com.reeltrak.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.reeltrak.app.core.navigation.RootNavigation
import com.reeltrak.app.domain.managers.SubscriptionManager
import com.reeltrak.app.features.auth.LoginViewModel
import com.reeltrak.app.features.auth.StartupAuthState
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity(), PaymentResultWithDataListener {
    
    @Inject
    lateinit var subscriptionManager: Lazy<SubscriptionManager>

    @Inject
    lateinit var razorpayPurchaseService: com.reeltrak.app.data.services.RazorpaySubscriptionPurchaseServiceImpl

    @Inject
    lateinit var rewardedAdManager: Lazy<com.reeltrak.app.core.ads.RewardedAdManager>

    @Inject
    lateinit var inAppUpdateManager: com.reeltrak.app.core.update.InAppUpdateManager

    @Inject
    lateinit var preferencesRepository: com.reeltrak.app.domain.repository.PreferencesRepository

    @Inject
    lateinit var dayManager: Lazy<com.reeltrak.app.services.tracking.DayManager>

    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        inAppUpdateManager.handleActivityResult(result.resultCode)
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        razorpayPurchaseService.onPaymentSuccess(razorpayPaymentId, paymentData)
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        razorpayPurchaseService.onPaymentError(code, response, paymentData)
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("STARTUP_DEBUG", "MainActivity: onStart() called")
        razorpayPurchaseService.attachActivity(this)
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("STARTUP_DEBUG", "MainActivity: onPause() called")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("STARTUP_DEBUG", "MainActivity: onStop() called")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("STARTUP_DEBUG", "MainActivity: onDestroy() called")
        razorpayPurchaseService.detachActivity()
    }

    override fun onResume() {
        super.onResume()
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        android.util.Log.d("STARTUP_DEBUG", "MainActivity: onResume() called. currentUser UID=$uid")
        razorpayPurchaseService.attachActivity(this)
        razorpayPurchaseService.onActivityResumed()

        inAppUpdateManager.resumeImmediateUpdateIfInProgress(this, updateLauncher)
        dayManager.get().checkDayTransition()
        lifecycleScope.launch {
            inAppUpdateManager.checkForUpdate()
        }

        lifecycleScope.launch {
            // Briefly yield startup priority to splash animation and UI transition before background sync check
            kotlinx.coroutines.delay(2400)
            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                android.util.Log.d("STARTUP_DEBUG", "MainActivity: onResume() skipping subscription sync (user not signed in)")
                return@launch
            }
            android.util.Log.d("STARTUP_DEBUG", "MainActivity: onResume() triggering background freshness checks")
            val currentStatus = subscriptionManager.get().currentStatus()
            if (currentStatus == com.reeltrak.app.domain.models.SubscriptionStatus.NONE) {
                subscriptionManager.get().ensureSubscription()
            }
            subscriptionManager.get().syncIfStale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        android.util.Log.d("STARTUP_DEBUG", "MainActivity: onCreate() called. savedInstanceState=${savedInstanceState != null}, currentUser UID=$uid")
        razorpayPurchaseService.attachActivity(this)

        lifecycleScope.launch {
            inAppUpdateManager.checkForUpdate()
        }
        
        setContent {
            val updateStatus by inAppUpdateManager.updateStatus.collectAsStateWithLifecycle()
            val isUpdateRequired = updateStatus == com.reeltrak.app.core.update.AppUpdateStatus.UPDATE_REQUIRED ||
                    updateStatus == com.reeltrak.app.core.update.AppUpdateStatus.UPDATE_FAILED ||
                    updateStatus == com.reeltrak.app.core.update.AppUpdateStatus.UPDATE_IN_PROGRESS

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                // Handled
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permission = Manifest.permission.POST_NOTIFICATIONS
                    if (ContextCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(permission)
                    }
                }
            }
            
            val loginViewModel: LoginViewModel = hiltViewModel()
            val authState by loginViewModel.startupAuthState.collectAsStateWithLifecycle()

            var isSplashAnimationFinished by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            var isSplashActive by androidx.compose.runtime.saveable.rememberSaveable {
                androidx.compose.runtime.mutableStateOf(true)
            }
            var shouldWarmUpApp by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(!isSplashActive)
            }

            LaunchedEffect(Unit) {
                if (isSplashActive) {
                    // Keep main thread 100% dedicated to smooth 60 FPS logo animation.
                    // Warm up RootNavigation, ViewModels, and Dashboard during the static completed-logo hold.
                    kotlinx.coroutines.delay(920)
                    shouldWarmUpApp = true
                    kotlinx.coroutines.delay(80) // ~1000ms mark
                    razorpayPurchaseService.preload(this@MainActivity)
                    rewardedAdManager.get().initialize()
                } else {
                    rewardedAdManager.get().initialize()
                }
            }

            // Splash stays active as the visual cover until BOTH the animation sequence finishes
            // AND background authentication/session verification has resolved.
            LaunchedEffect(isSplashAnimationFinished, authState) {
                if (isSplashAnimationFinished && authState != StartupAuthState.CHECKING) {
                    isSplashActive = false
                }
            }
            
            com.reeltrak.app.core.ui.theme.GenesisTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isUpdateRequired) {
                        com.reeltrak.app.features.update.ui.MandatoryUpdateScreen(
                            updateStatus = updateStatus,
                            onUpdateClicked = {
                                inAppUpdateManager.startImmediateUpdate(this@MainActivity, updateLauncher)
                            }
                        )
                    } else {
                        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                            if (shouldWarmUpApp) {
                                val navController = rememberNavController()
                                RootNavigation(
                                    navController = navController,
                                    viewModel = loginViewModel
                                )
                            }

                            androidx.compose.animation.AnimatedVisibility(
                                visible = isSplashActive,
                                enter = androidx.compose.animation.EnterTransition.None,
                                exit = androidx.compose.animation.fadeOut(
                                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 300)
                                )
                            ) {
                                com.reeltrak.app.features.splash.ui.ReFocusSplashScreen(
                                    onSplashFinished = { isSplashAnimationFinished = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
