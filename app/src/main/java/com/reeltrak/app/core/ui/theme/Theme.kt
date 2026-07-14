package com.reeltrak.app.core.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.content.Context
import android.content.ContextWrapper

import androidx.compose.material3.lightColorScheme

private val GenesisColorScheme = lightColorScheme(
    primary = GenesisPrimary,
    secondary = GenesisSecondary,
    background = GenesisBackground,
    surface = GenesisSurface,
    surfaceVariant = GenesisSurface, // Surface is white per spec
    onPrimary = GenesisSurface, // Text on primary button is white
    onSecondary = GenesisSurface, // Assuming text on secondary is also white
    onBackground = GenesisTextPrimary,
    onSurface = GenesisTextPrimary,
    onSurfaceVariant = GenesisTextSecondary,
    error = GenesisError,
    outline = GenesisBorder,
    outlineVariant = GenesisBorder,
    tertiary = GenesisNeutral
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun GenesisTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            if (activity != null) {
                val window = activity.window
                window.statusBarColor = GenesisBackground.toArgb()
                window.navigationBarColor = GenesisBackground.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = GenesisColorScheme,
        typography = GenesisTypography,
        content = content
    )
}
