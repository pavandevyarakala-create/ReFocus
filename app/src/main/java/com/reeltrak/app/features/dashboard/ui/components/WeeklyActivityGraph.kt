package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.DailyBlocks
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.TimeBlock
import kotlin.math.ceil

@Composable
fun WeeklyActivityGraph(
    weeklyData: List<DailyBlocks>,
    onSessionClick: (TimeBlock) -> Unit // Kept for compatibility
) {
    // 1. Calculate adaptive maximum for Y-Axis
    // Round UP to the nearest multiple of 100.
    val maxReels = weeklyData.maxOfOrNull { it.totalReelsScrolled.toInt() } ?: 0
    val targetMaxY = if (maxReels == 0) 100 else (((maxReels - 1) / 100) + 1) * 100
    
    // Remember displayed max to prevent jitter, but allow it to update
    var displayedMaxY by remember { mutableIntStateOf(targetMaxY) }

    // Detect when a new day starts or data changes significantly
    val latestDayLabel = weeklyData.lastOrNull()?.dayLabel ?: ""
    var currentDayLabel by remember { mutableStateOf(latestDayLabel) }

    LaunchedEffect(targetMaxY, latestDayLabel) {
        if (latestDayLabel != currentDayLabel) {
            currentDayLabel = latestDayLabel
            displayedMaxY = targetMaxY
        } else if (targetMaxY > displayedMaxY) {
            displayedMaxY = targetMaxY
        }
    }

    // Safety sync
    if (targetMaxY > displayedMaxY) {
        displayedMaxY = targetMaxY
    }

    val animatedMaxY by animateIntAsState(
        targetValue = displayedMaxY,
        animationSpec = tween(durationMillis = 600),
        label = "maxY"
    )

    Card(
        modifier = Modifier.fillMaxWidth().height(480.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(top = 24.dp, bottom = 24.dp).fillMaxSize()) {
            
            // 1. HEADER
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Daily Reel Count", 
                    style = MaterialTheme.typography.titleLarge, 
                    fontWeight = FontWeight.Bold, 
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 2. LEGEND
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reels Scrolled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 3. BAR CHART AREA
            val horizontalScrollState = rememberScrollState()
            val dayColumnWidth = 85.dp
            val yAxisWidth = 45.dp
            val xAxisHeight = 28.dp
            val axisThickness = 3.dp
            
            // Initial scroll to today (the right-most/latest day)
            // We must wait for the Layout pass to determine the maxValue of the scroll state.
            // If we scroll before layout, maxValue is 0 and the command is ignored.
            var initialScrollDone by remember { mutableStateOf(false) }
            LaunchedEffect(horizontalScrollState.maxValue, weeklyData) {
                if (weeklyData.isNotEmpty() && horizontalScrollState.maxValue > 0 && !initialScrollDone) {
                    horizontalScrollState.scrollTo(horizontalScrollState.maxValue)
                    initialScrollDone = true
                }
            }
            
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 24.dp)) {
                val chartHeight = maxHeight - xAxisHeight - axisThickness
                
                Box(modifier = Modifier.fillMaxSize()) {
                    
                    val gridLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    
                    // ==========================================
                    // LAYER 1 & 4: Y-Axis Grid Lines, Labels & Fixed Axes Lines
                    // ==========================================
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(chartHeight)
                            .drawBehind {
                                // Draw grid lines at every 100 units
                                for (yLabel in 0..animatedMaxY step 100) {
                                    val fraction = 1f - (yLabel.toFloat() / animatedMaxY.coerceAtLeast(1).toFloat())
                                    val yOffset = size.height * fraction
                                    
                                    drawLine(
                                        color = gridLineColor,
                                        start = androidx.compose.ui.geometry.Offset(yAxisWidth.toPx() + axisThickness.toPx(), yOffset),
                                        end = androidx.compose.ui.geometry.Offset(size.width, yOffset),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                                
                                // Thick Y-axis
                                drawLine(
                                    color = axisColor,
                                    start = androidx.compose.ui.geometry.Offset(yAxisWidth.toPx() + axisThickness.toPx() / 2f, 0f),
                                    end = androidx.compose.ui.geometry.Offset(yAxisWidth.toPx() + axisThickness.toPx() / 2f, size.height + axisThickness.toPx()),
                                    strokeWidth = axisThickness.toPx()
                                )
                                
                                // Thick X-axis
                                drawLine(
                                    color = axisColor,
                                    start = androidx.compose.ui.geometry.Offset(yAxisWidth.toPx(), size.height + axisThickness.toPx() / 2f),
                                    end = androidx.compose.ui.geometry.Offset(size.width, size.height + axisThickness.toPx() / 2f),
                                    strokeWidth = axisThickness.toPx()
                                )
                            }
                    ) {
                        // Draw Y-Axis Labels
                        for (yLabel in 0..animatedMaxY step 100) {
                            val fraction = 1f - (yLabel.toFloat() / animatedMaxY.coerceAtLeast(1).toFloat())
                            val yOffset = chartHeight * fraction
                            
                            Box(
                                modifier = Modifier
                                    .width(yAxisWidth)
                                    .padding(end = 6.dp)
                                    .offset(y = yOffset - 8.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = yLabel.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // ==========================================
                    // LAYER 2: Dates (X-Axis)
                    // ==========================================
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = yAxisWidth + axisThickness)
                            .align(Alignment.BottomStart)
                            .height(xAxisHeight)
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            weeklyData.forEach { daily ->
                                Box(
                                    modifier = Modifier.width(dayColumnWidth).fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = daily.dayLabel, 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // ==========================================
                    // LAYER 3: Data Bars
                    // ==========================================
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = yAxisWidth + axisThickness, bottom = xAxisHeight + axisThickness)
                            .height(chartHeight)
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxHeight(),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            weeklyData.forEach { daily ->
                                // BAR HEIGHT CALCULATION: dailyCount / maximumYAxis
                                val fraction = daily.totalReelsScrolled.toFloat() / animatedMaxY.coerceAtLeast(1).toFloat()
                                
                                val animatedFraction by animateFloatAsState(
                                    targetValue = fraction.coerceIn(0f, 1f),
                                    animationSpec = tween(durationMillis = 800),
                                    label = "barHeight"
                                )
                                
                                Box(
                                    modifier = Modifier
                                        .width(dayColumnWidth)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (daily.totalReelsScrolled > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.6f)
                                                .fillMaxHeight(animatedFraction)
                                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                                .background(MaterialTheme.colorScheme.primary)
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
