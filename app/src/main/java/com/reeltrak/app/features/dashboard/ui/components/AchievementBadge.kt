package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AchievementBadge(
    milestone: Int,
    isUnlocked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "badge_animation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val scaleFactor by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val appliedModifier = if (isUnlocked) {
        modifier.size(size).graphicsLayer(scaleX = scaleFactor, scaleY = scaleFactor)
    } else {
        modifier.size(size).blur(2.dp).graphicsLayer(alpha = 0.4f)
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        Box(modifier = appliedModifier) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val badgeRadius = size.toPx() / 2f
                val center = Offset(badgeRadius, badgeRadius)

                when (milestone) {
                    7 -> drawSeedlingBadge(center, badgeRadius)
                    30 -> drawFocusedBadge(center, badgeRadius, rotation)
                    50 -> drawDisciplinedBadge(center, badgeRadius)
                    100 -> drawMasterBadge(center, badgeRadius, rotation)
                    365 -> drawLegendBadge(center, badgeRadius, rotation)
                }
            }
        }
        if (!isUnlocked) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.size(size * 0.45f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked Milestone",
                        tint = Color.White,
                        modifier = Modifier.size(size * 0.22f)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawSeedlingBadge(center: Offset, radius: Float) {
    // Gold Metallic Ring
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500), Color(0xFFFFD700)),
            center = center
        ),
        radius = radius,
        center = center
    )
    // Dark Green Inner Fill
    drawCircle(
        color = Color(0xFF1B5E20),
        radius = radius * 0.85f,
        center = center
    )
    // Vibrant Leaf (Fresh Green)
    val leafPath = Path().apply {
        moveTo(center.x, center.y + radius * 0.4f)
        cubicTo(
            center.x - radius * 0.5f, center.y,
            center.x - radius * 0.3f, center.y - radius * 0.5f,
            center.x, center.y - radius * 0.5f
        )
        cubicTo(
            center.x + radius * 0.3f, center.y - radius * 0.5f,
            center.x + radius * 0.5f, center.y,
            center.x, center.y + radius * 0.4f
        )
        close()
    }
    drawPath(
        path = leafPath,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF4CAF50), Color(0xFF81C784)),
            start = Offset(center.x, center.y + radius * 0.4f),
            end = Offset(center.x, center.y - radius * 0.5f)
        )
    )
    // Gold Leaf Border & Rib
    drawPath(
        path = leafPath,
        color = Color(0xFFFFD700),
        style = Stroke(width = radius * 0.05f)
    )
    drawLine(
        color = Color(0xFFFFD700),
        start = Offset(center.x, center.y + radius * 0.4f),
        end = Offset(center.x, center.y - radius * 0.3f),
        strokeWidth = radius * 0.04f
    )
}

private fun DrawScope.drawFocusedBadge(center: Offset, radius: Float, rotation: Float) {
    // Silver Outer Border
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFB0BEC5), Color(0xFFECEFF1), Color(0xFFB0BEC5)),
            center = center
        ),
        radius = radius,
        center = center
    )
    // Deep Metallic Blue Inner
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF1565C0), Color(0xFF0D47A1)),
            center = center,
            radius = radius * 0.85f
        ),
        radius = radius * 0.85f,
        center = center
    )
    // Rotating Target lines
    rotate(rotation, pivot = center) {
        // Crosshair ticks
        val tickLength = radius * 0.2f
        val innerMargin = radius * 0.35f
        drawLine(
            color = Color(0xFF80D8FF),
            start = Offset(center.x, center.y - innerMargin),
            end = Offset(center.x, center.y - innerMargin - tickLength),
            strokeWidth = radius * 0.06f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF80D8FF),
            start = Offset(center.x, center.y + innerMargin),
            end = Offset(center.x, center.y + innerMargin + tickLength),
            strokeWidth = radius * 0.06f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF80D8FF),
            start = Offset(center.x - innerMargin, center.y),
            end = Offset(center.x - innerMargin - tickLength, center.y),
            strokeWidth = radius * 0.06f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF80D8FF),
            start = Offset(center.x + innerMargin, center.y),
            end = Offset(center.x + innerMargin + tickLength, center.y),
            strokeWidth = radius * 0.06f,
            cap = StrokeCap.Round
        )
    }
    // Blue Crystal Core
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF00E5FF), Color(0xFF00B0FF)),
            start = Offset(center.x - radius * 0.2f, center.y - radius * 0.2f),
            end = Offset(center.x + radius * 0.2f, center.y + radius * 0.2f)
        ),
        radius = radius * 0.3f,
        center = center
    )
}

