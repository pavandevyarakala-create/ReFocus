package com.reeltrak.app.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Re:Focus Core Brand Color Primitives.
 *
 * Brand Philosophy: "Intentional Digital Discipline"
 * Primary Accent: Signature Muted Lime (#B8D94E)
 * Foundations: Warm Ivory Neutrals (Light) & Deep Charcoal / Near-Black (Dark)
 */

// --- Signature Brand Accent (Lime) ---
val ReFocusLimePrimary = Color(0xFFB8D94E)          // Signature Muted Lime
val ReFocusLimePressed = Color(0xFFA2C23B)          // Tactile Pressed Lime
val ReFocusLimeHover = Color(0xFF9FBD39)            // Darker Hover Lime
val ReFocusLimeSoftLight = Color(0xFFEEF3D8)        // Soft Lime Container (Light)
val ReFocusLimeSoftDark = Color(0xFF283116)         // Soft Lime Container (Dark)
val ReFocusLimeDark = Color(0xFF607615)             // Deep Lime for light-mode text & contrast
val ReFocusLimeHighlight = Color(0xFFC2E057)        // Luminous Lime for dark-mode text & edges
val ReFocusLimeGradientStart = Color(0xFFC2E057)    // Gradient top light
val ReFocusLimeGradientEnd = Color(0xFFB0D244)      // Gradient bottom shade

// --- Warm Light Neutral Foundations ---
val ReFocusLightBackground = Color(0xFFF7F6F0)      // Warm Ivory Background
val ReFocusLightSurface = Color(0xFFFFFFFF)         // Pure Surface
val ReFocusLightSurfaceElevated = Color(0xFFFCFBF7) // Elevated Surface
val ReFocusLightSurfaceFloating = Color(0xFFFFFFFF) // Floating Surface
val ReFocusLightSurfaceSoft = Color(0xFFEFEEE7)     // Soft Surface / Chip Background
val ReFocusLightSurfaceStrong = Color(0xFFE6E4DA)   // Strong Contrast Surface
val ReFocusLightSurfaceRecessed = Color(0xFFECEAE2) // Recessed Sunken Surface
val ReFocusLightBorder = Color(0xFFE1DFD6)          // Primary Border / Divider
val ReFocusLightBorderSubtle = Color(0xFFECEAE2)    // Subtle Hairline

val ReFocusLightTextPrimary = Color(0xFF151619)     // Rich Deep Charcoal
val ReFocusLightTextSecondary = Color(0xFF666A6A)   // Medium Slate Charcoal
val ReFocusLightTextMuted = Color(0xFF929590)       // Muted Caption Neutral
val ReFocusLightTextDisabled = Color(0xFFB5B7B2)    // Disabled Text

// --- Genuine Dark Material Hierarchy ---
val ReFocusDarkBackground = Color(0xFF090A0C)       // Deep Near-Black Background
val ReFocusDarkSurface = Color(0xFF111316)          // Level 1: Primary Surface
val ReFocusDarkSurfaceElevated = Color(0xFF181B1F)  // Level 2: Elevated Surface
val ReFocusDarkSurfaceFloating = Color(0xFF1F2328)  // Level 3: Floating Surface
val ReFocusDarkSurfaceStrong = Color(0xFF252A30)    // Level 4: Strong Surface
val ReFocusDarkSurfaceSoft = Color(0xFF15181D)      // Soft Neutral Surface
val ReFocusDarkSurfaceRecessed = Color(0xFF0D0F11)  // Recessed Sunken Surface
val ReFocusDarkBorder = Color(0xFF2B3037)           // Primary Border
val ReFocusDarkBorderSubtle = Color(0xFF1F2329)     // Subtle Hairline

val ReFocusDarkTextPrimary = Color(0xFFF5F4EE)      // Warm Ivory-White
val ReFocusDarkTextSecondary = Color(0xFFA8AAA9)    // Neutral Silver
val ReFocusDarkTextMuted = Color(0xFF73777A)        // Muted Slate Neutral
val ReFocusDarkTextDisabled = Color(0xFF4A4E53)     // Disabled Text

// --- Semantic Warning (Restrained Warm Ember / Terracotta) ---
// Communicates "attention / limit reached" — NOT an emergency error!
val ReFocusWarningLightAccent = Color(0xFFC86D3B)   // Muted terracotta/ember
val ReFocusWarningLightSoft = Color(0xFFFDF3EC)     // Warm ivory/peach-tinted container
val ReFocusWarningLightBorder = Color(0xFFF4D6C3)   // Soft terracotta border
val ReFocusWarningLightText = Color(0xFF78351B)     // Deep warm red-brown text

val ReFocusWarningDarkAccent = Color(0xFFDE8B6F)    // Restrained muted coral/terracotta
val ReFocusWarningDarkSoft = Color(0xFF261815)      // Dark warm ember-brown container
val ReFocusWarningDarkBorder = Color(0xFF452A23)    // Subtle ember border
val ReFocusWarningDarkText = Color(0xFFF5CEBF)      // Soft warm peach text

// --- Semantic Error / Destructive (Restrained Warm Rose-Crimson) ---
// Communicates true errors / destructive operations
val ReFocusErrorLightAccent = Color(0xFFC94D50)     // Restrained warm crimson rose
val ReFocusErrorLightSoft = Color(0xFFFDF0EE)       // Soft blush neutral container
val ReFocusErrorLightBorder = Color(0xFFF7D2CF)     // Soft crimson border
val ReFocusErrorLightText = Color(0xFF7A2426)       // Deep dark crimson text

val ReFocusErrorDarkAccent = Color(0xFFE06D70)      // Luminous restrained rose
val ReFocusErrorDarkSoft = Color(0xFF261416)        // Dark wine-tinted container
val ReFocusErrorDarkBorder = Color(0xFF481F24)      // Dark wine border
val ReFocusErrorDarkText = Color(0xFFF7CFD1)        // Soft pale rose text

// --- Semantic Success (Calm Sage / Forest Green) ---
val ReFocusSuccessLightAccent = Color(0xFF4E8C43)   // Calm forest green
val ReFocusSuccessLightSoft = Color(0xFFEBF4E8)     // Soft sage container
val ReFocusSuccessLightBorder = Color(0xFFD0E6CA)   // Soft sage border
val ReFocusSuccessLightText = Color(0xFF285721)     // Deep forest text

val ReFocusSuccessDarkAccent = Color(0xFF75C96B)    // Luminous balanced green
val ReFocusSuccessDarkSoft = Color(0xFF172616)      // Dark forest container
val ReFocusSuccessDarkBorder = Color(0xFF294427)    // Dark forest border
val ReFocusSuccessDarkText = Color(0xFFBDE8B6)      // Soft sage text

// --- Semantic Info (Restrained Slate Blue) ---
val ReFocusInfoLightAccent = Color(0xFF3D86A3)      // Restrained slate blue
val ReFocusInfoLightSoft = Color(0xFFEDF6FA)        // Soft slate container
val ReFocusInfoLightBorder = Color(0xFFCDE5F0)      // Soft slate border
val ReFocusInfoLightText = Color(0xFF1B4F63)        // Deep slate text

val ReFocusInfoDarkAccent = Color(0xFF65B8D8)       // Luminous slate blue
val ReFocusInfoDarkSoft = Color(0xFF14222B)         // Dark slate container
val ReFocusInfoDarkBorder = Color(0xFF213A4A)       // Dark slate border
val ReFocusInfoDarkText = Color(0xFFBFE0EE)         // Soft slate text

// --- Streaks / Gamification (Warm Flame) ---
val ReFocusFlameLight = Color(0xFFD96B27)           // Disciplined warm flame
val ReFocusFlameLightGlow = Color(0xFFFDF1E8)       // Soft amber-peach glow
val ReFocusFlameDark = Color(0xFFE0783E)            // Warm ember flame
val ReFocusFlameDarkGlow = Color(0xFF281A14)        // Dark ember glow

// --- Backward-Compatible Legacy Genesis Color Aliases ---
val GenesisPrimary = ReFocusLightTextPrimary
val GenesisPrimaryHover = Color(0xFF1F2328)
val GenesisSecondary = ReFocusLimePrimary
val GenesisNeutral = ReFocusLightTextMuted
val GenesisBackground = ReFocusLightBackground
val GenesisSurface = ReFocusLightSurface
val GenesisTextPrimary = ReFocusLightTextPrimary
val GenesisTextSecondary = ReFocusLightTextSecondary
val GenesisBorder = ReFocusLightBorder
val GenesisSuccess = ReFocusSuccessDarkAccent
val GenesisWarning = ReFocusWarningLightAccent
val GenesisError = ReFocusErrorLightAccent
