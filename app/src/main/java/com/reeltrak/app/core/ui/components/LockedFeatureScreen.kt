package com.reeltrak.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.ReFocusFloatingPillCta
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.core.ui.theme.specularBorder

enum class LockedFeature(
    val title: String,
    val icon: ImageVector,
    val subtitle: String,
    val description: String,
    val highlights: List<String>
) {
    ANALYTICS(
        title = "Analytics",
        icon = Icons.Outlined.BarChart,
        subtitle = "Detailed Usage Insights & Trends",
        description = "Deepen your focus awareness with historical averages, lifetime metrics, and day-to-day distribution charts.",
        highlights = listOf(
            "Lifetime reels watched & daily rolling averages",
            "Weekly activity trends and hourly distribution",
            "Highest single day & historical focus comparisons"
        )
    ),
    CALENDAR(
        title = "Calendar & Streaks",
        icon = Icons.Outlined.DateRange,
        subtitle = "Discipline Streaks & Habit Calendar",
        description = "Build unstoppable discipline by protecting your daily limits and tracking continuous streaks over time.",
        highlights = listOf(
            "Full monthly discipline calendar & habit grid",
            "Continuous focus streaks & milestone tracking",
            "Exclusive achievement badges (7, 30, 50, 100, 365 Days)"
        )
    )
}

/**
 * Editorial Locked Feature Screen.
 *
 * Rendered when a non-entitled (Free) user views the Analytics or Calendar tabs.
 *
 * CRITICAL ACCESS & PRIVACY RULE:
 * This composable receives ZERO analytics or calendar data and triggers ZERO repository/network fetches.
 * It strictly displays an intentional, premium upgrade invitation adhering to the Re:Focus Light aesthetic.
 */
@Composable
fun LockedFeatureScreen(
    feature: LockedFeature,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ReFocusTheme.colors
    val containerShape = RoundedCornerShape(24.dp)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = ReFocusColors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = feature.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )

                    // Subdued Locked Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surfaceElevated)
                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PREMIUM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Feature Hero Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 2.dp,
                            shape = containerShape,
                            ambientColor = colors.shadowAmbient,
                            spotColor = colors.shadowSpot
                        )
                        .clip(containerShape)
                        .background(colors.surface)
                        .border(specularBorder(strokeWidth = 1.dp, shape = containerShape), containerShape)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Icon Disk with subtle accent aura
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(colors.accentSoft)
                                .border(1.dp, colors.accent.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = feature.icon,
                                contentDescription = null,
                                tint = colors.accentDark,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = feature.subtitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = colors.textPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = feature.description,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp,
                            color = colors.textSecondary
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Highlight checklist
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            feature.highlights.forEach { highlight ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(colors.accentSoft),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = colors.accentDark,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = highlight,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Bottom Action Area
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ReFocusFloatingPillCta(
                    text = "UNLOCK WITH PREMIUM",
                    icon = Icons.Default.Lock,
                    onClick = onUpgradeClick,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Includes 7-Day Free Trial • Cancel anytime",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted
                )
            }
        }
    }
}
