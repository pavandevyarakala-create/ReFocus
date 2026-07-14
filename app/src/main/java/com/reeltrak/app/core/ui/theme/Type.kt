package com.reeltrak.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em

val GenesisTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for General Sans
        fontWeight = FontWeight.Bold,
        fontSize = 72.sp,
        letterSpacing = (-0.04).em,
        color = GenesisTextPrimary
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for General Sans
        fontWeight = FontWeight.Bold,
        fontSize = 60.sp,
        letterSpacing = (-0.03).em,
        color = GenesisTextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for General Sans
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.03).em,
        color = GenesisTextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for General Sans
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.03).em,
        color = GenesisTextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for DM Sans
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        color = GenesisTextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for DM Sans
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = GenesisTextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for DM Sans
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        color = GenesisTextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, // Fallback for DM Sans
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = GenesisTextSecondary
    ),
    bodySmall = TextStyle( // Used as Overline
        fontFamily = FontFamily.SansSerif, // Fallback for DM Sans
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = GenesisTextSecondary
    )
)