private fun DrawScope.drawDisciplinedBadge(center: Offset, radius: Float) {
    // Dark Royal Purple Outer
    drawCircle(
        color = Color(0xFF311B92),
        radius = radius,
        center = center
    )
    // Diamond Shield path
    val shieldPath = Path().apply {
        moveTo(center.x, center.y - radius * 0.75f)
        lineTo(center.x + radius * 0.65f, center.y - radius * 0.35f)
        lineTo(center.x + radius * 0.55f, center.y + radius * 0.35f)
        lineTo(center.x, center.y + radius * 0.8f)
        lineTo(center.x - radius * 0.55f, center.y + radius * 0.35f)
        lineTo(center.x - radius * 0.65f, center.y - radius * 0.35f)
        close()
    }
    // Draw Gem Shield
    drawPath(
        path = shieldPath,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFE040FB), Color(0xFF7B1FA2)),
            start = Offset(center.x, center.y - radius * 0.75f),
            end = Offset(center.x, center.y + radius * 0.8f)
        )
    )
    // Gold Inner Diamond core
    val corePath = Path().apply {
        moveTo(center.x, center.y - radius * 0.4f)
        lineTo(center.x + radius * 0.35f, center.y)
        lineTo(center.x, center.y + radius * 0.4f)
        lineTo(center.x - radius * 0.35f, center.y)
        close()
    }
    drawPath(
        path = corePath,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFD700), Color(0xFFFF9100)),
            start = Offset(center.x, center.y - radius * 0.4f),
            end = Offset(center.x, center.y + radius * 0.4f)
        )
    )
}

private fun DrawScope.drawMasterBadge(center: Offset, radius: Float, rotation: Float) {
    // Rich Gold Ring with sweeping gradient
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500), Color(0xFFFFD700)),
            center = center
        ),
        radius = radius,
        center = center
    )
    // Deep Crimson Inner Center
    drawCircle(
        color = Color(0xFF880E4F),
        radius = radius * 0.82f,
        center = center
    )
    // Laurel Wreath branches (using stars as segments)
    rotate(rotation * 0.5f, pivot = center) {
        val leavesCount = 8
        for (i in 0 until leavesCount) {
            val angle = (i * 360f / leavesCount) * (Math.PI / 180).toFloat()
            val leafCenter = Offset(
                x = center.x + radius * 0.55f * cos(angle),
                y = center.y + radius * 0.55f * sin(angle)
            )
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFFFEA00), Color(0xFFFFD700)),
                    start = Offset(leafCenter.x - radius * 0.08f, leafCenter.y - radius * 0.08f),
                    end = Offset(leafCenter.x + radius * 0.08f, leafCenter.y + radius * 0.08f)
                ),
                radius = radius * 0.12f,
                center = leafCenter
            )
        }
    }
    // Crown in the center
    val crownPath = Path().apply {
        moveTo(center.x - radius * 0.4f, center.y + radius * 0.25f)
        lineTo(center.x - radius * 0.45f, center.y - radius * 0.1f)
        lineTo(center.x - radius * 0.2f, center.y + radius * 0.05f)
        lineTo(center.x, center.y - radius * 0.3f)
        lineTo(center.x + radius * 0.2f, center.y + radius * 0.05f)
        lineTo(center.x + radius * 0.45f, center.y - radius * 0.1f)
        lineTo(center.x + radius * 0.4f, center.y + radius * 0.25f)
        close()
    }
    drawPath(
        path = crownPath,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFEA00), Color(0xFFFF9100)),
            start = Offset(center.x, center.y - radius * 0.3f),
            end = Offset(center.x, center.y + radius * 0.25f)
        )
    )
}

