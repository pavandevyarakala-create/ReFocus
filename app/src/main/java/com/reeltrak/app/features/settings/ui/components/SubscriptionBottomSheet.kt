package com.reeltrak.app.features.settings.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reeltrak.app.core.ui.components.formatEpochDate
import com.reeltrak.app.core.ui.components.formatRemainingDurationText
import com.reeltrak.app.core.ui.theme.AmbientRadialBackdrop
import com.reeltrak.app.core.ui.theme.FocusAperture3D
import com.reeltrak.app.core.ui.theme.ReFocusBrandTheme
import com.reeltrak.app.core.ui.theme.ReFocusFloatingPillButton
import com.reeltrak.app.core.ui.theme.ReFocusFloatingPillCta
import com.reeltrak.app.core.ui.theme.ReFocusTheme
import com.reeltrak.app.core.ui.theme.specularBorder
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.services.CheckoutState
import kotlinx.coroutines.delay

/**
 * Re:Focus Dedicated Editorial Premium Experience.
 *
 * Visual Journey:
 * HERO (Precision 3D Aperture + Ambient Back-Glow) → CAPABILITIES → ₹79/MO ANCHOR → TACTILE CTA → CALM ACTIVE HORIZON
 *
 * Strict Compliance:
 * - Zero technical leakage (no internal Razorpay subscription IDs or payment identifiers).
 * - Multi-layer security preserving authentic Firestore entitlement gating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionBottomSheet(
    subscriptionState: SubscriptionState,
    checkoutState: CheckoutState,
    isRefreshing: Boolean,
    remainingDurationMs: Long = 0L,
    isCancelling: Boolean = false,
    onSubscribeClick: (SubscriptionPlan) -> Unit,
    onRefreshClick: () -> Unit,
    onCancelSubscriptionClick: () -> Unit = {},
    onResetCheckout: () -> Unit = {},
    themeMode: com.reeltrak.app.domain.models.ThemeMode = com.reeltrak.app.domain.models.ThemeMode.LIGHT,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Smooth dismiss only after checkout successfully completes and is verified
    LaunchedEffect(checkoutState) {
        if (checkoutState is CheckoutState.Success) {
            delay(1200L)
            onDismiss()
        }
    }

    ReFocusBrandTheme(darkTheme = false) {
        val colors = ReFocusTheme.colors

        ModalBottomSheet(
            onDismissRequest = {
                onResetCheckout()
                onDismiss()
            },
            sheetState = sheetState,
            containerColor = colors.canvas,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                when (subscriptionState) {
                    is SubscriptionState.Loading -> {
                        PremiumLoadingState()
                    }
                    is SubscriptionState.Error -> {
                        PremiumErrorBanner(
                            message = subscriptionState.message,
                            onRetry = onRefreshClick,
                            isRefreshing = isRefreshing
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        PremiumExperienceContent(
                            info = subscriptionState.fallbackInfo,
                            checkoutState = checkoutState,
                            isRefreshing = isRefreshing,
                            isCancelling = isCancelling,
                            remainingDurationMs = remainingDurationMs,
                            onSubscribeClick = onSubscribeClick,
                            onRefreshClick = onRefreshClick,
                            onCancelSubscriptionClick = onCancelSubscriptionClick,
                            onResetCheckout = onResetCheckout
                        )
                    }
                    is SubscriptionState.Success -> {
                        PremiumExperienceContent(
                            info = subscriptionState.info,
                            checkoutState = checkoutState,
                            isRefreshing = isRefreshing,
                            isCancelling = isCancelling,
                            remainingDurationMs = remainingDurationMs,
                            onSubscribeClick = onSubscribeClick,
                            onRefreshClick = onRefreshClick,
                            onCancelSubscriptionClick = onCancelSubscriptionClick,
                            onResetCheckout = onResetCheckout
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PremiumExperienceContent(
    info: SubscriptionInfo,
    checkoutState: CheckoutState,
    isRefreshing: Boolean,
    isCancelling: Boolean,
    remainingDurationMs: Long,
    onSubscribeClick: (SubscriptionPlan) -> Unit,
    onRefreshClick: () -> Unit,
    onCancelSubscriptionClick: () -> Unit,
    onResetCheckout: () -> Unit
) {
    val colors = ReFocusTheme.colors
    var showCancelDialog by remember { mutableStateOf(false) }

    val isTrial = info.accessState == AccessState.TRIAL || info.status == SubscriptionStatus.TRIAL
    val isActivePremium = info.isPremium && !isTrial
    val isExpired = !info.isUnlocked && (info.status == SubscriptionStatus.EXPIRED || info.status == SubscriptionStatus.HALTED || info.status == SubscriptionStatus.CANCELLED)

    val formattedEndDate = remember(info.currentPeriodEnd, info.trialEnd) {
        if (isTrial) {
            formatEpochDate(info.trialEnd)
        } else {
            formatEpochDate(info.currentPeriodEnd) ?: formatEpochDate(info.trialEnd)
        }
    }

    if (showCancelDialog) {
        CancelAutopayModal(
            endDateText = formattedEndDate,
            isCancelling = isCancelling,
            onConfirm = {
                onCancelSubscriptionClick()
                showCancelDialog = false
            },
            onDismiss = { showCancelDialog = false }
        )
    }

    // 1. EDITORIAL BRAND HERO WITH 3D FOCUS APERTURE & AMBIENT BACKDROP
    PremiumHeroModule(isActivePremium = isActivePremium, isTrial = isTrial)

    Spacer(modifier = Modifier.height(24.dp))

    // 2. TECHNICAL ALERT NOTICES (if mandate is pending or payment halted)
    if (info.status == SubscriptionStatus.PENDING) {
        TechnicalAlertBlock(
            label = "MANDATE PENDING",
            message = "Your recurring mandate is being processed. Full access remains active.",
            isError = false
        )
        Spacer(modifier = Modifier.height(14.dp))
    } else if (info.status == SubscriptionStatus.HALTED) {
        TechnicalAlertBlock(
            label = "PAYMENT HALTED",
            message = "Monthly renewal was unsuccessful. Update payment method via UPI/card to resume full access.",
            isError = true
        )
        Spacer(modifier = Modifier.height(14.dp))
    }

    // 3. CHECKOUT FEEDBACK CARD (Errors / Verification)
    CheckoutStatusModule(
        checkoutState = checkoutState,
        onReset = onResetCheckout
    )

    // 4. MAIN EXPERIENCE: DIVERGES BASED ON ACTIVE VS SALES
    if (isActivePremium) {
        // ACTIVE MEMBERSHIP EXPERIENCE (Clean, non-sales status horizon)
        ActiveMembershipModule(
            info = info,
            formattedEndDate = formattedEndDate,
            isRefreshing = isRefreshing,
            onRefreshClick = onRefreshClick,
            onCancelClick = { showCancelDialog = true },
            onRenewClick = { onSubscribeClick(SubscriptionPlan.MONTHLY) }
        )
    } else {
        // UPGRADE / RENEWAL EXPERIENCE (Genuine capabilities → ₹79/mo → Reactive CTA)
        SalesAndUpgradeModule(
            isTrial = isTrial,
            isExpired = isExpired,
            remainingDurationMs = remainingDurationMs,
            checkoutState = checkoutState,
            onUpgradeClick = { onSubscribeClick(SubscriptionPlan.MONTHLY) }
        )
    }

    Spacer(modifier = Modifier.height(28.dp))

    // 5. MINIMAL TRUST FOOTER (Zero technical leakage)
    Text(
        text = "CANCEL ANYTIME IN SETTINGS • SECURE CHECKOUT",
        style = ReFocusTheme.Typography.SectionTitle.copy(
            fontSize = 10.sp,
            color = colors.inkMuted,
            letterSpacing = 1.2.sp
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Editorial Hero:
 * Precision 3D Focus Aperture + Ambient Back-Glow + PRO (40sp) + Tagline.
 */
