package com.reeltrak.app.core.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val GenesisColorScheme = lightColorScheme(
    primary = GenesisPrimary,
    secondary = GenesisSecondary,
    background = GenesisBackground,
    surface = GenesisSurface,
    surfaceVariant = GenesisSurface,
    onPrimary = GenesisSurface,
    onSecondary = GenesisSurface,
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val statusBarColor = if (darkTheme) ReFocusDarkColors.canvas.toArgb() else ReFocusLightColors.canvas.toArgb()
    val navBarColor = if (darkTheme) ReFocusDarkColors.canvas.toArgb() else ReFocusLightColors.canvas.toArgb()

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            if (activity != null) {
                val window = activity.window
                window.statusBarColor = statusBarColor
                window.navigationBarColor = navBarColor
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = ReFocusDarkColors.accent,
            onPrimary = ReFocusDarkColors.textInverse,
            primaryContainer = ReFocusDarkColors.accentSoft,
            onPrimaryContainer = ReFocusDarkColors.accent,
            inversePrimary = ReFocusDarkColors.accentDark,
            secondary = ReFocusDarkColors.accentPressed,
            onSecondary = ReFocusDarkColors.textInverse,
            secondaryContainer = ReFocusDarkColors.surfaceFloating,
            onSecondaryContainer = ReFocusDarkColors.textPrimary,
            tertiary = ReFocusDarkColors.textMuted,
            onTertiary = ReFocusDarkColors.textPrimary,
            tertiaryContainer = ReFocusDarkColors.surfaceElevated,
            onTertiaryContainer = ReFocusDarkColors.textSecondary,
            background = ReFocusDarkColors.background,
            onBackground = ReFocusDarkColors.textPrimary,
            surface = ReFocusDarkColors.surface,
            onSurface = ReFocusDarkColors.textPrimary,
            surfaceVariant = ReFocusDarkColors.surfaceElevated,
            onSurfaceVariant = ReFocusDarkColors.textSecondary,
            surfaceTint = Color.Transparent, // Crucial: prevents M3 elevation tinting from bleeding unwanted hues
            inverseSurface = ReFocusDarkColors.textPrimary,
            inverseOnSurface = ReFocusDarkColors.surface,
            error = ReFocusDarkColors.error,
            onError = ReFocusDarkColors.textInverse,
            errorContainer = ReFocusDarkColors.errorSoft,
            onErrorContainer = ReFocusDarkColors.errorText,
            outline = ReFocusDarkColors.border,
            outlineVariant = ReFocusDarkColors.surfaceStrong,
            scrim = ReFocusDarkColors.scrim
        )
    } else {
        lightColorScheme(
            primary = ReFocusLightColors.textPrimary,
            onPrimary = ReFocusLightColors.textInverse,
            primaryContainer = ReFocusLightColors.accentSoft,
            onPrimaryContainer = ReFocusLightColors.accentDark,
            inversePrimary = ReFocusLightColors.accent,
            secondary = ReFocusLightColors.accent,
            onSecondary = ReFocusLightColors.textPrimary,
            secondaryContainer = ReFocusLightColors.surfaceElevated,
            onSecondaryContainer = ReFocusLightColors.textPrimary,
            tertiary = ReFocusLightColors.textMuted,
            onTertiary = ReFocusLightColors.textInverse,
            tertiaryContainer = ReFocusLightColors.surfaceSoft,
            onTertiaryContainer = ReFocusLightColors.textSecondary,
            background = ReFocusLightColors.background,
            onBackground = ReFocusLightColors.textPrimary,
            surface = ReFocusLightColors.surface,
            onSurface = ReFocusLightColors.textPrimary,
            surfaceVariant = ReFocusLightColors.surfaceElevated,
            onSurfaceVariant = ReFocusLightColors.textSecondary,
            surfaceTint = Color.Transparent, // Crucial: prevents M3 elevation tinting from bleeding unwanted hues
            inverseSurface = ReFocusLightColors.textPrimary,
            inverseOnSurface = ReFocusLightColors.background,
            error = ReFocusLightColors.error,
            onError = ReFocusLightColors.textInverse,
            errorContainer = ReFocusLightColors.errorSoft,
            onErrorContainer = ReFocusLightColors.errorText,
            outline = ReFocusLightColors.border,
            outlineVariant = ReFocusLightColors.surfaceSoft,
            scrim = ReFocusLightColors.scrim
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GenesisTypography
    ) {
        ReFocusBrandTheme(darkTheme = darkTheme, content = content)
    }
}
