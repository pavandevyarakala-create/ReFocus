package com.reeltrak.app.core.tour

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Target UI element IDs used throughout the guided onboarding tour.
 */
object TourTargets {
    const val HERO_PROGRESS_CARD = "hero_progress_card"
    const val STRICT_MODE_CARD = "strict_mode_card"
    const val LIVE_TODAYS_STATUS = "todays_status_card"
    const val ANALYTICS_TAB = "nav_analytics_tab"
    const val CALENDAR_TAB = "nav_calendar_tab"
    const val SETTINGS_TAB = "nav_settings_tab"
}

/**
 * Preferred position of the tooltip relative to the target element.
 */
enum class TooltipPosition {
    ABOVE,
    BELOW,
    AUTO
}

/**
 * Defines a step in the onboarding tour.
 */
data class TourStep(
    val id: String,
    val targetId: String,
    val title: String,
    val description: String,
    val targetScreenRoute: String,
    val preferredPosition: TooltipPosition = TooltipPosition.AUTO
)

/**
 * Centralized coordinator for the App Tour state and element coordinate registry.
 */
class TourCoordinator {

    private val _targetBounds = mutableMapOf<String, Rect>()
    private val _boundsVersion = MutableStateFlow(0)
    val boundsVersion: StateFlow<Int> = _boundsVersion.asStateFlow()

    private val _isTourActive = MutableStateFlow(false)
    val isTourActive: StateFlow<Boolean> = _isTourActive.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    val steps: List<TourStep> = listOf(
        TourStep(
            id = "reel_counter_limit",
            targetId = TourTargets.HERO_PROGRESS_CARD,
            title = "Reel Counter & Daily Limit",
            description = "Track your scrolled reels in real time and customize your daily goal. Tap the limit to adjust your daily allowance.",
            targetScreenRoute = "dashboard",
            preferredPosition = TooltipPosition.BELOW
        ),
        TourStep(
            id = "strict_mode",
            targetId = TourTargets.STRICT_MODE_CARD,
            title = "Strict Mode",
            description = "Lock down your limits! Once enabled, strict mode cannot be bypassed until tomorrow.",
            targetScreenRoute = "dashboard",
            preferredPosition = TooltipPosition.BELOW
        ),
        TourStep(
            id = "today_status",
            targetId = TourTargets.LIVE_TODAYS_STATUS,
            title = "Today's Status & Feeds",
            description = "Quick breakdown of your active reel watching sessions across Instagram and YouTube.",
            targetScreenRoute = "dashboard",
            preferredPosition = TooltipPosition.ABOVE
        ),
        TourStep(
            id = "analytics",
            targetId = TourTargets.ANALYTICS_TAB,
            title = "Analytics & Trends",
            description = "Review your lifetime reels, daily averages, and weekly scroll patterns over time.",
            targetScreenRoute = "dashboard", // Highlights the bottom nav tab
            preferredPosition = TooltipPosition.ABOVE
        ),
        TourStep(
            id = "calendar_history",
            targetId = TourTargets.CALENDAR_TAB,
            title = "Habit Calendar & Streaks",
            description = "Track your daily consistency, earn achievement badges, and review past history.",
            targetScreenRoute = "dashboard", // Highlights the bottom nav tab
            preferredPosition = TooltipPosition.ABOVE
        ),
        TourStep(
            id = "settings",
            targetId = TourTargets.SETTINGS_TAB,
            title = "Settings & Subscription",
            description = "Manage notifications, view your active subscription status, restore cloud backups, or replay this tour anytime.",
            targetScreenRoute = "dashboard", // Highlights the bottom nav tab
            preferredPosition = TooltipPosition.ABOVE
        )
    )

    fun registerTarget(targetId: String, bounds: Rect) {
        _targetBounds[targetId] = bounds
        _boundsVersion.value += 1
    }

    fun getTargetBounds(targetId: String): Rect? {
        return _targetBounds[targetId]
    }

    fun startTour() {
        _currentStepIndex.value = 0
        _isTourActive.value = true
    }

    fun nextStep() {
        if (_currentStepIndex.value < steps.size - 1) {
            _currentStepIndex.value += 1
        } else {
            endTour()
        }
    }

    fun previousStep() {
        if (_currentStepIndex.value > 0) {
            _currentStepIndex.value -= 1
        }
    }

    fun endTour() {
        _isTourActive.value = false
        _currentStepIndex.value = 0
    }
}

val LocalTourCoordinator = staticCompositionLocalOf<TourCoordinator?> { null }

/**
 * Modifier to register an element's bounds with the tour coordinator.
 */
fun Modifier.tourTarget(targetId: String, coordinator: TourCoordinator?): Modifier {
    if (coordinator == null) return this
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        if (bounds.width > 0 && bounds.height > 0) {
            coordinator.registerTarget(targetId, bounds)
        }
    }
}
