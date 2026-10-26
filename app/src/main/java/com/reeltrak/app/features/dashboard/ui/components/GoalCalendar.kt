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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.reeltrak.app.core.ui.theme.ReFocusColors

@Composable
fun GoalCalendar(
    dailyStats: List<DailyBlocks>,
    currentBusinessDate: LocalDate = LocalDate.now(),
    onMonthChanged: (YearMonth) -> Unit = {}
) {
    // Local state for month navigation anchored to current local date
    var currentMonth by remember(currentBusinessDate) { mutableStateOf(YearMonth.from(currentBusinessDate)) }
    val today = currentBusinessDate
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    
    LaunchedEffect(currentMonth) {
        onMonthChanged(currentMonth)
    }

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

    val calendarShape = RoundedCornerShape(22.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = calendarShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            ),
        shape = calendarShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                        tint = ReFocusColors.textPrimary
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
                        tint = ReFocusColors.textPrimary
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
                                        val date = currentMonth.atDay(day)
                                        val dayData = statsMap[date.toEpochDay()]
                                        val isToday = (dayData?.isToday == true) || (currentMonth.year == today.year && 
                                                      currentMonth.month == today.month && 
                                                      day == today.dayOfMonth)

                                        val warningColor = ReFocusColors.warning
                                        val errorColor = ReFocusColors.error
                                        val accentDarkColor = ReFocusColors.accentDark
                                        val accentColor = ReFocusColors.accent

                                        val cellColor = remember(day, statsMap, currentMonth, warningColor, errorColor, accentDarkColor, accentColor) {
                                            val data = statsMap[date.toEpochDay()]
                                            if (data == null || (data.totalReelsScrolled == 0 && data.dailyGoal <= 0)) {
                                                Color.Transparent
                                            } else if (data.isUnrestrictedDay) {
                                                warningColor // Amber indicator for Unrestricted Day
                                            } else if (data.isDriftedDay) {
                                                errorColor // Error indicator for Drifted Day
                                            } else if (data.isGraceDay) {
                                                errorColor.copy(alpha = 0.5f) // Grace Zone
                                            } else {
                                                val fraction = data.percentage.coerceIn(0f, 1f)
                                                androidx.compose.ui.graphics.lerp(accentDarkColor, accentColor, fraction)
                                            }
                                        }

                                        Surface(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { selectedDate = date },
                                            shape = RoundedCornerShape(8.dp),
                                            color = cellColor,
                                            border = when {
                                                selectedDate == date -> BorderStroke(2.dp, ReFocusColors.textPrimary)
                                                isToday -> BorderStroke(1.5.dp, ReFocusColors.accent)
                                                else -> null
                                            }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = day.toString(),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isToday || dayData?.isDriftedDay == true || dayData?.isUnrestrictedDay == true) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (cellColor != Color.Transparent) (if (cellColor == accentColor) ReFocusColors.textInverse else ReFocusColors.textPrimary) else if (isToday) ReFocusColors.accent else MaterialTheme.colorScheme.onSurface
                                                )
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

            // 4. COLOR LEGEND AND SELECTED DATE STATS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    "Color Legend", 
                    style = MaterialTheme.typography.labelMedium, 
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (selectedDate != null) {
                    val dayEntry = statsMap[selectedDate!!.toEpochDay()]
                    val dateStr = selectedDate!!.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
                    if (dayEntry == null) {
                        Text(
                            text = "$dateStr: No data recorded",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val count = dayEntry.totalReelsScrolled
                        val statusSuffix = when {
                            dayEntry.isUnrestrictedDay -> " (Unrestricted)"
                            dayEntry.isDriftedDay -> " (Drifted)"
                            dayEntry.isGraceDay -> " (Grace Zone)"
                            dayEntry.dailyGoal > 0 && count <= dayEntry.dailyGoal -> " (Within Limit)"
                            else -> ""
                        }
                        Text(
                            text = "$dateStr: $count reels$statusSuffix",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                dayEntry.isUnrestrictedDay -> ReFocusColors.warning
                                dayEntry.isDriftedDay -> ReFocusColors.error
                                dayEntry.isGraceDay -> ReFocusColors.error.copy(alpha = 0.7f)
                                else -> ReFocusColors.accent
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Gradient Item
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.2f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(ReFocusColors.accentDark, ReFocusColors.accent)
                                )
                            )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("0% → 100%", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Grace Zone Item
                LegendItem(ReFocusColors.error.copy(alpha = 0.5f), "Grace", Modifier.weight(0.9f))

                // Drifted Day Item
                LegendItem(ReFocusColors.error, "Drifted", Modifier.weight(0.9f))

                // Unrestricted Day Item
                LegendItem(ReFocusColors.warning, "Unrestricted", Modifier.weight(1.1f))
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
