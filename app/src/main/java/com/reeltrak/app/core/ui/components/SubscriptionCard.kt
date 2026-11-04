package com.reeltrak.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Formats remaining duration milliseconds into a user-facing date-based remaining duration string.
 * Example: "6 days remaining", "1 day remaining".
 */
fun formatRemainingDurationText(durationMs: Long): String {
    if (durationMs <= 0L) return "Expired"
    val days = (durationMs + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)
    return when {
        days > 1 -> "$days days remaining"
        else -> "1 day remaining"
    }
}

/**
 * Formats an epoch timestamp to "d MMMM yyyy" in local device timezone.
 */
fun formatEpochDate(epochMs: Long?): String? {
    if (epochMs == null || epochMs <= 0L) return null
    return try {
        val instant = Instant.ofEpochMilli(epochMs)
        DateTimeFormatter.ofPattern("d MMMM yyyy")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (e: Exception) {
        null
    }
}

/**
 * Formats an epoch timestamp to "d MMMM yyyy, h:mm a" in local device timezone for detailed views if needed.
 */
fun formatEpochDateTime(epochMs: Long?): String? = formatEpochDate(epochMs)

@Composable
fun SubscriptionCard(
    subscriptionInfo: SubscriptionInfo,
    accessState: AccessState,
    remainingDurationMs: Long,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnlocked = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE
    val isTrial = accessState == AccessState.TRIAL
    val isActive = accessState == AccessState.ACTIVE

    val isCancelled = subscriptionInfo.status == SubscriptionStatus.CANCELLED
    val formattedEndDate = remember(subscriptionInfo.trialEnd, subscriptionInfo.currentPeriodEnd, accessState) {
        when (accessState) {
            AccessState.INITIALIZING -> ""
            AccessState.TRIAL -> formatEpochDate(subscriptionInfo.trialEnd)
            AccessState.ACTIVE -> formatEpochDate(subscriptionInfo.currentPeriodEnd)
            AccessState.FINAL_SYNC_GRACE, AccessState.LOCKED -> formatEpochDate(subscriptionInfo.trialEnd ?: subscriptionInfo.currentPeriodEnd)
        }
    }

    val liveRemainingText = remember(remainingDurationMs) {
        formatRemainingDurationText(remainingDurationMs)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onUpgradeClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.linearGradient(
                        colors = when (accessState) {
                            AccessState.INITIALIZING -> listOf(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f)
                            )
                            AccessState.ACTIVE -> if (isCancelled) {
                                listOf(
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
                                )
                            } else {
                                listOf(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                            }
                            AccessState.TRIAL -> listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
                            )
                            AccessState.FINAL_SYNC_GRACE, AccessState.LOCKED -> listOf(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            )
                        }
                    )
                )
                .padding(24.dp)
        ) {
            Column {
                // Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val headerIcon = when {
                            accessState == AccessState.INITIALIZING -> Icons.Default.HourglassTop
                            accessState == AccessState.LOCKED -> Icons.Default.Lock
                            isCancelled -> Icons.Default.Timer
                            accessState == AccessState.ACTIVE -> Icons.Default.WorkspacePremium
                            else -> Icons.Default.Timer
                        }
                        val headerColor = when {
                            accessState == AccessState.INITIALIZING -> MaterialTheme.colorScheme.primary
                            accessState == AccessState.LOCKED -> MaterialTheme.colorScheme.error
                            isCancelled -> MaterialTheme.colorScheme.secondary
                            accessState == AccessState.ACTIVE -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }

                        Icon(
                            imageVector = headerIcon,
                            contentDescription = null,
                            tint = headerColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        val headerLabel = when {
                            accessState == AccessState.INITIALIZING -> "Subscription"
                            accessState == AccessState.LOCKED -> "Subscription Required"
                            isCancelled -> "Subscription Cancelled"
                            accessState == AccessState.ACTIVE -> "Membership"
                            else -> "Free Trial"
                        }

                        Text(
                            text = headerLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = headerColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Badge Pill
                    val badgeText = when {
                        accessState == AccessState.INITIALIZING -> "Checking..."
                        accessState == AccessState.LOCKED -> if (subscriptionInfo.trialEnd != null && subscriptionInfo.status == SubscriptionStatus.NONE) "Trial Expired" else "Locked"
                        isCancelled -> "Cancelled"
                        accessState == AccessState.ACTIVE -> subscriptionInfo.plan.displayName
                        else -> "7-Day Free Trial"
                    }

                    val badgeBg = when {
                        accessState == AccessState.INITIALIZING -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        accessState == AccessState.LOCKED -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        isCancelled -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        accessState == AccessState.ACTIVE -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    }

                    val badgeColor = when {
                        accessState == AccessState.INITIALIZING -> MaterialTheme.colorScheme.onSurfaceVariant
                        accessState == AccessState.LOCKED -> MaterialTheme.colorScheme.error
                        isCancelled -> MaterialTheme.colorScheme.secondary
                        accessState == AccessState.ACTIVE -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeBg)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = badgeColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                val titleText = when {
                    accessState == AccessState.INITIALIZING -> "Checking Status"
                    accessState == AccessState.LOCKED -> "Subscription Required"
                    isCancelled -> "Subscription Cancelled"
                    accessState == AccessState.ACTIVE -> subscriptionInfo.plan.displayName
                    else -> "Free Trial"
                }

                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Countdown and End Date Box for TRIAL and ACTIVE
                if (isUnlocked && remainingDurationMs > 0L) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.HourglassTop,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isTrial) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = liveRemainingText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (formattedEndDate != null) {
                                val endPrefix = when {
                                    isTrial -> "Ends: "
                                    isCancelled -> "Access until: "
                                    else -> "Expires: "
                                }
                                Text(
                                    text = "$endPrefix$formattedEndDate",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Description Subtitle
                val descriptionText = when {
                    accessState == AccessState.INITIALIZING -> "Verifying your subscription status with the server..."
                    accessState == AccessState.LOCKED -> "No active subscription"
                    isCancelled -> "Your subscription was cancelled, but you still have access until the end of your billing cycle."
                    accessState == AccessState.ACTIVE -> "Your subscription is active. All features, counter overlays, limits, and habit analytics are unlocked."
                    else -> "You have full access to all features, counter overlays, limits, and habit analytics during your trial."
                }

                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // CTA Button when locked (suppressed during INITIALIZING)
                if (!isUnlocked && accessState != AccessState.INITIALIZING) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onUpgradeClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Subscribe Now", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Backward compatibility overload for legacy references.
 */
@Composable
fun SubscriptionCard(
    currentPlan: String,
    isPremium: Boolean = false,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SubscriptionCard(
        subscriptionInfo = SubscriptionInfo(
            status = if (isPremium) SubscriptionStatus.ACTIVE else SubscriptionStatus.NONE,
            accessState = if (isPremium) AccessState.ACTIVE else AccessState.LOCKED
        ),
        accessState = if (isPremium) AccessState.ACTIVE else AccessState.LOCKED,
        remainingDurationMs = 0L,
        onUpgradeClick = onUpgradeClick,
        modifier = modifier
    )
}
