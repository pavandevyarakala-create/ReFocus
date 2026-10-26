package com.reeltrak.app.core.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Re:Focus Visual Identity Design Tokens.
 *
 * Brand Philosophy: "RE:FOCUS — Intentional Digital Discipline"
 * Depth Formula: DEEP CHARCOAL / IVORY SURFACES + SPECULAR BEVELS + AMBIENT LIME LIGHTING (#B8D94E).
 *
 * Every visual color in the application belongs to this theme system and has intentional Light + Dark values.
 */
data class ReFocusColorPalette(
    // Surface Elevation Hierarchy
    val background: Color,              // Level 0: App Canvas
    val surface: Color,                 // Level 1: Primary Surface / Card
    val surfaceElevated: Color,         // Level 2: Elevated Surface / Nested Card
    val surfaceFloating: Color,         // Level 3: Floating Surface / Dock
    val surfaceStrong: Color,           // Level 4: Strong Neutral Container / Contrast Surface
    val surfaceSoft: Color,             // Soft Neutral Accent Surface / Chip Background
    val surfaceRecessed: Color,         // Recessed Sunken Surface (inputs, sockets, tracks)

    // Borders & Dividers
    val border: Color,                  // Primary Border / Hairlines
    val borderSubtle: Color,            // Subtle Internal Separators
    val divider: Color,                 // Content Dividers

    // Typography & Content Hierarchy
    val textPrimary: Color,             // Primary High-Emphasis Text
    val textSecondary: Color,           // Secondary Medium-Emphasis Text
    val textMuted: Color,               // Tertiary / Caption / Metadata Text
    val textDisabled: Color,            // Disabled State Text
    val textInverse: Color,             // Inverted Text (e.g. crisp dark text on lime)

    // Icons
    val iconPrimary: Color,             // High-Emphasis Icons
    val iconSecondary: Color,           // Medium-Emphasis Icons
    val iconMuted: Color,               // Low-Emphasis Icons

    // Brand Lime Accent System
    val accent: Color,                  // Signature Muted Lime #B8D94E
    val accentPressed: Color,           // Pressed Lime State
    val accentSoft: Color,              // Soft Lime Surface / Container
    val accentDark: Color,              // Deep Lime for light-mode text / borders
    val accentHighlight: Color,         // Luminous Lime Highlight for dark surfaces

    // Semantic Status Systems
    val success: Color,                 // Success State (Within Goal, Completed Day)
    val successSoft: Color,             // Success Container / Soft Surface
    val successBorder: Color,           // Success Hairline Border
    val successText: Color,             // High-Contrast Success Text

    val warning: Color,                 // Warning State (Attention, Near Limit, Limit Reached)
    val warningSoft: Color,             // Warning Container / Soft Surface
    val warningBorder: Color,           // Warning Hairline Border
    val warningText: Color,             // High-Contrast Warning Text

    val error: Color,                   // Error / Destructive (True Error, Deletion, Network Fail)
    val errorSoft: Color,               // Error Container / Soft Surface
    val errorBorder: Color,             // Error Hairline Border
    val errorText: Color,               // High-Contrast Error Text

    val info: Color,                    // Informational State
    val infoSoft: Color,                // Informational Container / Soft Surface
    val infoBorder: Color,              // Informational Hairline Border
    val infoText: Color,                // High-Contrast Informational Text

    // Progress Indicators
    val progress: Color,                // Active Progress Indicator
    val progressTrack: Color,           // Sunken Progress Track

    // Charts & Analytics Data
    val chart: Color,                   // Primary Chart Series
    val chartSecondary: Color,          // Secondary Comparison Series
    val chartTrack: Color,              // Chart Background Track
    val chartGrid: Color,               // Subtle Grid Lines

    // Navigation & Floating Dock
    val navigationBackground: Color,    // Dock Floating Surface
    val navigationSelected: Color,      // Active Capsule Background
    val navigationIcon: Color,          // Inactive Dock Icon
    val navigationIconSelected: Color,  // Active Dock Icon
    val navigationText: Color,          // Inactive Dock Label
    val navigationTextSelected: Color,  // Active Dock Label
    val navigationBorder: Color,        // Dock Border

    // Streaks & Gamification
    val streakFlame: Color,             // Disciplined Warm Flame
    val streakFlameGlow: Color,         // Flame Ambient Glow

    // Depth, Shadows & Specular Bevels
    val shadowAmbient: Color,           // Ambient Diffuse Drop Shadow
    val shadowSpot: Color,              // Contact Spot Drop Shadow
    val shadowAccent: Color,            // Lime Glow Shadow
    val specularHighlight: Color,       // Physical Bevel Top Highlight
    val glowLime: Color,                // Subtle Lime Alpha Glow
    val scrim: Color,                   // Modal Scrim / Backdrop

    // Floating Reel Counter
    val counterSurface: Color,
    val counterSurfaceOverLimit: Color,
    val counterBorder: Color,
    val counterBorderOverLimit: Color,
    val counterTextPrimary: Color,
    val counterTextSecondary: Color,
    val counterAccent: Color,

    val isDark: Boolean
) {
    // Backward-Compatible Semantic Aliases & Required Design Tokens
    val canvas: Color get() = background
    val surfaceContainer: Color get() = surfaceFloating
    val surfaceSubtle: Color get() = surfaceSoft
    val inkPrimary: Color get() = textPrimary
    val inkSecondary: Color get() = textSecondary
    val inkMuted: Color get() = textMuted
    val lineHairline: Color get() = border
    val lineSubtle: Color get() = borderSubtle
    val lineStrong: Color get() = surfaceStrong
    val accentLime: Color get() = accent
    val accentLimeDark: Color get() = accentPressed
    val accentLimeSubtle: Color get() = accentSoft
    val destructive: Color get() = error
    val destructiveSubtle: Color get() = errorSoft
    val warningSubtle: Color get() = warningSoft
    val surfaceLevel0: Color get() = background
    val surfaceLevel1: Color get() = surface
    val surfaceLevel2: Color get() = surfaceElevated
    val surfaceLevel3: Color get() = surfaceFloating
    val borderDivider: Color get() = border
    val textTertiary: Color get() = textMuted
    val limeSignature: Color get() = accent
    val limePressed: Color get() = accentPressed
    val limeSoft: Color get() = accentSoft
    val disabled: Color get() = textDisabled
    val selected: Color get() = accentSoft
    val pressed: Color get() = accentPressed
}

// LIGHT THEME: Warm ivory foundation, crisp white surfaces, charcoal typography, signature lime.
val ReFocusLightColors = ReFocusColorPalette(
    background = ReFocusLightBackground,
    surface = ReFocusLightSurface,
    surfaceElevated = ReFocusLightSurfaceElevated,
    surfaceFloating = ReFocusLightSurfaceFloating,
    surfaceStrong = ReFocusLightSurfaceStrong,
    surfaceSoft = ReFocusLightSurfaceSoft,
    surfaceRecessed = ReFocusLightSurfaceRecessed,

    border = ReFocusLightBorder,
    borderSubtle = ReFocusLightBorderSubtle,
    divider = ReFocusLightBorder,

    textPrimary = ReFocusLightTextPrimary,
    textSecondary = ReFocusLightTextSecondary,
    textMuted = ReFocusLightTextMuted,
    textDisabled = ReFocusLightTextDisabled,
    textInverse = Color(0xFFFFFFFF),

    iconPrimary = ReFocusLightTextPrimary,
    iconSecondary = ReFocusLightTextSecondary,
    iconMuted = ReFocusLightTextMuted,

    accent = ReFocusLimePrimary,
    accentPressed = ReFocusLimePressed,
    accentSoft = ReFocusLimeSoftLight,
    accentDark = ReFocusLimeDark,
    accentHighlight = ReFocusLimeHighlight,

    success = ReFocusSuccessLightAccent,
    successSoft = ReFocusSuccessLightSoft,
    successBorder = ReFocusSuccessLightBorder,
    successText = ReFocusSuccessLightText,

    warning = ReFocusWarningLightAccent,
    warningSoft = ReFocusWarningLightSoft,
    warningBorder = ReFocusWarningLightBorder,
    warningText = ReFocusWarningLightText,

    error = ReFocusErrorLightAccent,
    errorSoft = ReFocusErrorLightSoft,
    errorBorder = ReFocusErrorLightBorder,
    errorText = ReFocusErrorLightText,

    info = ReFocusInfoLightAccent,
    infoSoft = ReFocusInfoLightSoft,
    infoBorder = ReFocusInfoLightBorder,
    infoText = ReFocusInfoLightText,

    progress = ReFocusLimePrimary,
    progressTrack = ReFocusLightSurfaceSoft,

    chart = ReFocusLimePrimary,
    chartSecondary = ReFocusLightSurfaceStrong,
    chartTrack = ReFocusLightSurfaceSoft,
    chartGrid = ReFocusLightBorder.copy(alpha = 0.5f),

    navigationBackground = ReFocusLightSurface,
    navigationSelected = ReFocusLimePrimary,
    navigationIcon = ReFocusLightTextSecondary,
    navigationIconSelected = ReFocusLightTextPrimary,
    navigationText = ReFocusLightTextMuted,
    navigationTextSelected = ReFocusLightTextPrimary,
    navigationBorder = ReFocusLightBorder,

    streakFlame = ReFocusFlameLight,
    streakFlameGlow = ReFocusFlameLightGlow,

    shadowAmbient = Color(0x12151619),
    shadowSpot = Color(0x18151619),
    shadowAccent = Color(0x30B8D94E),
    specularHighlight = Color(0x80FFFFFF),
    glowLime = Color(0x1EB8D94E),
    scrim = Color(0x66000000),

    counterSurface = ReFocusLightSurface,
    counterSurfaceOverLimit = ReFocusWarningLightSoft,
    counterBorder = ReFocusLightBorder,
    counterBorderOverLimit = ReFocusWarningLightBorder,
    counterTextPrimary = ReFocusLightTextPrimary,
    counterTextSecondary = ReFocusLightTextSecondary,
    counterAccent = ReFocusLimePrimary,

    isDark = false
)

// DARK THEME: Deep charcoal/near-black foundation, warm-white typography, luminous muted-lime.
val ReFocusDarkColors = ReFocusColorPalette(
    background = ReFocusDarkBackground,
    surface = ReFocusDarkSurface,
    surfaceElevated = ReFocusDarkSurfaceElevated,
    surfaceFloating = ReFocusDarkSurfaceFloating,
    surfaceStrong = ReFocusDarkSurfaceStrong,
    surfaceSoft = ReFocusDarkSurfaceSoft,
    surfaceRecessed = ReFocusDarkSurfaceRecessed,

    border = ReFocusDarkBorder,
    borderSubtle = ReFocusDarkBorderSubtle,
    divider = ReFocusDarkBorder,

    textPrimary = ReFocusDarkTextPrimary,
    textSecondary = ReFocusDarkTextSecondary,
    textMuted = ReFocusDarkTextMuted,
    textDisabled = ReFocusDarkTextDisabled,
    textInverse = Color(0xFF151619),

    iconPrimary = ReFocusDarkTextPrimary,
    iconSecondary = ReFocusDarkTextSecondary,
    iconMuted = ReFocusDarkTextMuted,

    accent = ReFocusLimePrimary,
    accentPressed = ReFocusLimePressed,
    accentSoft = ReFocusLimeSoftDark,
    accentDark = ReFocusLimeDark,
    accentHighlight = ReFocusLimeHighlight,

    success = ReFocusSuccessDarkAccent,
    successSoft = ReFocusSuccessDarkSoft,
    successBorder = ReFocusSuccessDarkBorder,
    successText = ReFocusSuccessDarkText,

    warning = ReFocusWarningDarkAccent,
    warningSoft = ReFocusWarningDarkSoft,
    warningBorder = ReFocusWarningDarkBorder,
    warningText = ReFocusWarningDarkText,

    error = ReFocusErrorDarkAccent,
    errorSoft = ReFocusErrorDarkSoft,
    errorBorder = ReFocusErrorDarkBorder,
    errorText = ReFocusErrorDarkText,

    info = ReFocusInfoDarkAccent,
    infoSoft = ReFocusInfoDarkSoft,
    infoBorder = ReFocusInfoDarkBorder,
    infoText = ReFocusInfoDarkText,

    progress = ReFocusLimePrimary,
    progressTrack = ReFocusDarkSurfaceFloating,

    chart = ReFocusLimePrimary,
    chartSecondary = ReFocusDarkSurfaceStrong,
    chartTrack = ReFocusDarkSurfaceFloating,
    chartGrid = ReFocusDarkBorder.copy(alpha = 0.5f),

    navigationBackground = ReFocusDarkSurfaceFloating,
    navigationSelected = ReFocusLimePrimary,
    navigationIcon = ReFocusDarkTextSecondary,
    navigationIconSelected = Color(0xFF151619),
    navigationText = ReFocusDarkTextMuted,
    navigationTextSelected = Color(0xFF151619),
    navigationBorder = ReFocusDarkBorder,

    streakFlame = ReFocusFlameDark,
    streakFlameGlow = ReFocusFlameDarkGlow,

    shadowAmbient = Color(0x66000000),
    shadowSpot = Color(0x66000000),
    shadowAccent = Color(0x2EB8D94E),
    specularHighlight = Color(0x22FFFFFF),
    glowLime = Color(0x2EB8D94E),
    scrim = Color(0xB3000000),

    counterSurface = ReFocusDarkSurfaceFloating,
    counterSurfaceOverLimit = ReFocusWarningDarkSoft,
    counterBorder = ReFocusDarkBorder,
    counterBorderOverLimit = ReFocusWarningDarkBorder,
    counterTextPrimary = ReFocusDarkTextPrimary,
    counterTextSecondary = ReFocusDarkTextSecondary,
    counterAccent = ReFocusLimePrimary,

    isDark = true
)

val LocalReFocusColors = staticCompositionLocalOf { ReFocusLightColors }

/**
 * Direct Centralized Re:Focus Semantic Theme Tokens.
 *
 * Usage:
 * ReFocusColors.background
 * ReFocusColors.surface
 * ReFocusColors.accent
 * ReFocusColors.warning
 * ReFocusColors.warningSoft
 * ReFocusColors.textPrimary
 */
object ReFocusColors {
    val background: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.background
    val surface: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.surface
    val surfaceElevated: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.surfaceElevated
    val surfaceFloating: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.surfaceFloating
    val surfaceStrong: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.surfaceStrong
    val surfaceSoft: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.surfaceSoft
    val surfaceRecessed: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.surfaceRecessed

    val border: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.border
    val borderSubtle: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.borderSubtle
    val divider: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.divider

    val textPrimary: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.textPrimary
    val textSecondary: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.textSecondary
    val textMuted: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.textMuted
    val textDisabled: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.textDisabled
    val textInverse: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.textInverse

    val iconPrimary: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.iconPrimary
    val iconSecondary: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.iconSecondary
    val iconMuted: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.iconMuted

    val accent: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.accent
    val accentPressed: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.accentPressed
    val accentSoft: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.accentSoft
    val accentDark: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.accentDark
    val accentHighlight: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.accentHighlight

    val success: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.success
    val successSoft: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.successSoft
    val successBorder: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.successBorder
    val successText: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.successText

    val warning: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.warning
    val warningSoft: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.warningSoft
    val warningBorder: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.warningBorder
    val warningText: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.warningText

    val error: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.error
    val errorSoft: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.errorSoft
    val errorBorder: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.errorBorder
    val errorText: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.errorText

    val info: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.info
    val infoSoft: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.infoSoft
    val infoBorder: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.infoBorder
    val infoText: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.infoText

    val progress: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.progress
    val progressTrack: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.progressTrack

    val chart: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.chart
    val chartSecondary: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.chartSecondary
    val chartTrack: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.chartTrack
    val chartGrid: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.chartGrid

    val navigationBackground: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.navigationBackground
    val navigationSelected: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.navigationSelected
    val navigationIcon: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.navigationIcon
    val navigationIconSelected: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.navigationIconSelected
    val navigationText: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.navigationText
    val navigationTextSelected: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.navigationTextSelected
    val navigationBorder: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.navigationBorder

    val streakFlame: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.streakFlame
    val streakFlameGlow: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.streakFlameGlow

    val shadowAmbient: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.shadowAmbient
    val shadowSpot: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.shadowSpot
    val shadowAccent: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.shadowAccent
    val specularHighlight: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.specularHighlight
    val glowLime: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.glowLime
    val scrim: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.scrim

    val counterSurface: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.counterSurface
    val counterSurfaceOverLimit: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.counterSurfaceOverLimit
    val counterBorder: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.counterBorder
    val counterBorderOverLimit: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.counterBorderOverLimit
    val counterTextPrimary: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.counterTextPrimary
    val counterTextSecondary: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.counterTextSecondary
    val counterAccent: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.counterAccent

    val disabled: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.disabled
    val selected: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.selected
    val pressed: Color @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.pressed
    val isDark: Boolean @Composable @ReadOnlyComposable get() = ReFocusTheme.colors.isDark
}

object ReFocusTheme {
    val colors: ReFocusColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalReFocusColors.current

    /**
     * Backward-compatible static color definitions.
     */
    object Colors {
        val Canvas get() = ReFocusLightColors.canvas
        val Surface get() = ReFocusLightColors.surface
        val SurfaceSubtle get() = ReFocusLightColors.surfaceSubtle
        val SurfaceDark get() = Color(0xFF141519)
        val InkPrimary get() = ReFocusLightColors.inkPrimary
        val InkSecondary get() = ReFocusLightColors.inkSecondary
        val InkMuted get() = ReFocusLightColors.inkMuted
        val InkInverse get() = Color(0xFFF6F6F8)
        val LineHairline get() = ReFocusLightColors.lineHairline
        val LineStrong get() = ReFocusLightColors.lineStrong
        val FocusIndigo get() = ReFocusLightColors.inkPrimary
        val FocusIndigoSubtle get() = ReFocusLightColors.accentLimeSubtle
        val Emerald get() = ReFocusSuccessLightAccent
        val EmeraldSubtle get() = ReFocusSuccessLightSoft
        val Amber get() = ReFocusWarningLightAccent
        val AmberSubtle get() = ReFocusWarningLightSoft
        val Crimson get() = ReFocusErrorLightAccent
        val CrimsonSubtle get() = ReFocusErrorLightSoft
    }

    object Dimens {
        val RadiusXs = 6.dp
        val RadiusSm = 12.dp
        val RadiusMd = 18.dp
        val RadiusLg = 24.dp
        val RadiusPill = 999.dp

        val HairlineWidth: Dp = 1.dp
        val DividerWidth: Dp = 0.5.dp

        val TopHeaderTopPadding: Dp = 8.dp
        val TopHeaderBottomPadding: Dp = 4.dp
        val TopBarTopPadding: Dp = 8.dp
        val TopBarBottomPadding: Dp = 4.dp
        val TopBarSpacing: Dp = 10.dp
        val TopBarHeight: Dp = 50.dp
        val TopBarRadius: Dp = 16.dp
        val TopBarShape = RoundedCornerShape(TopBarRadius)
        val TopBarTopMargin: Dp = 8.dp
        val TopBarHorizontalMargin: Dp = 16.dp
        val TopBarContentTopOffset: Dp = 70.dp
        val ScreenBottomContentPadding: Dp = 16.dp
        val BrandDotSize: Dp = 6.dp
        val BrandDotRadius: Dp = 1.5.dp
        val ProfileButtonSize: Dp = 34.dp
        val ProfileButtonRadius: Dp = 10.dp
        val ProfileButtonShape = RoundedCornerShape(ProfileButtonRadius)

        val ShapeXs = RoundedCornerShape(RadiusXs)
        val ShapeSm = RoundedCornerShape(RadiusSm)
        val ShapeMd = RoundedCornerShape(RadiusMd)
        val ShapeLg = RoundedCornerShape(RadiusLg)
        val ShapePill = RoundedCornerShape(RadiusPill)

        val Hairline: BorderStroke
            @Composable
            @ReadOnlyComposable
            get() = BorderStroke(HairlineWidth, LocalReFocusColors.current.lineHairline)

        val BorderCrimson: BorderStroke
            @Composable
            @ReadOnlyComposable
            get() = BorderStroke(HairlineWidth, LocalReFocusColors.current.destructive)
    }

    object Typography {
        val BrandTag = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp
        )

        val TopBarPageTitle = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = (-0.2).sp
        )

        val TopBarBrandName = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = 0.2.sp
        )

        val TopBarTitle = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            letterSpacing = (-0.3).sp,
            lineHeight = 28.sp
        )

        val TopBarSubtitle = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.5.sp,
            letterSpacing = 0.1.sp,
            lineHeight = 17.sp
        )

        val TopHeaderTitle get() = TopBarTitle
        val TopHeaderSubtitle get() = TopBarSubtitle

        val EditorialHeader = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            letterSpacing = (-0.5).sp,
            lineHeight = 34.sp
        )

        val BigMetric = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 38.sp,
            letterSpacing = (-0.8).sp,
            lineHeight = 42.sp
        )

        val SectionTitle = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp
        )

        val Headline = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            letterSpacing = 0.sp
        )

        val Body = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 13.5.sp,
            lineHeight = 19.sp
        )

        val Metadata = TextStyle(
            fontFamily = SoraFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            letterSpacing = 0.2.sp
        )

        // Compatibility Aliases
        val SectionTag: TextStyle get() = SectionTitle
        val BodyText: TextStyle get() = Body
        val CardHeadline: TextStyle get() = Headline
        val HeaderTitle: TextStyle get() = EditorialHeader
        val MetadataText: TextStyle get() = Metadata
        val MetricNumber: TextStyle get() = BigMetric
    }
}

