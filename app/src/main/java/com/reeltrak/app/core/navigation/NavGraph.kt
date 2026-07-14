package com.reeltrak.app.core.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reeltrak.app.features.main.ui.MainScreen
import com.reeltrak.app.features.onboarding.ui.OnboardingScreen

@Composable
fun ReelTrakNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.MAIN
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onSetupComplete = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Routes.MAIN) {
            MainScreen()
        }
    }
}
