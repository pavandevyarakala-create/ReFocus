package com.reeltrak.app.features.tour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.tour.TooltipPosition
import com.reeltrak.app.core.tour.TourCoordinator
import com.reeltrak.app.core.ui.theme.ReFocusColors
import kotlin.math.max
import kotlin.math.min

/**
 * Welcome dialog shown to first-time users before the guided tour starts.
 */
@Composable
fun TourWelcomeDialog(
    onStartTour: () -> Unit,
    onSkipTour: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSkipTour,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WavingHand,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Welcome to Re:Focus 👋",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Text(
                text = "Let's take a quick interactive tour so you know how daily limits, strict mode, and analytics work.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onStartTour,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Start Tour", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(6.dp))
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkipTour,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip Tour", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/**
 * Full-screen interactive Spotlight overlay with spring animated cutout, connecting pointer, and elegant tooltips.
 */
@Composable
fun TourOverlay(
    coordinator: TourCoordinator,
    onFinishTour: () -> Unit
) {
    val isTourActive by coordinator.isTourActive.collectAsState()
    val currentStepIndex by coordinator.currentStepIndex.collectAsState()
    val boundsVersion by coordinator.boundsVersion.collectAsState()

    if (!isTourActive) return

    val currentStep = coordinator.steps.getOrNull(currentStepIndex) ?: return
    val targetBounds = coordinator.getTargetBounds(currentStep.targetId)

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // Intercept Android Back button
    BackHandler {
        if (currentStepIndex > 0) {
            coordinator.previousStep()
        } else {
            coordinator.endTour()
            onFinishTour()
        }
    }

    // Smooth Spring Animation for Target Bounds Transitions
    val animatedLeft by animateFloatAsState(
        targetValue = targetBounds?.left ?: (screenWidthPx / 2),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "target_left"
    )
    val animatedTop by animateFloatAsState(
        targetValue = targetBounds?.top ?: (screenHeightPx / 2),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "target_top"
    )
    val animatedRight by animateFloatAsState(
        targetValue = targetBounds?.right ?: (screenWidthPx / 2),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "target_right"
    )
    val animatedBottom by animateFloatAsState(
        targetValue = targetBounds?.bottom ?: (screenHeightPx / 2),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "target_bottom"
    )

    // Subtle breathing stroke highlight animation
    val infiniteTransition = rememberInfiniteTransition(label = "highlight_breathe")
    val highlightAlpha by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "highlight_alpha"
    )

    val themeSurfaceColor = MaterialTheme.colorScheme.surface
    val themePrimaryColor = MaterialTheme.colorScheme.primary
    val tourScrimColor = ReFocusColors.scrim
    val tourSpecularColor = ReFocusColors.specularHighlight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Consume taps to prevent background touch leakage */ }
    ) {
        var tooltipHeightPx by remember { mutableIntStateOf(0) }

        val isBelow = when (currentStep.preferredPosition) {
            TooltipPosition.ABOVE -> false
            TooltipPosition.BELOW -> true
            TooltipPosition.AUTO -> {
                if (targetBounds != null) {
                    val targetCenterY = (targetBounds.top + targetBounds.bottom) / 2
                    targetCenterY < screenHeightPx / 2
                } else true
            }
        }

        val tooltipYOffset = if (targetBounds != null) {
            if (isBelow) {
                val calculatedTop = animatedBottom + with(density) { 24.dp.toPx() }
                min(calculatedTop, screenHeightPx - tooltipHeightPx - with(density) { 32.dp.toPx() })
            } else {
                val calculatedBottom = animatedTop - with(density) { 24.dp.toPx() } - tooltipHeightPx
                max(calculatedBottom, with(density) { 48.dp.toPx() })
            }
        } else {
            screenHeightPx / 3
        }

        // Spotlight & Elegant Connecting Arrow Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (targetBounds != null) {
                val padding = 10.dp.toPx()
                val expandedRect = Rect(
                    left = max(0f, animatedLeft - padding),
                    top = max(0f, animatedTop - padding),
                    right = min(size.width, animatedRight + padding),
                    bottom = min(size.height, animatedBottom + padding)
                )

                val spotlightPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = expandedRect,
                            cornerRadius = CornerRadius(16.dp.toPx())
                        )
                    )
                }

                // Semi-transparent scrim cutout
                clipPath(spotlightPath, clipOp = ClipOp.Difference) {
                    drawRect(tourScrimColor.copy(alpha = 0.70f))
                }

                // Glowing highlight border around active target
                drawRoundRect(
                    color = tourSpecularColor.copy(alpha = highlightAlpha),
                    topLeft = Offset(expandedRect.left, expandedRect.top),
                    size = androidx.compose.ui.geometry.Size(expandedRect.width, expandedRect.height),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )

                // Elegant pointer callout connecting tooltip with target
                val targetCenterX = (expandedRect.left + expandedRect.right) / 2
                val pointerPath = Path()
                if (isBelow) {
                    val pointerTipY = expandedRect.bottom + 2.dp.toPx()
                    val cardTopY = tooltipYOffset
                    if (cardTopY > pointerTipY) {
                        pointerPath.moveTo(targetCenterX, pointerTipY)
                        pointerPath.lineTo(targetCenterX - 8.dp.toPx(), pointerTipY + 12.dp.toPx())
                        pointerPath.lineTo(targetCenterX + 8.dp.toPx(), pointerTipY + 12.dp.toPx())
                        pointerPath.close()
                        drawPath(pointerPath, color = themeSurfaceColor)
                    }
                } else {
                    val pointerTipY = expandedRect.top - 2.dp.toPx()
                    val cardBottomY = tooltipYOffset + tooltipHeightPx
                    if (cardBottomY < pointerTipY) {
                        pointerPath.moveTo(targetCenterX, pointerTipY)
                        pointerPath.lineTo(targetCenterX - 8.dp.toPx(), pointerTipY - 12.dp.toPx())
                        pointerPath.lineTo(targetCenterX + 8.dp.toPx(), pointerTipY - 12.dp.toPx())
                        pointerPath.close()
                        drawPath(pointerPath, color = themeSurfaceColor)
                    }
                }
            } else {
                drawRect(tourScrimColor.copy(alpha = 0.70f))
            }
        }

        // Floating Tooltip Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, tooltipYOffset.toInt()) }
                .padding(horizontal = 20.dp)
                .onGloballyPositioned { tooltipHeightPx = it.size.height }
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Header: Progress Dots & Skip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Dot Progress Indicator
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            coordinator.steps.forEachIndexed { index, _ ->
                                val isActive = index == currentStepIndex
                                Box(
                                    modifier = Modifier
                                        .size(if (isActive) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isActive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                        )
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                coordinator.endTour()
                                onFinishTour()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Skip Tour",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Animated Step Content
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                    fadeOut(animationSpec = tween(180))
                        },
                        label = "tooltip_text_transition"
                    ) { step ->
                        Column {
                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentStepIndex > 0) {
                            OutlinedButton(
                                onClick = { coordinator.previousStep() },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Back", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    coordinator.endTour()
                                    onFinishTour()
                                }
                            ) {
                                Text("Skip", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        val isLastStep = currentStepIndex == coordinator.steps.size - 1
                        Button(
                            onClick = {
                                if (isLastStep) {
                                    coordinator.endTour()
                                    onFinishTour()
                                } else {
                                    coordinator.nextStep()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Text(if (isLastStep) "Done ✓" else "Next", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            if (!isLastStep) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