/**
 * Specular Bevel Border:
 * Simulates a physical 3D edge with top-light reflection fading into subtle ambient shadow.
 */
@Composable
fun specularBorder(
    strokeWidth: Dp = 1.dp,
    shape: RoundedCornerShape = ReFocusTheme.Dimens.ShapeLg
): BorderStroke {
    val colors = ReFocusTheme.colors
    val brush = Brush.verticalGradient(
        0.0f to colors.specularHighlight,
        0.4f to colors.border,
        1.0f to colors.borderSubtle
    )
    return BorderStroke(strokeWidth, brush)
}

/**
 * 3D Ambient Radial Glow Backdrop:
 * Simulates cinematic studio lighting behind heroes and profile cards.
 */
@Composable
fun AmbientRadialBackdrop(
    modifier: Modifier = Modifier,
    glowColor: Color = ReFocusTheme.colors.accentLime,
    glowAlpha: Float = 0.22f
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.75f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowColor.copy(alpha = glowAlpha),
                    glowColor.copy(alpha = glowAlpha * 0.45f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            center = center,
            radius = radius
        )
    }
}

/**
 * Original Re:Focus 3D Visual Motif: The Precision Focus Aperture.
 */
@Composable
fun FocusAperture3D(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 96.dp,
    coreColor: Color = ReFocusTheme.colors.accentLime
) {
    val colors = ReFocusTheme.colors

    Box(
        modifier = modifier.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f - 4f
            val midRadius = outerRadius * 0.76f
            val innerRadius = outerRadius * 0.46f

            // 1. Ambient back-glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor.copy(alpha = 0.35f),
                        coreColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = outerRadius * 1.2f
                ),
                center = center,
                radius = outerRadius * 1.2f
            )

            // 2. Outer precision calibration ring (dashed ticks)
            drawCircle(
                color = colors.surfaceStrong.copy(alpha = 0.6f),
                center = center,
                radius = outerRadius,
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                )
            )

            // 3. Middle metallic beveled ring with specular gradient
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        colors.specularHighlight,
                        colors.border,
                        colors.surfaceFloating,
                        colors.specularHighlight.copy(alpha = 0.1f)
                    ),
                    start = Offset(center.x - midRadius, center.y - midRadius),
                    end = Offset(center.x + midRadius, center.y + midRadius)
                ),
                center = center,
                radius = midRadius,
                style = Stroke(width = 3.5f)
            )

            // 4. Inner depth recess
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.surfaceFloating,
                        colors.background
                    ),
                    center = center,
                    radius = innerRadius
                ),
                center = center,
                radius = innerRadius
            )

            // 5. Glowing iris focal core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor,
                        coreColor.copy(alpha = 0.75f),
                        coreColor.copy(alpha = 0.1f)
                    ),
                    center = center,
                    radius = innerRadius * 0.65f
                ),
                center = center,
                radius = innerRadius * 0.65f
            )

            // 6. Precision crosshairs
            val crosshairLen = innerRadius * 0.35f
            drawLine(
                color = colors.background,
                start = Offset(center.x - crosshairLen, center.y),
                end = Offset(center.x + crosshairLen, center.y),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = colors.background,
                start = Offset(center.x, center.y - crosshairLen),
                end = Offset(center.x, center.y + crosshairLen),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Backward-compatible compact focal reticle mark.
 */
@Composable
fun FocusReticleMark(
    modifier: Modifier = Modifier,
    color: Color = ReFocusTheme.colors.accentLime
) {
    FocusAperture3D(modifier = modifier, sizeDp = 36.dp, coreColor = color)
}

/**
 * Re:Focus Brand Theme Provider applying Light or Dark mode.
 */
@Composable
fun ReFocusBrandTheme(
    themeMode: com.reeltrak.app.domain.models.ThemeMode = com.reeltrak.app.domain.models.ThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val isDark = themeMode == com.reeltrak.app.domain.models.ThemeMode.DARK
    val palette = if (isDark) ReFocusDarkColors else ReFocusLightColors
    CompositionLocalProvider(
        LocalReFocusColors provides palette,
        content = content
    )
}

@Composable
fun ReFocusBrandTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) ReFocusDarkColors else ReFocusLightColors
    CompositionLocalProvider(
        LocalReFocusColors provides palette,
        content = content
    )
}
