package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.DailyBlocks
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun GoalCalendar(dailyStats: List<DailyBlocks>) {
    // Local state for month navigation
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    val today = remember { LocalDate.now() }
    
    // Create a map for quick lookup by epoch day
    val statsMap = remember(dailyStats) {
        dailyStats.associateBy { it.dateEpochDay }
    }

    // Pre-calculate calendar grid data to avoid date math during composition
    val calendarGridData = remember(currentMonth) {
        val daysInMonth = currentMonth.lengthOfMonth()
        val firstOfMonth = currentMonth.atDay(1)
        val firstDayOfWeek = firstOfMonth.dayOfWeek.value
        val previousMonth = currentMonth.minusMonths(1)
        val daysInPrevMonth = previousMonth.lengthOfMonth()
        
        Triple(daysInMonth, firstDayOfWeek, daysInPrevMonth)
    }
    val (daysInMonth, firstDayOfWeek, daysInPrevMonth) = calendarGridData

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 1. HEADER: < Month Year >
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Previous Month",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                Text(
                    text = "${currentMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${currentMonth.year}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Next Month",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. WEEKDAY LABELS
            Row(modifier = Modifier.fillMaxWidth()) {
                val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                weekdays.forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. CALENDAR GRID
            var dayCounter = 1
            var nextMonthDayCounter = 1

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in 0 until 6) {
                    // Stop if we have already displayed all days of the current month
                    // and we are not in the first row.
                    if (row > 0 && dayCounter > daysInMonth) break
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (col in 1..7) {
                            val cellIndex = row * 7 + col
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    // Previous month days (Faded)
                                    cellIndex < firstDayOfWeek -> {
                                        val day = daysInPrevMonth - (firstDayOfWeek - cellIndex - 1)
                                        Text(
                                            text = day.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                        )
                                    }
                                    // Current month days
                                    dayCounter <= daysInMonth -> {
                                        val day = dayCounter
                                        val isToday = currentMonth.year == today.year && 
                                                      currentMonth.month == today.month && 
                                                      day == today.dayOfMonth
                                        
                                        val date = currentMonth.atDay(day)
                                        val dayData = statsMap[date.toEpochDay()]

                                        val cellColor = remember(day, statsMap, currentMonth) {
                                            val data = statsMap[date.toEpochDay()]
                                            if (data == null || data.percentage < 0f) {
                                                Color.Transparent
                                            } else if (data.isSkullDay) {
                                                Color.Transparent
                                            } else if (data.isGraceDay) {
                                                Color(0xFFEF9A9A) // Light Red for Grace Zone
                                            } else {
                                                val fraction = data.percentage.coerceIn(0f, 1f)
                                                androidx.compose.ui.graphics.lerp(Color(0xFF1B5E20), Color(0xFF81C784), fraction)
                                            }
                                        }

                                        Surface(
                                            modifier = Modifier.fillMaxSize(),
                                            shape = RoundedCornerShape(8.dp),
                                            color = cellColor,
                                            border = if (isToday) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                if (dayData?.isSkullDay == true) {
                                                    Text(text = "💀", fontSize = 18.sp)
                                                } else {
                                                    Text(
                                                        text = day.toString(),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (cellColor != Color.Transparent) Color.White else if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                        dayCounter++
                                    }
                                    // Next month days (Faded)
                                    else -> {
                                        Text(
                                            text = nextMonthDayCounter.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                        )
                                        nextMonthDayCounter++
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. COLOR LEGEND
            Text(
                "Color Legend", 
                style = MaterialTheme.typography.labelMedium, 
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Gradient Item
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.5f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF1B5E20), Color(0xFF81C784))
                                )
                            )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("0% → 100%", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Grace Zone Item
                LegendItem(Color(0xFFEF9A9A), "Grace Zone", Modifier.weight(1f))

                // Skull Day Item
                LegendItem(Color.Transparent, "> Limit + 30", Modifier.weight(1f), isSkull = true)
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier, isSkull: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isSkull) MaterialTheme.colorScheme.surfaceVariant else color)
                .then(if (isSkull) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (isSkull) Text("💀", fontSize = 6.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
