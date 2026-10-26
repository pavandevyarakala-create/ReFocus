package com.reeltrak.app.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reeltrak.app.features.auth.LoginViewModel
import com.reeltrak.app.features.auth.StartupAuthState
import com.reeltrak.app.features.auth.ui.LoginScreen
import androidx.compose.runtime.rememberCoroutineScope
import com.reeltrak.app.features.legal.ui.PrivacyPolicyScreen
import com.reeltrak.app.features.legal.ui.TermsAndConditionsScreen
import com.reeltrak.app.features.main.ui.MainScreen
import com.reeltrak.app.features.onboarding.ui.OnboardingScreen
import kotlinx.coroutines.launch

@Composable
fun RootNavigation(
    navController: NavHostController = rememberNavController(),
    viewModel: LoginViewModel = hiltViewModel()
) {
    val authState by viewModel.startupAuthState.collectAsStateWithLifecycle()
    val resolvedStartDestination = remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(authState) {
        if (resolvedStartDestination.value == null && authState != StartupAuthState.CHECKING) {
            resolvedStartDestination.value = if (authState == StartupAuthState.AUTHENTICATED || authState == StartupAuthState.FREE_MODE) {
                if (viewModel.isOnboardingNeeded()) Routes.ONBOARDING else "main"
            } else "login"
        }
    }

    val startDest = resolvedStartDestination.value
    if (startDest == null) {
        // While AUTH_CHECKING, keep a clean placeholder covered by the splash screen.
        // Do NOT render NavHost or expose LoginScreen until verification has definitively finished.
        Box(modifier = Modifier.fillMaxSize())
    } else {
        NavHost(
            navController = navController,
            startDestination = startDest
        ) {
        composable("login") {
            LoginScreen(
                onNavigateToMain = {
                    coroutineScope.launch {
                        val dest = if (viewModel.isOnboardingNeeded()) Routes.ONBOARDING else "main"
                        navController.navigate(dest) {
                            popUpTo("login") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                onNavigateToPrivacy = {
                    navController.navigate(Routes.PRIVACY_POLICY)
                },
                onNavigateToTerms = {
                    navController.navigate(Routes.TERMS_AND_CONDITIONS)
                },
                viewModel = viewModel
            )
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onSetupComplete = {
                    navController.navigate("main") {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        
        composable("main") {
            MainScreen(
                onNavigateToLogin = {
                    navController.navigate("login") {
                        popUpTo("main") { inclusive = true }
                    }
                },
                onNavigateToLoginForUpgrade = {
                    navController.navigate("login")
                },
                onNavigateToPrivacy = {
                    navController.navigate(Routes.PRIVACY_POLICY)
                },
                onNavigateToTerms = {
                    navController.navigate(Routes.TERMS_AND_CONDITIONS)
                },
                viewModel = viewModel
            )
        }

        composable(Routes.PRIVACY_POLICY) {
            PrivacyPolicyScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.TERMS_AND_CONDITIONS) {
            TermsAndConditionsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
}