private fun DrawScope.drawLegendBadge(center: Offset, radius: Float, rotation: Float) {
    // Cosmic Nebula outer ring
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFF00E5FF), Color(0xFFD500F9), Color(0xFF00E5FF)),
            center = center
        ),
        radius = radius,
        center = center
    )
    // Dark Deep Space Core
    drawCircle(
        color = Color(0xFF0A0E29),
        radius = radius * 0.86f,
        center = center
    )
    // Constellation lines & stars
    rotate(rotation, pivot = center) {
        val constellationPath = Path().apply {
            moveTo(center.x - radius * 0.4f, center.y - radius * 0.2f)
            lineTo(center.x, center.y - radius * 0.5f)
            lineTo(center.x + radius * 0.4f, center.y - radius * 0.2f)
            lineTo(center.x + radius * 0.2f, center.y + radius * 0.4f)
            lineTo(center.x - radius * 0.2f, center.y + radius * 0.4f)
            close()
        }
        drawPath(
            path = constellationPath,
            color = Color(0x6600E5FF),
            style = Stroke(width = radius * 0.03f)
        )
        // Stars on vertices
        val stars = listOf(
            Offset(center.x - radius * 0.4f, center.y - radius * 0.2f),
            Offset(center.x, center.y - radius * 0.5f),
            Offset(center.x + radius * 0.4f, center.y - radius * 0.2f),
            Offset(center.x + radius * 0.2f, center.y + radius * 0.4f),
            Offset(center.x - radius * 0.2f, center.y + radius * 0.4f)
        )
        stars.forEach { starCenter ->
            drawCircle(
                color = Color.White,
                radius = radius * 0.06f,
                center = starCenter
            )
        }
    }
    // Galaxy core glowing spiral
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFF4081), Color(0x00FF4081)),
            center = center,
            radius = radius * 0.5f
        ),
        radius = radius * 0.5f,
        center = center
    )
    drawCircle(
        color = Color.White,
        radius = radius * 0.15f,
        center = center
    )
}

@Composable
fun CelebrationDialog(
    milestone: Int,
    onDismiss: () -> Unit,
    onViewCollection: () -> Unit
) {
    val badgeName = when (milestone) {
        7 -> "Focused"
        30 -> "Consistent"
        50 -> "Disciplined"
        100 -> "Master"
        365 -> "Legend"
        else -> ""
    }

    val motivationMsg = when (milestone) {
        7 -> "The journey has begun. You've taken the hardest first step and unlocked Focused!"
        30 -> "30 days of consistency. Focus has officially become your Consistent habit."
        50 -> "50 days reached! Your self-control and willpower are diamond-strong and Disciplined."
        100 -> "100 days of absolute masterclass discipline. Truly phenomenal Master level!"
        365 -> "You've achieved what very few people ever do. You are a Legend!"
        else -> ""
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight()
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Confetti/Particle layer in background
                ConfettiAnimation(modifier = Modifier.matchParentSize())

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ACHIEVEMENT UNLOCKED",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    AchievementBadge(
                        milestone = milestone,
                        isUnlocked = true,
                        size = 140.dp
                    )
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = badgeName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$milestone Day Streak",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = motivationMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onViewCollection,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Collection")
                        }
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Continue")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfettiAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "particles")
    val animProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        // Render 24 multi-colored confetti particles falling/spreading
        val randomColors = listOf(Color.Yellow, Color.Cyan, Color.Green, Color.Magenta, Color.Red, Color(0xFFFF9100))
        for (i in 0 until 24) {
            val seed = i * 23.4f
            val xPos = (width * 0.5f) + (width * 0.45f * sin(seed) * animProgress)
            val yPos = (height * 0.4f) + (height * 0.5f * animProgress) - (height * 0.2f * cos(seed))
            val sizeRadius = 6.dp.toPx() + (3.dp.toPx() * sin(seed + animProgress))
            
            drawCircle(
                color = randomColors[i % randomColors.size].copy(alpha = 1f - animProgress),
                radius = sizeRadius,
                center = Offset(xPos, yPos)
            )
        }
    }
}
