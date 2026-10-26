package com.reeltrak.app.features.subscription.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.*
import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountType
import com.reeltrak.app.domain.models.SubscriptionInfo

/**
 * Centrally displayed full-screen Locked State screen when the user's trial or paid subscription has expired
 * or when a Razorpay test account needs to complete the test payment.
 *
 * Re:Focus Editorial Locked Shell:
 * - Ambient radial lime backdrop
 * - 3D Focus Aperture motif
 * - Tactile Re:Focus Floating Pill CTA
 * - Cohesive neutral + lime palette
 */
@Composable
fun LockedAppScreen(
    subscriptionInfo: SubscriptionInfo,
    onSubscribeClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    accountAccessInfo: AccountAccessInfo = AccountAccessInfo.DEFAULT,
    modifier: Modifier = Modifier
) {
    val isTesterAccount = !accountAccessInfo.isLoading && accountAccessInfo.accountType == AccountType.TESTER
    val colors = ReFocusTheme.colors
    val containerShape = RoundedCornerShape(20.dp)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: Header & Badge
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))

                // Brand Tag + Status Badge Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(colors.accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "RE:FOCUS",
                            style = ReFocusTheme.Typography.BrandTag.copy(
                                color = colors.textMuted
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isTesterAccount) colors.accentSoft else colors.error.copy(alpha = 0.12f)
                            )
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (isTesterAccount) colors.accent.copy(alpha = 0.4f) else colors.error.copy(alpha = 0.35f)
                                ),
                                RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isTesterAccount) "TEST ACCOUNT" else "ACCESS LOCKED",
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontSize = 10.sp,
                                color = if (isTesterAccount) (if (colors.isDark) colors.accent else colors.accentDark) else colors.error
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 3D Focus Aperture with Ambient Glow
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AmbientRadialBackdrop(
                        modifier = Modifier.size(110.dp),
                        glowColor = if (isTesterAccount) colors.accent else colors.error,
                        glowAlpha = if (colors.isDark) 0.28f else 0.12f
                    )

                    FocusAperture3D(
                        sizeDp = 88.dp,
                        coreColor = if (isTesterAccount) colors.accent else colors.error
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Headline
                Text(
                    text = if (isTesterAccount) {
                        "Complete Review Payment"
                    } else {
                        "Discipline Reclaimed."
                    },
                    style = ReFocusTheme.Typography.EditorialHeader.copy(
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                        letterSpacing = (-1.0).sp,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Supporting Text
                Text(
                    text = if (isTesterAccount) {
                        "You're using the Re:Focus review account. Complete the ₹79 Razorpay test transaction to unlock the app."
                    } else {
                        "Your trial period has concluded. Start monthly membership to keep automatic feed halting and unhackable limits."
                    },
                    style = ReFocusTheme.Typography.Body.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Compact Plan Information Surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(containerShape)
                        .background(colors.surfaceFloating)
                        .border(specularBorder(strokeWidth = 1.dp, shape = containerShape), containerShape)
                        .padding(20.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "MONTHLY AUTOPAY",
                                    style = ReFocusTheme.Typography.SectionTitle.copy(
                                        fontSize = 11.sp,
                                        letterSpacing = 1.2.sp,
                                        color = colors.textMuted
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isTesterAccount) "Razorpay Test Sandbox" else "Full Re:Focus Pro Protection",
                                    style = ReFocusTheme.Typography.Body.copy(
                                        fontSize = 12.sp,
                                        color = colors.textSecondary
                                    )
                                )
                            }

                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "₹79",
                                    style = ReFocusTheme.Typography.BigMetric.copy(
                                        fontSize = 32.sp,
                                        lineHeight = 32.sp,
                                        color = colors.textPrimary
                                    )
                                )
                                Text(
                                    text = " / mo",
                                    style = ReFocusTheme.Typography.Body.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textSecondary
                                    ),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        HorizontalDivider(
                            thickness = ReFocusTheme.Dimens.HairlineWidth,
                            color = colors.border
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(colors.accentSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (colors.isDark) colors.accent else colors.accentDark,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isTesterAccount) {
                                    "No real money will be charged (test mode)"
                                } else {
                                    "Automatic feed interruption & strict anti-bypass"
                                },
                                style = ReFocusTheme.Typography.Body.copy(
                                    fontSize = 12.sp,
                                    color = colors.textSecondary
                                )
                            )
                        }
                    }
                }
            }

            // Bottom Section: Action Buttons & Trust Reassurance
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Primary Action Button: ReFocusFloatingPillCta
                ReFocusFloatingPillCta(
                    text = if (isTesterAccount) "CONTINUE TEST CHECKOUT" else "START MEMBERSHIP",
                    priceTag = "₹79 / MO",
                    onClick = onSubscribeClick,
                    height = 56.dp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Secondary Action: Settings & Account
                ReFocusFloatingPillButton(
                    text = "Settings & Account",
                    icon = Icons.Default.Settings,
                    onClick = onOpenSettingsClick,
                    isSelected = false,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Trust reassurance footnote
                Text(
                    text = if (isTesterAccount) {
                        "REVIEW ACCOUNT • RAZORPAY TEST ENVIRONMENT"
                    } else {
                        "SECURE 256-BIT CHECKOUT POWERED BY RAZORPAY • CANCEL ANYTIME"
                    },
                    style = ReFocusTheme.Typography.SectionTitle.copy(
                        fontSize = 9.5.sp,
                        letterSpacing = 1.1.sp,
                        color = colors.textMuted
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
