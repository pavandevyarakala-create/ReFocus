package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.DailyBlocks
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun WeeklyActivityGraph(
    weeklyData: List<DailyBlocks>,
    modifier: Modifier = Modifier
) {
    // 1. Calculate adaptive maximum for Y-Axis
    val maxReels = weeklyData.maxOfOrNull { it.totalReelsScrolled.toInt() } ?: 0
    val targetMaxY = if (maxReels == 0) 100 else (((maxReels - 1) / 100) + 1) * 100
    
    var selectedDay by remember { mutableStateOf<DailyBlocks?>(null) }
    
    Card(
        modifier = modifier.fillMaxWidth().height(320.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Last 7 Days", 
                    style = MaterialTheme.typography.titleMedium, 
                    fontWeight = FontWeight.Bold, 
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                AnimatedContent(
                    targetState = selectedDay,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "selected_day_label"
                ) { day ->
                    if (day != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = "${day.totalReelsScrolled} Reels",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Y-Axis Labels
                Column(
                    modifier = Modifier.height(180.dp).width(32.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    val step = targetMaxY / 5
                    for (i in 5 downTo 0) {
                        Text(
                            text = (i * step).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val chartHeight = 180.dp
                    val gridLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)

                    // Grid Lines
                    Column(
                        modifier = Modifier.fillMaxWidth().height(chartHeight), 
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        repeat(6) {
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(gridLineColor))
                        }
                    }

                    // Chart & X-Axis Column Container
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Plotting Area (Height matched exactly to Y-axis)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(chartHeight)
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            weeklyData.forEach { daily ->
                                val isSelected = selectedDay?.dateEpochDay == daily.dateEpochDay
                                val fraction = daily.totalReelsScrolled.toFloat() / targetMaxY.coerceAtLeast(1).toFloat()
                                val animatedFraction by animateFloatAsState(
                                    targetValue = fraction.coerceIn(0f, 1f),
                                    animationSpec = tween(durationMillis = 800),
                                    label = "barHeight"
                                )
                                
                                val barColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                val barBackground = Brush.verticalGradient(
                                    listOf(MaterialTheme.colorScheme.primary, barColor)
                                )

                                Box(
                                    contentAlignment = Alignment.BottomCenter,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            selectedDay = if (isSelected) null else daily
                                        }
                                ) {
                                    if (animatedFraction > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.55f)
                                                .height(chartHeight * animatedFraction)
                                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                                .background(barBackground)
                                                .then(
                                                    if (isSelected) Modifier.background(Color.White.copy(alpha = 0.2f)) else Modifier
                                                )
                                                .then(
                                                    if (isSelected) Modifier.border(
                                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                                        RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                                    ) else Modifier
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // X-Axis Date Labels Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            weeklyData.forEach { daily ->
                                val isSelected = selectedDay?.dateEpochDay == daily.dateEpochDay
                                val dateLabel = remember(daily.dateEpochDay) {
                                    val today = LocalDate.now().toEpochDay()
                                    if (daily.dateEpochDay == today) {
                                        "Today"
                                    } else {
                                        val date = LocalDate.ofEpochDay(daily.dateEpochDay)
                                        date.format(DateTimeFormatter.ofPattern("dd/MM"))
                                    }
                                }

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dateLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

