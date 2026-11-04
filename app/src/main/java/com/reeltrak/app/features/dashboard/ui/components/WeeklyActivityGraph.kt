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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.DailyBlocks
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.reeltrak.app.core.ui.theme.ReFocusColors

@Composable
fun WeeklyActivityGraph(
    weeklyData: List<DailyBlocks>,
    trustedBusinessDate: LocalDate = LocalDate.now(),
    modifier: Modifier = Modifier
) {
    // 1. Calculate adaptive maximum for Y-Axis
    val maxReels = weeklyData.maxOfOrNull { it.totalReelsScrolled.toInt() } ?: 0
    val targetMaxY = if (maxReels == 0) 100 else (((maxReels - 1) / 100) + 1) * 100
    
    var selectedDay by remember { mutableStateOf<DailyBlocks?>(null) }
    val todayEpoch = trustedBusinessDate.toEpochDay()
    
    val cardShape = RoundedCornerShape(24.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp)
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ReFocusColors.border)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isNarrowScreen = maxWidth < 350.dp
            val cardHorizontalPadding = if (isNarrowScreen) 12.dp else 16.dp
            val yAxisWidth = if (isNarrowScreen) 24.dp else 28.dp
            val axisSpacerWidth = if (isNarrowScreen) 6.dp else 8.dp
            val chartHorizontalPadding = if (isNarrowScreen) 2.dp else 4.dp
            val labelFontSize = if (isNarrowScreen) 9.sp else 9.5.sp
            val labelLetterSpacing = if (isNarrowScreen) (-0.4).sp else (-0.2).sp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = cardHorizontalPadding, vertical = 20.dp)
            ) {
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
                                color = ReFocusColors.accentSoft
                            ) {
                                Text(
                                    text = "${day.totalReelsScrolled} Reels",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ReFocusColors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(18.dp))
                
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val chartHeight = 180.dp

                    // Y-Axis Labels
                    Column(
                        modifier = Modifier.height(chartHeight).width(yAxisWidth),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        val step = targetMaxY / 5
                        for (i in 5 downTo 0) {
                            Text(
                                text = (i * step).toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = if (isNarrowScreen) 9.sp else 10.sp,
                                    lineHeight = 12.sp
                                ),
                                color = ReFocusColors.textMuted,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(axisSpacerWidth))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val gridLineColor = ReFocusColors.chartGrid

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
                                    .padding(horizontal = chartHorizontalPadding),
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
                                    
                                    val barColor = if (isSelected) ReFocusColors.chart else ReFocusColors.chart.copy(alpha = 0.7f)
                                    val barBackground = Brush.verticalGradient(
                                        listOf(ReFocusColors.chart, barColor.copy(alpha = 0.45f))
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
                                                        if (isSelected) Modifier.background(ReFocusColors.accent.copy(alpha = 0.2f)) else Modifier
                                                    )
                                                    .then(
                                                        if (isSelected) Modifier.border(
                                                            BorderStroke(1.5.dp, ReFocusColors.textPrimary),
                                                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                                        ) else Modifier
                                                    )
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // X-Axis Date Labels Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = chartHorizontalPadding),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                weeklyData.forEach { daily ->
                                    val isSelected = selectedDay?.dateEpochDay == daily.dateEpochDay
                                    val isToday = daily.isToday || daily.dateEpochDay == todayEpoch
                                    val dateLabel = remember(daily.dateEpochDay, daily.isToday, todayEpoch) {
                                        if (isToday) {
                                            "Today"
                                        } else {
                                            val date = LocalDate.ofEpochDay(daily.dateEpochDay)
                                            date.format(DateTimeFormatter.ofPattern("dd/MM"))
                                        }
                                    }

                                    val labelColor = when {
                                        isSelected -> if (ReFocusColors.isDark) ReFocusColors.accent else ReFocusColors.accentDark
                                        isToday -> ReFocusColors.textPrimary
                                        else -> ReFocusColors.textSecondary
                                    }

                                    val labelWeight = when {
                                        isSelected -> FontWeight.Bold
                                        isToday -> FontWeight.SemiBold
                                        else -> FontWeight.Medium
                                    }

                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = dateLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = labelFontSize,
                                                letterSpacing = labelLetterSpacing,
                                                lineHeight = 13.sp
                                            ),
                                            color = labelColor,
                                            fontWeight = labelWeight,
                                            maxLines = 1,
                                            softWrap = false
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
}
