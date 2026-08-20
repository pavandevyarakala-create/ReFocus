package com.reeltrak.app.core.navigation

import com.google.firebase.Firebase
import com.google.firebase.auth.auth

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reeltrak.app.features.auth.LoginViewModel
import com.reeltrak.app.features.auth.ui.LoginScreen
import com.reeltrak.app.features.main.ui.MainScreen

@Composable
fun RootNavigation(
    navController: NavHostController = rememberNavController(),
    viewModel: LoginViewModel = hiltViewModel()
) {
    val startDest = if (Firebase.auth.currentUser != null) "main" else "login"
    
    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable("login") {
            LoginScreen(
                onNavigateToMain = {
                    navController.navigate("main") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                viewModel = viewModel
            )
        }
        
        composable("main") {
            MainScreen(
                onNavigateToLogin = {
                    navController.navigate("login") {
                        popUpTo("main") { inclusive = true }
                    }
                },
                viewModel = viewModel
            )
        }
    }
}
