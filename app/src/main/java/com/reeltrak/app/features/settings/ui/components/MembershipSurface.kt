package com.reeltrak.app.features.settings.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.components.formatEpochDate
import com.reeltrak.app.core.ui.components.formatRemainingDurationText
import com.reeltrak.app.core.ui.theme.ReFocusFloatingPillButton
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.core.ui.theme.specularBorder
import com.reeltrak.app.core.ui.theme.tactilePress
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionStatus

/**
 * Dedicated Re:Focus Membership Surface for Settings.
 *
 * Provides clear visual separation and distinct treatment for:
 * 1. FREE / TRIAL users: Clear tier status, remaining trial countdown, and tactile upgrade CTA.
 * 2. PREMIUM users: Calm active status horizon, renewal date, and manage options.
 */
@Composable
fun MembershipSurface(
    accessState: AccessState,
    subscriptionInfo: SubscriptionInfo,
    remainingDurationMs: Long,
    onManageOrUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ReFocusTheme.colors
    val containerShape = RoundedCornerShape(20.dp)

    val isPro = accessState == AccessState.ACTIVE
    val isTrial = accessState == AccessState.TRIAL
    val isLocked = accessState == AccessState.LOCKED || accessState == AccessState.FINAL_SYNC_GRACE

    val formattedEndDate = remember(subscriptionInfo.currentPeriodEnd, subscriptionInfo.trialEnd) {
        formatEpochDate(subscriptionInfo.currentPeriodEnd) ?: formatEpochDate(subscriptionInfo.trialEnd)
    }

    val remainingText = remember(remainingDurationMs) {
        formatRemainingDurationText(remainingDurationMs)
    }



    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .shadow(
                elevation = if (isPro) 3.dp else 2.dp,
                shape = containerShape,
                ambientColor = colors.shadowAmbient,
                spotColor = colors.shadowSpot
            )
            .clip(containerShape)
            .background(
                if (colors.isDark) {
                    Brush.verticalGradient(
                        listOf(colors.surfaceElevated, colors.surface)
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(colors.surface, colors.surfaceElevated)
                    )
                }
            )
    ) {
        // Restrained lime edge accent for Pro (top hairline only, not a rectangular box)
        if (isPro) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                colors.accent.copy(alpha = 0.6f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            if (isPro) {
                // ==================== PRO SUBSCRIBER STATE (NEUTRAL PREMIUM) ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Controlled Lime Status Indicator
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent.copy(alpha = 0.18f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "RE:FOCUS PRO",
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // Neutral Active Pill with Lime Indicator (Border removed, pure surface contrast)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surfaceElevated)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ACTIVE",
                                style = ReFocusTheme.Typography.SectionTitle.copy(
                                    color = if (colors.isDark) colors.accent else colors.textPrimary,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Renewal & Pricing Inset Surface (Border removed, soft surface contrast)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceSoft)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (subscriptionInfo.cancelAtPeriodEnd) "ACCESS ENDS" else "NEXT RENEWAL",
                                style = ReFocusTheme.Typography.SectionTitle.copy(
                                    color = colors.textMuted,
                                    fontSize = 9.5.sp,
                                    letterSpacing = 1.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formattedEndDate ?: "Monthly cycle",
                                style = ReFocusTheme.Typography.Body.copy(
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }

                        Text(
                            text = "₹79 / mo",
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                ReFocusFloatingPillButton(
                    text = "Manage Membership",
                    onClick = onManageOrUpgradeClick,
                    isSelected = false,
                    modifier = Modifier.fillMaxWidth()
                )

            } else {
                // ==================== FREE / TRIAL / EXPIRED STATE ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isTrial) colors.accent else colors.textMuted)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isTrial -> "7-DAY TRIAL"
                                isLocked -> "ACCESS LOCKED"
                                else -> "FREE TIER"
                            },
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // Status Pill (Border removed, pure surface contrast)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isTrial) colors.accentSoft
                                else if (isLocked) colors.error.copy(alpha = 0.12f)
                                else colors.surfaceSoft
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when {
                                isTrial -> "TRIAL ACTIVE"
                                isLocked -> "EXPIRED"
                                else -> "BASIC"
                            },
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = when {
                                    isTrial -> if (colors.isDark) colors.accent else colors.accentDark
                                    isLocked -> colors.error
                                    else -> colors.textSecondary
                                },
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when {
                        isTrial -> "$remainingText in your trial. Lock in continuous feed interruption and strict daily limits."
                        isLocked -> "Trial has ended. Reel tracking and strict limits are locked. Activate Pro to resume protection."
                        else -> "Unlock automatic reel detection, midnight limit locking, and cloud sync across devices."
                    },
                    style = ReFocusTheme.Typography.Body.copy(
                        color = colors.textSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Price and Upgrade Action (Physically raised tactile button)
                val interactionSource = remember { MutableInteractionSource() }
                val pillBtnShape = RoundedCornerShape(999.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .shadow(
                            elevation = 3.dp,
                            shape = pillBtnShape,
                            ambientColor = if (colors.isDark) colors.shadowAmbient else colors.shadowAccent,
                            spotColor = if (colors.isDark) colors.shadowSpot else colors.shadowAccent
                        )
                        .tactilePress(interactionSource = interactionSource)
                        .clip(pillBtnShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.accentHighlight, colors.accentPressed)
                            )
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onManageOrUpgradeClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val ctaContentColor = if (colors.isDark) colors.textInverse else colors.textPrimary
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isTrial) "LOCK IN PRO • ₹79 / MO" else "UNLOCK RE:FOCUS PRO • ₹79 / MO",
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = ctaContentColor, // crisp dark on lime in both modes
                                fontSize = 11.5.sp,
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            tint = ctaContentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
