package com.reeltrak.app.features.settings.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.reeltrak.app.core.ui.components.ReFocusTopBar

/**
 * Editorial Brand Header for Settings:
 * Delegates to centralized [ReFocusTopBar] for visual consistency across tabs.
 */
@Composable
fun SettingsHeaderComponent(
    modifier: Modifier = Modifier
) {
    ReFocusTopBar(
        title = "Settings",
        modifier = modifier
    )
}
