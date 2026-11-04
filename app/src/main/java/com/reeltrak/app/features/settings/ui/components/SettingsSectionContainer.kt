package com.reeltrak.app.features.settings.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.ReFocusTheme

/**
 * Architectural Section Label:
 * Quiet, restrained monospace label establishing intentional hierarchy.
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    index: String? = null
) {
    val colors = ReFocusTheme.colors

    Text(
        text = if (index != null) "$index // $title" else title,
        style = ReFocusTheme.Typography.SectionTitle.copy(
            color = colors.inkMuted,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
    )
}

/**
 * Unified Grouped Settings Container:
 * Groups multiple settings items inside a single continuous squircle surface
 * with a 3D specular highlight top border, eliminating visually cluttered standalone cards.
 */
@Composable
fun SettingsGroupContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = ReFocusTheme.colors
    val containerShape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .shadow(
                elevation = 2.dp,
                shape = containerShape,
                ambientColor = colors.shadowAmbient,
                spotColor = colors.shadowSpot
            )
            .clip(containerShape)
            .background(if (colors.isDark) colors.surfaceElevated else colors.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * Editorial Settings Item Row:
 * Features squircle icon frames, clean typography hierarchy, right-pointing chevrons,
 * tactile spring pressed feedback, and indented hairlines that align with the content margin.
 */
@Composable
fun SettingsItemRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    isDestructive: Boolean = false,
    showDivider: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = ReFocusTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val rowBackground by animateColorAsState(
        targetValue = if (isPressed && onClick != null) {
            if (colors.isDark) colors.surfaceFloating else colors.surfaceSoft
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 100),
        label = "rowBackground"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.94f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconScale"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = onClick != null
                ) { onClick?.invoke() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Squircle Icon Tile Frame (Recessed socket depth, borderless)
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isDestructive) colors.destructiveSubtle
                            else colors.surfaceRecessed
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isDestructive) colors.destructive else colors.inkPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))
            }

            // Title & Subtitle Hierarchy
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = if (isDestructive) colors.destructive else colors.inkPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = if (isDestructive) colors.destructive.copy(alpha = 0.8f) else colors.inkSecondary
                        )
                    )
                }
            }

            // Trailing Affordance (Switch, Action, or Subtle Chevron)
            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailing()
            } else if (onClick != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = if (isDestructive) colors.destructive else if (isPressed) colors.inkPrimary else colors.inkMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Indented Hairline Divider (starts flush with text margin)
        if (showDivider) {
            HorizontalDivider(
                thickness = ReFocusTheme.Dimens.HairlineWidth,
                color = colors.lineHairline,
                modifier = Modifier.padding(start = if (icon != null) 66.dp else 16.dp, end = 16.dp)
            )
        }
    }
}

/**
 * Backward-compatible Open Row wrapper for existing callers.
 */
@Composable
fun SettingsOpenRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    isDestructive: Boolean = false,
    modifier: Modifier = Modifier
) {
    SettingsItemRow(
        title = title,
        subtitle = subtitle,
        icon = null,
        onClick = onClick,
        trailing = trailing,
        isDestructive = isDestructive,
        showDivider = true,
        modifier = modifier
    )
}
