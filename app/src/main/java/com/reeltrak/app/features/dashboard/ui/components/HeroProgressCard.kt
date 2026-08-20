package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.StreakStats
import com.reeltrak.app.features.dashboard.ui.components.AchievementBadge

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign

@Composable
fun HeroProgressCard(
    dailyIntentProvider: () -> Int,
    currentReelsProvider: () -> Int,
    streakStatsProvider: () -> StreakStats,
    isLimitLocked: Boolean = false,
    onSaveGoal: (Int) -> Unit = {},
    onStreakClick: () -> Unit = {}
) {
    val dailyIntent = dailyIntentProvider()
    val currentReels = currentReelsProvider()
    val streakStats = streakStatsProvider()
    val progress = if (dailyIntent > 0) currentReels.toFloat() / dailyIntent else 0f
    
    val isNotSet = dailyIntent == -1
    val isNoLimit = dailyIntent == 0

    // Today's Limit Toggle state
    val defaultDisplay = if (dailyIntent >= 0) dailyIntent.toString() else "500"
    var textValue by remember(dailyIntent) { mutableStateOf(defaultDisplay) }
    val focusManager = LocalFocusManager.current

    // Pulsating animation for fire icon
    val infiniteTransition = rememberInfiniteTransition(label = "fire_pulsate")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), // Premium radius matching the dashboard design language
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Upper Section: Progress Row and Integrated Limit Toggle
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Today's Progress Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Today's Progress",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "$currentReels",
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isNotSet) "reels" else if (isNoLimit) "reels (no limit)" else "/ $dailyIntent reels",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }

                    // Scaled circular progress indicator aligned to center-right
                    if (!isNotSet && !isNoLimit) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(68.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { 1f },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 7.dp,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                            )
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 7.dp,
                                color = MaterialTheme.colorScheme.primary
                            )

                            val percentageString = "${(progress * 100).toInt()}%"
                            val fontSize = if (percentageString.length <= 3) 16.sp else 12.sp

                            Text(
                                text = percentageString,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = fontSize,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = LocalTextStyle.current.copy(
                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                        includeFontPadding = false
                                    )
                                )
                            )
                        }
                    } else if (isNotSet) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = "⚠️ Set Limit",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // Integrated Today's Limit Selector
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Daily Limit",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isLimitLocked) "Already set for today" else if (isNotSet) "Limit not set" else "Adjust your goal",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isNotSet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { newValue: String ->
                                    if (!isLimitLocked && (newValue.isEmpty() || newValue.all { char -> char.isDigit() })) {
                                        textValue = newValue
                                    }
                                },
                                enabled = !isLimitLocked,
                                modifier = Modifier.width(80.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                        val goal = textValue.toIntOrNull() ?: 500
                                        val validatedGoal = goal.coerceIn(0, 10000)
                                        textValue = validatedGoal.toString()
                                        onSaveGoal(validatedGoal)
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    val goal = textValue.toIntOrNull() ?: 500
                                    val validatedGoal = goal.coerceIn(0, 10000)
                                    textValue = validatedGoal.toString()
                                    onSaveGoal(validatedGoal)
                                },
                                enabled = !isLimitLocked,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Text("Save", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            // Compact horizontal linear progress bar
            if (!isNotSet && !isNoLimit) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            }

            // Simplified Current Streak section
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onStreakClick),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "CURRENT STREAK",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${streakStats.currentStreak}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = " Days",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(scaleX = scale, scaleY = scale),
                            shape = CircleShape,
                            color = if (streakStats.currentStreak > 0) Color(0xFFFF5722).copy(alpha = 0.1f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                        ) {}
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = "Streak",
                            modifier = Modifier
                                .size(32.dp)
                                .graphicsLayer(scaleX = scale, scaleY = scale),
                            tint = if (streakStats.currentStreak > 0) Color(0xFFFF5722) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}
