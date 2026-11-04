package com.reeltrak.app.core.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tactile Spring Scale Press Modifier.
 *
 * Produces an authentic, tactile physical reduction in scale (default: 0.97f)
 * when pressed, accompanied by a subtle reduction in alpha (0.92f).
 */
@Composable
fun Modifier.tactilePress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 0.92f,
    enabled: Boolean = true
): Modifier {
    if (!enabled) return this

    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "tactilePressScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) pressedAlpha else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "tactilePressAlpha"
    )

    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/**
 * Premium Floating Pill CTA:
 *
 * The marquee action surface for Re:Focus (used as the primary Subscription CTA):
 * - Substantial height (56dp)
 * - True pill geometry (RoundedCornerShape(999.dp))
 * - Deep translucent material with subtle glass appearance
 * - Soft outer shadow simulating physical suspension
 * - Specular top highlight bevel
 * - Tactile spring scale on press (0.97f)
 * - Polished state transitions: DEFAULT, PRESSED, LOADING, DISABLED
 */
@Composable
fun ReFocusFloatingPillCta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    priceTag: String? = null,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    loadingText: String = "PROCESSING...",
    enabled: Boolean = true,
    height: Dp = 56.dp
) {
    val colors = ReFocusTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pillShape = RoundedCornerShape(999.dp)

    // Elevation dynamically compresses on press
    val elevationDp by animateFloatAsState(
        targetValue = if (!enabled) 0f else if (isPressed) 3f else if (colors.isDark) 12f else 8f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ctaElevation"
    )

    // Signature Lime background with restrained depth
    val backgroundBrush = when {
        !enabled -> Brush.verticalGradient(
            listOf(colors.lineStrong.copy(alpha = 0.5f), colors.lineStrong.copy(alpha = 0.3f))
        )
        isPressed -> Brush.verticalGradient(
            listOf(colors.accentLimeDark, colors.accentLimeDark) // Pressed darker lime #9FBE38
        )
        else -> Brush.verticalGradient(
            listOf(colors.accentLime, colors.accentLime) // Signature Lime #B8D94E
        )
    }

    val contentColor = when {
        !enabled -> colors.textMuted
        else -> if (colors.isDark) colors.textInverse else colors.textPrimary // Crisp dark text (#151619) on signature lime in both modes
    }

    // Restrained shadow - subtle alpha-based treatment only
    val shadowColor = if (enabled) {
        if (colors.isDark) colors.shadowAccent else colors.shadowAmbient
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .shadow(
                elevation = elevationDp.dp,
                shape = pillShape,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
            .tactilePress(interactionSource = interactionSource, enabled = enabled && !isLoading)
            .clip(pillShape)
            .background(backgroundBrush)
            .border(
                BorderStroke(
                    1.dp,
                    if (colors.isDark) colors.specularHighlight else colors.borderSubtle
                ),
                pillShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isLoading,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isLoading,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label = "FloatingCtaContent"
        ) { loading ->
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = contentColor
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = loadingText,
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            color = contentColor,
                            fontSize = 12.sp,
                            letterSpacing = 1.4.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        } else {
                            // Signature glowing pip indicator
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(contentColor)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Text(
                            text = text,
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = contentColor,
                                fontSize = 12.sp,
                                letterSpacing = 1.3.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    if (priceTag != null) {
                        Text(
                            text = priceTag,
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = contentColor,
                                fontSize = 11.sp,
                                letterSpacing = 0.8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    } else {
                        Text(
                            text = "›",
                            style = ReFocusTheme.Typography.Headline.copy(
                                color = contentColor,
                                fontSize = 18.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Premium Secondary Floating Pill Control:
 *
 * For contextual actions (e.g. Sync Status, Cancel Autopay, Renew Plan, Done):
 * - Pill geometry with soft outer shadow
 * - Deep translucent graphite surface in Dark mode, warm pearl in Light mode
 * - Specular top highlight bevel
 * - Spring scale reduction on press (0.97f)
 */
@Composable
fun ReFocusFloatingPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    isSelected: Boolean = false,
    isDestructive: Boolean = false,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 46.dp
) {
    val colors = ReFocusTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pillShape = RoundedCornerShape(999.dp)

    val elevationDp by animateFloatAsState(
        targetValue = if (!enabled) 0f else if (isPressed) 1f else if (isPrimary) 6f else 4f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillElevation"
    )

    val surfaceColor = when {
        !enabled -> colors.surfaceSubtle.copy(alpha = 0.5f)
        isPrimary -> if (colors.isDark) colors.accentLimeDark else colors.inkPrimary
        isSelected -> if (colors.isDark) colors.surfaceElevated else colors.accentLimeSubtle
        isDestructive -> colors.destructiveSubtle
        else -> colors.surfaceContainer
    }

    val contentColor = when {
        !enabled -> colors.inkMuted
        isPrimary -> colors.textInverse
        isSelected -> if (colors.isDark) colors.accentLimeDark else colors.inkPrimary
        isDestructive -> colors.destructive
        else -> colors.inkPrimary
    }

    val borderColor = when {
        !enabled -> colors.lineHairline.copy(alpha = 0.5f)
        isPrimary -> if (colors.isDark) colors.accentLime else colors.inkPrimary
        isSelected -> colors.accentLime.copy(alpha = 0.8f)
        isDestructive -> colors.destructive.copy(alpha = 0.4f)
        else -> colors.lineHairline
    }

    Box(
        modifier = modifier
            .height(height)
            .shadow(
                elevation = elevationDp.dp,
                shape = pillShape,
                ambientColor = colors.shadowAmbient,
                spotColor = colors.shadowSpot
            )
            .tactilePress(interactionSource = interactionSource, enabled = enabled && !isLoading)
            .clip(pillShape)
            .background(surfaceColor)
            .border(
                if (isPrimary || isSelected) BorderStroke(1.2.dp, borderColor) else specularBorder(1.dp, pillShape),
                pillShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isLoading,
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = text,
                    style = ReFocusTheme.Typography.SectionTitle.copy(
                        color = contentColor,
                        fontSize = 11.sp,
                        letterSpacing = 1.1.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Premium Floating Segmented Pill Bar:
 *
 * Floating dock containing segmented options (e.g. System | Light | Dark):
 * - Continuous floating pill container with deep translucent surface and soft outer shadow
 * - Elevated active pill segment with luminous lime accent and tactile pop
 */
@Composable
fun <T> ReFocusFloatingSegmentedBar(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    modifier: Modifier = Modifier,
    iconProvider: ((T) -> ImageVector)? = null,
    height: Dp = 52.dp
) {
    val colors = ReFocusTheme.colors
    val containerShape = RoundedCornerShape(999.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .shadow(
                elevation = 8.dp,
                shape = containerShape,
                ambientColor = colors.shadowAmbient,
                spotColor = colors.shadowSpot
            )
            .clip(containerShape)
            .background(colors.surfaceContainer)
            .border(specularBorder(strokeWidth = 1.dp, shape = containerShape))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val segmentInteraction = remember { MutableInteractionSource() }
                val segmentShape = RoundedCornerShape(999.dp)

                val segmentBg = when {
                    isSelected -> if (colors.isDark) colors.surfaceElevated else colors.surface
                    else -> Color.Transparent
                }

                val segmentBorder = when {
                    isSelected -> BorderStroke(1.dp, colors.accentLime.copy(alpha = 0.6f))
                    else -> BorderStroke(0.dp, Color.Transparent)
                }

                val segmentContentColor = when {
                    isSelected -> if (colors.isDark) colors.accentLimeDark else colors.inkPrimary
                    else -> colors.inkSecondary
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .tactilePress(interactionSource = segmentInteraction)
                        .clip(segmentShape)
                        .background(segmentBg)
                        .border(segmentBorder, segmentShape)
                        .clickable(
                            interactionSource = segmentInteraction,
                            indication = null,
                            onClick = { onItemSelected(item) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (iconProvider != null) {
                            Icon(
                                imageVector = iconProvider(item),
                                contentDescription = null,
                                tint = segmentContentColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Text(
                            text = labelProvider(item),
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = segmentContentColor,
                                fontSize = 11.sp,
                                letterSpacing = 0.8.sp,
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