@Composable
private fun PremiumHeroModule(
    isActivePremium: Boolean,
    isTrial: Boolean
) {
    val colors = ReFocusTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 3D Precision Aperture Instrument with Ambient Glow
        Box(
            modifier = Modifier.size(130.dp),
            contentAlignment = Alignment.Center
        ) {
            AmbientRadialBackdrop(
                modifier = Modifier.size(130.dp),
                glowColor = colors.accentLime,
                glowAlpha = if (colors.isDark) 0.32f else 0.14f
            )

            FocusAperture3D(
                sizeDp = 96.dp,
                coreColor = if (isActivePremium) colors.accentLime else colors.accentLimeDark
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Brand Identifier Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(colors.accentLime, RoundedCornerShape(1.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "RE:FOCUS",
                style = ReFocusTheme.Typography.BrandTag.copy(color = colors.inkMuted)
            )
        }

        // Editorial Pro Headline
        Text(
            text = if (isActivePremium) "MEMBERSHIP" else "PRO",
            style = ReFocusTheme.Typography.EditorialHeader.copy(
                fontSize = 40.sp,
                letterSpacing = (-1.5).sp,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Editorial Tagline
        Text(
            text = if (isActivePremium) "UNHACKABLE FOCUS BOUNDARIES ACTIVE." else "FOCUS, WITHOUT DISTRACTION.",
            style = ReFocusTheme.Typography.SectionTitle.copy(
                color = colors.inkSecondary,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                isActivePremium -> "Your on-device limits, anti-bypass locks, and tracking are actively guarding your attention."
                isTrial -> "Free trial access active. Lock in your monthly membership to keep continuous protection."
                else -> "Stop mindless algorithmic short-form video from fragmenting your daily productivity."
            },
            style = ReFocusTheme.Typography.Body.copy(
                color = colors.inkSecondary,
                fontSize = 13.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/**
 * Clean, Non-Sales Active Membership Status Horizon.
 */
@Composable
private fun ActiveMembershipModule(
    info: SubscriptionInfo,
    formattedEndDate: String?,
    isRefreshing: Boolean,
    onRefreshClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRenewClick: () -> Unit
) {
    val colors = ReFocusTheme.colors
    val containerShape = RoundedCornerShape(20.dp)

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
            .background(colors.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Status Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(colors.accentLime, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RE:FOCUS PRO",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            color = colors.inkPrimary,
                            fontSize = 12.sp,
                            letterSpacing = 1.2.sp
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accentLimeSubtle)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            color = if (colors.isDark) colors.accentLime else colors.accentLimeDark,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Renewal Notice Box (Border removed, soft surface contrast)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceElevated)
                    .padding(14.dp)
            ) {
                Text(
                    text = if (info.cancelAtPeriodEnd) {
                        "Access remains active until $formattedEndDate. Will not renew."
                    } else {
                        "Next scheduled renewal: ${formattedEndDate ?: "Upcoming billing cycle"}"
                    },
                    style = ReFocusTheme.Typography.Body.copy(
                        color = colors.inkSecondary,
                        fontSize = 12.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Unhackable focus boundaries, anti-bypass locks, and tracking are actively guarding your attention.",
                style = ReFocusTheme.Typography.Body.copy(
                    color = colors.inkSecondary,
                    fontSize = 12.sp
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Actions Row (Floating Pill Controls)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReFocusFloatingPillButton(
                    text = "Sync Status",
                    onClick = onRefreshClick,
                    enabled = !isRefreshing,
                    isLoading = isRefreshing,
                    modifier = Modifier.weight(1f)
                )

                if (info.cancelAtPeriodEnd) {
                    ReFocusFloatingPillButton(
                        text = "Renew Plan",
                        onClick = onRenewClick,
                        isSelected = true,
                        modifier = Modifier.weight(1f)
                    )
                } else if (!info.subscriptionId.isNullOrBlank()) {
                    ReFocusFloatingPillButton(
                        text = "Cancel Autopay",
                        onClick = onCancelClick,
                        isDestructive = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Upgrade Module: 4 Genuine Capabilities + ₹79/month Anchor + High-Impact CTA.
 */
@Composable
private fun SalesAndUpgradeModule(
    isTrial: Boolean,
    isExpired: Boolean,
    remainingDurationMs: Long,
    checkoutState: CheckoutState,
    onUpgradeClick: () -> Unit
) {
    val colors = ReFocusTheme.colors
    val containerShape = RoundedCornerShape(20.dp)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Trial / Expiry Context Notice
        if (isTrial) {
            val durationText = if (remainingDurationMs > 0L) {
                formatRemainingDurationText(remainingDurationMs)
            } else "Trial ending"

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentLimeSubtle)
                    .padding(14.dp)
            ) {
                Text(
                    text = "$durationText in your free trial. Lock in your monthly membership to prevent disruption.",
                    style = ReFocusTheme.Typography.Body.copy(
                        fontSize = 12.sp,
                        color = colors.inkPrimary
                    )
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        } else if (isExpired) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.destructiveSubtle)
                    .padding(14.dp)
            ) {
                Text(
                    text = "Subscription expired. Reel tracking and strict limits are locked. Renew to resume protection.",
                    style = ReFocusTheme.Typography.Body.copy(
                        fontSize = 12.sp,
                        color = colors.destructive
                    )
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // REAL CAPABILITIES UNIFIED CONTAINER (Border removed, soft surface contrast)
        Text(
            text = "MEMBERSHIP CAPABILITIES",
            style = ReFocusTheme.Typography.SectionTitle.copy(
                color = colors.inkMuted,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
            ),
            modifier = Modifier.padding(bottom = 10.dp)
        )

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
                .background(colors.surfaceContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GenuineBenefitRow(
                    index = "01",
                    title = "Automatic Feed Interruption",
                    detail = "Detects and halts short-form video reels across Instagram and YouTube Shorts."
                )

                HorizontalDivider(
                    thickness = ReFocusTheme.Dimens.HairlineWidth,
                    color = colors.lineHairline,
                    modifier = Modifier.padding(start = 38.dp)
                )

                GenuineBenefitRow(
                    index = "02",
                    title = "Strict Anti-Bypass Lock",
                    detail = "Locks daily limits until 12:00 AM midnight to eliminate impulsive overrides."
                )

                HorizontalDivider(
                    thickness = ReFocusTheme.Dimens.HairlineWidth,
                    color = colors.lineHairline,
                    modifier = Modifier.padding(start = 38.dp)
                )

                GenuineBenefitRow(
                    index = "03",
                    title = "Session & Streak Tracking",
                    detail = "Continuous tracking of daily reels, session durations, and reclaimed focus time."
                )

                HorizontalDivider(
                    thickness = ReFocusTheme.Dimens.HairlineWidth,
                    color = colors.lineHairline,
                    modifier = Modifier.padding(start = 38.dp)
                )

                GenuineBenefitRow(
                    index = "04",
                    title = "Cloud Continuity",
                    detail = "Safely syncs your daily limits, streaks, and records across reinstalls."
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ANCHOR PRICING CONTAINER: ₹79 / month (Border removed, soft surface contrast)
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
                .background(colors.surfaceContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "MONTHLY MEMBERSHIP",
                            style = ReFocusTheme.Typography.SectionTitle.copy(
                                color = colors.inkMuted,
                                fontSize = 11.sp,
                                letterSpacing = 1.2.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "₹79",
                                style = ReFocusTheme.Typography.BigMetric.copy(
                                    fontSize = 44.sp,
                                    color = colors.inkPrimary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "/ month",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = colors.inkSecondary
                                ),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }

                    Text(
                        text = "Cancel anytime",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            fontSize = 11.sp,
                            color = colors.inkSecondary
                        ),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // HIGH-IMPACT RE:FOCUS FLOATING PILL CTA
                val isProcessing = checkoutState is CheckoutState.CreatingOrder ||
                        checkoutState is CheckoutState.CheckoutInProgress ||
                        checkoutState is CheckoutState.Verifying

                val ctaText = when (checkoutState) {
                    is CheckoutState.Success -> "MEMBERSHIP ACTIVATED"
                    is CheckoutState.MandatePending -> "MANDATE AUTHORIZED"
                    is CheckoutState.Error -> "RETRY CHECKOUT"
                    else -> if (isExpired) "RENEW MEMBERSHIP" else "START MEMBERSHIP"
                }

                val ctaLoadingText = when (checkoutState) {
                    is CheckoutState.CreatingOrder -> "CREATING ORDER..."
                    is CheckoutState.CheckoutInProgress -> "AWAITING RAZORPAY..."
                    is CheckoutState.Verifying -> "VERIFYING ENTITLEMENT..."
                    else -> "PROCESSING..."
                }

                ReFocusFloatingPillCta(
                    text = ctaText,
                    priceTag = if (checkoutState is CheckoutState.Idle || checkoutState is CheckoutState.Error) "₹79 / MO" else null,
                    onClick = onUpgradeClick,
                    isLoading = isProcessing,
                    loadingText = ctaLoadingText,
                    enabled = !isProcessing,
                    height = 56.dp
                )
            }
        }
    }
}

@Composable
private fun GenuineBenefitRow(
    index: String,
    title: String,
    detail: String
) {
    val colors = ReFocusTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Monospace Index Badge (Border removed, soft surface contrast)
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index,
                style = ReFocusTheme.Typography.SectionTitle.copy(
                    color = colors.accentLime,
                    fontSize = 10.sp
                )
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = colors.inkPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                style = ReFocusTheme.Typography.Body.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.inkSecondary
                )
            )
        }
    }
}

@Composable
private fun TechnicalAlertBlock(
    label: String,
    message: String,
    isError: Boolean
) {
    val colors = ReFocusTheme.colors
    val borderColor = if (isError) colors.destructive else colors.warning
    val bg = if (isError) colors.destructiveSubtle else colors.warningSubtle

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(BorderStroke(1.dp, borderColor.copy(alpha = 0.35f)), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column {
            Text(
                text = label,
                style = ReFocusTheme.Typography.SectionTitle.copy(
                    color = borderColor,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = ReFocusTheme.Typography.Body.copy(
                    fontSize = 12.sp,
                    color = colors.inkSecondary
                )
            )
        }
    }
}

@Composable
private fun CheckoutStatusModule(checkoutState: CheckoutState, onReset: () -> Unit) {
    val colors = ReFocusTheme.colors

    AnimatedVisibility(visible = checkoutState is CheckoutState.Error) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.destructiveSubtle)
                .border(BorderStroke(1.dp, colors.destructive), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PAYMENT INCOMPLETE",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            color = colors.destructive,
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "No charges were settled. You can re-attempt authorization anytime.",
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 12.sp,
                            color = colors.inkSecondary
                        )
                    )
                }
                TextButton(onClick = onReset) {
                    Text(
                        text = "DISMISS",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            fontSize = 10.sp,
                            color = colors.destructive
                        )
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = checkoutState is CheckoutState.MandatePending) {
        val message = (checkoutState as? CheckoutState.MandatePending)?.message
            ?: "Your autopay mandate is confirmed. Full access will activate once initial payment settles."
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accentLimeSubtle)
                .border(BorderStroke(1.dp, colors.accentLime.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MANDATE AUTHORIZED",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            color = colors.accentLimeDark,
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = message,
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 12.sp,
                            color = colors.inkPrimary
                        )
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = checkoutState is CheckoutState.Success) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accentLimeSubtle)
                .border(BorderStroke(1.dp, colors.accentLime.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ENTITLEMENT VERIFIED",
                        style = ReFocusTheme.Typography.SectionTitle.copy(
                            color = colors.accentLimeDark,
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Monthly focus subscription active. Access is now unhackable.",
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 12.sp,
                            color = colors.inkPrimary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun CancelAutopayModal(
    endDateText: String?,
    isCancelling: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = ReFocusTheme.colors
    val modalShape = RoundedCornerShape(24.dp)

    Dialog(
        onDismissRequest = { if (!isCancelling) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isCancelling,
            dismissOnClickOutside = !isCancelling,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = modalShape,
            color = colors.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            border = specularBorder(strokeWidth = 1.dp, shape = modalShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Cancel Autopay?",
                    style = ReFocusTheme.Typography.Headline.copy(fontSize = 20.sp),
                    color = colors.inkPrimary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceElevated)
                        .border(BorderStroke(1.dp, colors.lineHairline), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = if (!endDateText.isNullOrBlank()) {
                            "Recurring charges will halt immediately. You retain full calibrated focus access until $endDateText."
                        } else {
                            "Recurring charges will halt immediately. Your access remains active until the end of this billing cycle."
                        },
                        style = ReFocusTheme.Typography.Body.copy(
                            fontSize = 12.sp,
                            color = colors.inkSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isCancelling,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.lineHairline)
                    ) {
                        Text(
                            text = "Keep Plan",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.inkSecondary
                        )
                    }

                    Button(
                        onClick = onConfirm,
                        enabled = !isCancelling,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.destructive,
                            contentColor = colors.textInverse
                        )
                    ) {
                        if (isCancelling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.textInverse
                            )
                        } else {
                            Text(
                                text = "Cancel Autopay",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumErrorBanner(
    message: String,
    onRetry: () -> Unit,
    isRefreshing: Boolean
) {
    val colors = ReFocusTheme.colors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.destructiveSubtle)
            .border(BorderStroke(1.dp, colors.destructive), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NETWORK RECONNECT REQUIRED",
                    style = ReFocusTheme.Typography.SectionTitle.copy(
                        color = colors.destructive,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Could not synchronize subscription status with server.",
                    style = ReFocusTheme.Typography.Body.copy(
                        fontSize = 12.sp,
                        color = colors.inkSecondary
                    )
                )
            }
            IconButton(onClick = onRetry, enabled = !isRefreshing) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.destructive
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = colors.destructive
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumLoadingState() {
    val colors = ReFocusTheme.colors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
                color = colors.accentLimeDark
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "CALIBRATING MEMBERSHIP ENTITLEMENT...",
                style = ReFocusTheme.Typography.SectionTitle.copy(
                    color = colors.inkMuted,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp
                )
            )
        }
    }
}
