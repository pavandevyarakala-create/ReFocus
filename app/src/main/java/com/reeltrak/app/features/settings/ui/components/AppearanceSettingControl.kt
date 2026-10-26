package com.reeltrak.app.features.settings.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.core.ui.theme.specularBorder
import com.reeltrak.app.core.ui.theme.tactilePress
import com.reeltrak.app.domain.models.ThemeMode

/**
 * Dedicated In-Line Appearance Control for Settings.
 *
 * Implements the preferred Re:Focus visual direction:
 * PREFERENCES
 * Appearance
 * Current Mode Description
 * [ System ] [ Light ] [ Dark ]
 *
 * Characteristics:
 * - Smooth selected-state animation
 * - Subtle depth (track sunken in canvas, elevated active pill with specular highlight)
 * - Clear active state with signature Re:Focus lime accent
 * - Generous touch targets (>44dp)
 * - Immediate state propagation and DataStore persistence
 */
@Composable
fun AppearanceSettingControl(
    currentMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Appearance",
    subtitle: String? = null,
    icon: ImageVector = Icons.Outlined.Palette,
    showDivider: Boolean = true
) {
    val colors = ReFocusTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Row Header (Icon + Title + Subtitle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Squircle Icon Container (Recessed socket depth)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceRecessed),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        color = colors.inkPrimary
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = subtitle ?: when (currentMode) {
                            ThemeMode.LIGHT -> "Light • Warm ivory foundation"
                            ThemeMode.DARK -> "Dark • Deep charcoal foundation"
                        },
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = colors.inkSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dedicated Segmented Pill Selector: [ Light ] [ Dark ]
            val trackShape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(trackShape)
                    .background(colors.surfaceRecessed)
                    .padding(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppearanceSegmentPill(
                        mode = ThemeMode.LIGHT,
                        label = "Light",
                        icon = Icons.Outlined.LightMode,
                        isSelected = currentMode == ThemeMode.LIGHT,
                        onClick = { onSelectMode(ThemeMode.LIGHT) },
                        modifier = Modifier.weight(1f)
                    )

                    AppearanceSegmentPill(
                        mode = ThemeMode.DARK,
                        label = "Dark",
                        icon = Icons.Outlined.DarkMode,
                        isSelected = currentMode == ThemeMode.DARK,
                        onClick = { onSelectMode(ThemeMode.DARK) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = colors.lineHairline.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 66.dp, end = 16.dp)
            )
        }
    }
}

@Composable
private fun AppearanceSegmentPill(
    mode: ThemeMode,
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ReFocusTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pillShape = RoundedCornerShape(999.dp)

    // Animated colors for silky transitions
    val animatedBackground by animateColorAsState(
        targetValue = when {
            isSelected && colors.isDark -> colors.surfaceFloating
            isSelected && !colors.isDark -> colors.surface
            else -> Color.Transparent
        },
        animationSpec = tween(180),
        label = "pillBg"
    )

    val animatedContentColor by animateColorAsState(
        targetValue = when {
            isSelected && colors.isDark -> colors.textPrimary
            isSelected && !colors.isDark -> colors.inkPrimary
            else -> colors.inkMuted
        },
        animationSpec = tween(180),
        label = "pillContent"
    )

    val animatedIconColor by animateColorAsState(
        targetValue = when {
            isSelected && colors.isDark -> colors.accentLime
            isSelected && !colors.isDark -> colors.accentDark
            else -> colors.inkMuted
        },
        animationSpec = tween(180),
        label = "pillIcon"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (isSelected) {
                    Modifier.shadow(
                        elevation = 3.dp,
                        shape = pillShape,
                        ambientColor = colors.shadowAmbient,
                        spotColor = colors.shadowSpot
                    )
                } else Modifier
            )
            .tactilePress(interactionSource = interactionSource)
            .clip(pillShape)
            .background(animatedBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedIconColor,
                modifier = Modifier.size(15.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = label,
                style = ReFocusTheme.Typography.SectionTitle.copy(
                    color = animatedContentColor,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                ),
                textAlign = TextAlign.Center
            )

            if (isSelected) {
                Spacer(modifier = Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(colors.accentLime)
                )
            }
        }
    }
}
