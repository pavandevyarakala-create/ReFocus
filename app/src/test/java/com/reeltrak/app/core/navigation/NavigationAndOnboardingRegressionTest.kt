package com.reeltrak.app.core.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression Test Suite for:
 * - BUG-003: Android Back Button Navigation in MainScreen
 * - BUG-004: Onboarding reachability and first-launch state
 */
class NavigationAndOnboardingRegressionTest {

    @Test
    fun mainScreenImplementsBackHandlerToReturnToDashboard() {
        val screenPath = "src/main/java/com/reeltrak/app/features/main/ui/MainScreen.kt"
        val file = listOf(File(screenPath), File("app/$screenPath"), File("../app/$screenPath"))
            .firstOrNull { it.exists() } ?: throw IllegalStateException("MainScreen.kt not found")
        val content = file.readText()

        // BUG-003: Verify BackHandler is present and conditional on currentPage != 0
        assertTrue("MainScreen must import BackHandler", content.contains("androidx.activity.compose.BackHandler"))
        assertTrue(
            "BackHandler must be enabled only when pagerState.currentPage != 0",
            content.contains("BackHandler(enabled = pagerState.currentPage != 0)")
        )
        assertTrue(
            "BackHandler must scroll back to page 0 (Dashboard)",
            content.contains("pagerState.scrollToPage(0)")
        )
    }

    @Test
    fun rootNavigationConnectsOnboardingAndPreservesStartFlows() {
        val navPath = "src/main/java/com/reeltrak/app/core/navigation/RootNavigation.kt"
        val file = listOf(File(navPath), File("app/$navPath"), File("../app/$navPath"))
            .firstOrNull { it.exists() } ?: throw IllegalStateException("RootNavigation.kt not found")
        val content = file.readText()

        // BUG-004: Verify Onboarding route is declared and connected in NavHost
        assertTrue("RootNavigation must handle Routes.ONBOARDING", content.contains("Routes.ONBOARDING"))
        assertTrue("RootNavigation must embed OnboardingScreen", content.contains("OnboardingScreen"))
        assertTrue(
            "Onboarding completion must navigate to main and pop up to onboarding inclusively",
            content.contains("popUpTo(Routes.ONBOARDING) { inclusive = true }")
        )
        assertTrue(
            "RootNavigation must inspect isOnboardingNeeded() on startup",
            content.contains("viewModel.isOnboardingNeeded()")
        )
    }

    @Test
    fun onboardingViewModelSetsFirstRunDateOnSetupComplete() {
        val vmPath = "src/main/java/com/reeltrak/app/features/onboarding/viewmodel/OnboardingViewModel.kt"
        val file = listOf(File(vmPath), File("app/$vmPath"), File("../app/$vmPath"))
            .firstOrNull { it.exists() } ?: throw IllegalStateException("OnboardingViewModel.kt not found")
        val content = file.readText()

        // BUG-004: When daily intent is saved during onboarding, firstRunDate must be set
        assertTrue(
            "OnboardingViewModel must persist firstRunDate upon saving daily intent",
            content.contains("preferencesRepository.setFirstRunDate")
        )
    }

    @Test
    fun loginViewModelEvaluatesOnboardingNeedCorrectly() {
        val vmPath = "src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt"
        val file = listOf(File(vmPath), File("app/$vmPath"), File("../app/$vmPath"))
            .firstOrNull { it.exists() } ?: throw IllegalStateException("LoginViewModel.kt not found")
        val content = file.readText()

        assertTrue(
            "LoginViewModel must implement isOnboardingNeeded()",
            content.contains("suspend fun isOnboardingNeeded(): Boolean")
        )
        assertTrue(
            "isOnboardingNeeded must check firstRunDate",
            content.contains("firstRunDate")
        )
        assertTrue(
            "isOnboardingNeeded must check defaultDailyLimit and dailyIntent",
            content.contains("defaultDailyLimit") && content.contains("dailyIntent")
        )
        assertFalse(
            "isOnboardingNeeded must NOT depend on remote Firestore queries",
            content.contains("Firebase.firestore")
        )
    }

    @Test
    fun testLocalFirstTourCompletion_doesNotQueryRemoteFirestore() {
        val repoPath = "src/main/java/com/reeltrak/app/data/repository/OnboardingRepositoryImpl.kt"
        val file = listOf(File(repoPath), File("app/$repoPath"), File("../app/$repoPath"))
            .firstOrNull { it.exists() } ?: throw IllegalStateException("OnboardingRepositoryImpl.kt not found")
        val content = file.readText()

        assertFalse(
            "OnboardingRepositoryImpl must not query remote onboarding/tour document",
            content.contains("collection(\"onboarding\")")
        )
    }
}
