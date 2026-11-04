package com.reeltrak.app.core.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Navigation destination specification for the floating dock.
 */
data class NavDockItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val modifier: Modifier = Modifier,
    val isLocked: Boolean = false
)

/**
 * Re:Focus Premium Floating Control Dock.
 *
 * Implements a layered, physically suspended navigation capsule inspired by
 * precision hardware and editorial product design:
 *
 * DEPTH STACK:
 * - Layer 1: Ambient diffuse drop shadow (16dp blur)
 * - Layer 2: Tight contact shadow (4dp elevation)
 * - Layer 3: Main dock material gradient (#1A1D22 -> #131519 in Dark, pearl in Light)
 * - Layer 4: Specular top highlight bevel
 * - Layer 5: Hairline edge border
 *
 * SELECTED ITEM:
 * - Elevated capsule sitting physically inside the dock
 * - Fluid gliding animation between tab positions (zero bounce)
 * - Restrained signature lime accent (#B8D94E)
 */
@Composable
fun ReFocusFloatingNavDock(
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    items: List<NavDockItem>,
    modifier: Modifier = Modifier
) {
    val colors = ReFocusTheme.colors
    val dockShape = RoundedCornerShape(999.dp)

    // Layer 1 & 2: Ambient and Contact Shadows
    val ambientShadowColor = colors.shadowAmbient
    val contactShadowColor = colors.shadowSpot

    // Layer 3: Main Dock Material Gradient
    val dockBackgroundBrush = when {
        colors.isDark -> Brush.verticalGradient(
            listOf(colors.surfaceFloating, colors.surface)
        )
        else -> Brush.verticalGradient(
            listOf(colors.surface, colors.background)
        )
    }

    Box(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .height(66.dp)
            // Layer 1: Ambient diffuse drop shadow
            .shadow(
                elevation = 16.dp,
                shape = dockShape,
                ambientColor = ambientShadowColor,
                spotColor = ambientShadowColor
            )
            // Layer 2: Contact drop shadow
            .shadow(
                elevation = 4.dp,
                shape = dockShape,
                ambientColor = contactShadowColor,
                spotColor = contactShadowColor
            )
            .clip(dockShape)
            // Layer 3: Material
            .background(dockBackgroundBrush)
            // Layer 4 & 5: Specular top highlight & hairline edge border
            .border(specularBorder(strokeWidth = 1.dp, shape = dockShape), dockShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val itemCount = items.size.coerceAtLeast(1)
            val tabWidth = maxWidth / itemCount
            val clampedIndex = selectedIndex.coerceIn(0, itemCount - 1)

            // Smooth physical gliding offset for the elevated selected capsule
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * clampedIndex,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "dockIndicatorOffset"
            )

            val capsuleShape = RoundedCornerShape(999.dp)

            // Elevated active capsule: Luminous lime physical control rising from the dock
            val capsuleBgBrush = Brush.verticalGradient(
                listOf(colors.accentHighlight, colors.accentPressed)
            )

            val capsuleBorder = BorderStroke(
                1.dp,
                Brush.verticalGradient(
                    listOf(colors.specularHighlight, colors.borderSubtle)
                )
            )

            val capsuleShadowColor = if (colors.isDark) colors.shadowSpot else colors.shadowAccent

            // The elevated sliding capsule with physical rise
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 3.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = capsuleShape,
                        ambientColor = capsuleShadowColor,
                        spotColor = capsuleShadowColor
                    )
                    .clip(capsuleShape)
                    .background(capsuleBgBrush)
                    .border(capsuleBorder, capsuleShape)
            )

            // Row of interactive tab items
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == clampedIndex
                    val interactionSource = remember { MutableInteractionSource() }

                    val animatedIconColor by animateColorAsState(
                        targetValue = when {
                            isSelected -> colors.textInverse // High-contrast charcoal on lime capsule
                            colors.isDark -> colors.textPrimary // Warm-white icons in dark mode dock
                            else -> colors.iconSecondary // Charcoal icons in light mode dock
                        },
                        animationSpec = tween(180),
                        label = "dockIconColor"
                    )

                    val animatedTextColor by animateColorAsState(
                        targetValue = when {
                            isSelected -> colors.textInverse // High-contrast charcoal label on lime
                            colors.isDark -> colors.textSecondary // Muted warm-white label in dark mode
                            else -> colors.textMuted // Muted label in light mode
                        },
                        animationSpec = tween(180),
                        label = "dockTextColor"
                    )

                    // Tasteful separator before tab (if neither this nor previous is selected)
                    if (index > 0 && index != clampedIndex && (index - 1) != clampedIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(14.dp)
                                .background(colors.border.copy(alpha = 0.6f))
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(item.modifier)
                            .tactilePress(interactionSource = interactionSource)
                            .clip(capsuleShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelectTab(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.title,
                                    tint = animatedIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                if (item.isLocked) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 6.dp, y = (-3).dp)
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) colors.textInverse else colors.accent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Locked",
                                            tint = if (isSelected) colors.accent else colors.textInverse,
                                            modifier = Modifier.size(6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(3.dp))

                            Text(
                                text = item.title,
                                style = ReFocusTheme.Typography.SectionTitle.copy(
                                    color = animatedTextColor,
                                    fontSize = 10.5.sp,
                                    letterSpacing = 0.3.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
