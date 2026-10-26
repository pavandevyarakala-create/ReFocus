package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock

import com.reeltrak.app.features.dashboard.viewmodel.DashboardViewModel.StreakStats

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.tactilePress

@Composable
fun HeroProgressCard(
    dailyIntentProvider: () -> Int,
    currentReelsProvider: () -> Int,
    streakStatsProvider: () -> StreakStats = { StreakStats() },
    isLimitLocked: Boolean = false,
    isSaving: Boolean = false,
    isEntitled: Boolean = true,
    onSaveGoal: (Int) -> Unit = {},
    onDailyLimitLockedClick: () -> Unit = {},
    onStreakClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dailyIntent = dailyIntentProvider()
    val currentReels = currentReelsProvider()
    val streakStats = streakStatsProvider()
    @Suppress("UNUSED_VARIABLE")
    val progress = if (dailyIntent > 0) currentReels.toFloat() / dailyIntent else 0f
    
    val isNotSet = dailyIntent == -1
    val isNoLimit = dailyIntent == 0

    // Today's Limit Toggle state
    val defaultDisplay = if (dailyIntent >= 0) dailyIntent.toString() else "500"
    var textValue by remember(dailyIntent) { mutableStateOf(defaultDisplay) }
    var isInputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val parsedGoal = textValue.toIntOrNull()
    val isValid = parsedGoal != null && parsedGoal in 0..10000
    val isInvalid = textValue.isNotEmpty() && !isValid

    val subtitleText = when {
        !isEntitled -> "Premium feature"
        isLimitLocked -> "Locked until tomorrow"
        isInvalid -> "Enter 0 to 10,000 reels"
        isNotSet -> "Limit not set for today"
        else -> "Active daily goal"
    }

    val subtitleColor = when {
        !isEntitled -> if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
        isInvalid -> ReFocusColors.errorText
        isNotSet -> ReFocusColors.warningText
        isLimitLocked -> ReFocusColors.textMuted
        else -> ReFocusColors.textSecondary
    }

    val cardShape = RoundedCornerShape(24.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Upper Section: Clean, Prominent Progress Header with balanced Sora typography
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Today's Progress",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = ReFocusColors.textSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "$currentReels",
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 38.sp,
                            lineHeight = 42.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = ReFocusColors.textPrimary,
                        modifier = Modifier.alignByBaseline()
                    )
                    if (!isNotSet && !isNoLimit) {
                        Text(
                            text = "/ $dailyIntent",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = ReFocusColors.textMuted,
                            modifier = Modifier.alignByBaseline()
                        )
                    }
                }
                Text(
                    text = if (currentReels == 1) "Reel Watched" else "Reels Watched",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = ReFocusColors.textSecondary
                )
            }

            // Integrated Daily Limit Recessed Control & Raised Tactile Action
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = ReFocusColors.surfaceSoft,
                border = BorderStroke(1.dp, ReFocusColors.borderSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    ) {
                        Text(
                            text = "Daily Limit",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = ReFocusColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = subtitleColor
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Clearly Defined Numeric Input Box (High affordance, distinct in Light & Dark)
                        val inputShape = RoundedCornerShape(10.dp)
                        val canEdit = isEntitled && !isLimitLocked
                        val inputBgColor = when {
                            !isEntitled -> ReFocusColors.surfaceRecessed
                            isLimitLocked -> ReFocusColors.surfaceRecessed
                            isInputFocused -> if (ReFocusColors.isDark) ReFocusColors.surfaceStrong else ReFocusColors.surface
                            else -> if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surface
                        }
                        val inputBorder = when {
                            !isEntitled -> BorderStroke(1.dp, ReFocusColors.borderSubtle)
                            isLimitLocked -> BorderStroke(1.dp, ReFocusColors.borderSubtle)
                            isInvalid -> BorderStroke(1.5.dp, ReFocusColors.error)
                            isInputFocused -> BorderStroke(1.5.dp, if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark)
                            else -> BorderStroke(1.dp, ReFocusColors.border)
                        }

                        Box(
                            modifier = Modifier
                                .width(76.dp)
                                .height(40.dp)
                                .then(
                                    if (canEdit) {
                                        Modifier.shadow(
                                            elevation = if (isInputFocused) 2.dp else 1.dp,
                                            shape = inputShape,
                                            ambientColor = if (isInputFocused) ReFocusColors.glowLime else ReFocusColors.shadowAmbient,
                                            spotColor = if (isInputFocused) ReFocusColors.glowLime else ReFocusColors.shadowSpot
                                        )
                                    } else Modifier
                                )
                                .clip(inputShape)
                                .background(inputBgColor)
                                .border(inputBorder, inputShape)
                                .then(
                                    if (!isEntitled) {
                                        Modifier.clickable { onDailyLimitLockedClick() }
                                    } else Modifier
                                )
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicTextField(
                                value = textValue,
                                onValueChange = { newValue: String ->
                                    if (canEdit && (newValue.isEmpty() || newValue.all { char -> char.isDigit() })) {
                                        if (newValue.length <= 5) {
                                            textValue = newValue
                                        }
                                    }
                                },
                                enabled = canEdit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isInputFocused = it.isFocused },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = if (!isEntitled || isLimitLocked) {
                                        ReFocusColors.textSecondary
                                    } else if (isInvalid) {
                                        ReFocusColors.errorText
                                    } else {
                                        ReFocusColors.textPrimary
                                    }
                                ),
                                cursorBrush = SolidColor(if (isInvalid) ReFocusColors.error else ReFocusColors.accent),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (isValid && canEdit) {
                                            focusManager.clearFocus()
                                            val goal = textValue.toIntOrNull() ?: 500
                                            val validatedGoal = goal.coerceIn(0, 10000)
                                            textValue = validatedGoal.toString()
                                            onSaveGoal(validatedGoal)
                                        }
                                    }
                                ),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (textValue.isEmpty()) {
                                            Text(
                                                text = "Limit",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    textAlign = TextAlign.Center,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 13.sp
                                                ),
                                                color = ReFocusColors.textMuted
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }

                        // 2. Physically Raised Tactile Save Button / Confirmed Saved State
                        val saveButtonShape = RoundedCornerShape(10.dp)
                        val saveInteraction = remember { MutableInteractionSource() }

                        when {
                            !isEntitled -> {
                                val lockBg = if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surfaceStrong
                                val lockBorder = BorderStroke(1.dp, ReFocusColors.border)

                                Box(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .wrapContentWidth()
                                        .clip(saveButtonShape)
                                        .background(lockBg)
                                        .border(lockBorder, saveButtonShape)
                                        .clickable { onDailyLimitLockedClick() }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Lock,
                                            contentDescription = "Unlock Daily Limit",
                                            modifier = Modifier.size(13.dp),
                                            tint = if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Unlock",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.5.sp,
                                                letterSpacing = 0.2.sp
                                            ),
                                            color = ReFocusColors.textPrimary
                                        )
                                    }
                                }
                            }
                            isLimitLocked -> {
                                // Locked / Confirmed state for today: Clear button shape, visible background, check indicator
                                val savedBg = if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surfaceStrong
                                val savedBorder = BorderStroke(1.dp, ReFocusColors.border)

                                Box(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .wrapContentWidth()
                                        .clip(saveButtonShape)
                                        .background(savedBg)
                                        .border(savedBorder, saveButtonShape)
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Saved",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.5.sp,
                                                letterSpacing = 0.2.sp
                                            ),
                                            color = ReFocusColors.textPrimary
                                        )
                                    }
                                }
                            }
                            isSaving -> {
                                // Saving in progress
                                Box(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .wrapContentWidth()
                                        .clip(saveButtonShape)
                                        .background(ReFocusColors.accent)
                                        .padding(horizontal = 22.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = ReFocusColors.textInverse
                                    )
                                }
                            }
                            isValid && textValue.isNotEmpty() -> {
                                // Active Ready to Save: Signature Re:Focus Lime Accent, Tactile Press, Elevated
                                Box(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .wrapContentWidth()
                                        .shadow(
                                            elevation = 2.dp,
                                            shape = saveButtonShape,
                                            ambientColor = if (ReFocusColors.isDark) ReFocusColors.shadowAmbient else ReFocusColors.shadowAccent,
                                            spotColor = if (ReFocusColors.isDark) ReFocusColors.shadowSpot else ReFocusColors.shadowAccent
                                        )
                                        .tactilePress(interactionSource = saveInteraction)
                                        .clip(saveButtonShape)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(ReFocusColors.accentHighlight, ReFocusColors.accentPressed)
                                            )
                                        )
                                        .clickable(
                                            interactionSource = saveInteraction,
                                            indication = null,
                                            enabled = true
                                        ) {
                                            focusManager.clearFocus()
                                            val goal = textValue.toIntOrNull() ?: 500
                                            val validatedGoal = goal.coerceIn(0, 10000)
                                            textValue = validatedGoal.toString()
                                            onSaveGoal(validatedGoal)
                                        }
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Save Limit",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            letterSpacing = 0.2.sp
                                        ),
                                        color = ReFocusColors.textInverse
                                    )
                                }
                            }
                            else -> {
                                // Empty or Invalid input: Structurally visible button, inactive/disabled state
                                val inactiveBg = if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surfaceStrong
                                val inactiveBorder = BorderStroke(
                                    1.dp,
                                    if (isInvalid) ReFocusColors.errorBorder else ReFocusColors.border
                                )

                                Box(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .wrapContentWidth()
                                        .clip(saveButtonShape)
                                        .background(inactiveBg)
                                        .border(inactiveBorder, saveButtonShape)
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Save Limit",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp,
                                            letterSpacing = 0.2.sp
                                        ),
                                        color = if (isInvalid) ReFocusColors.errorText else ReFocusColors.textDisabled
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Lower Section: Current Streak (Distinct lower section, aligned with card content, no nested card)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onStreakClick)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Current Streak",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        ),
                        color = ReFocusColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${streakStats.currentStreak}",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 24.sp
                            ),
                            color = ReFocusColors.textPrimary,
                            modifier = Modifier.alignByBaseline()
                        )
                        Text(
                            text = if (streakStats.currentStreak == 1) "Day" else "Days",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = ReFocusColors.textSecondary,
                            modifier = Modifier.alignByBaseline()
                        )
                    }
                }

                val earnedMilestone = when {
                    streakStats.disciplineLevel == "Legend" || streakStats.currentStreak >= 365 || streakStats.badge365UnlockTime > 0L -> 365
                    streakStats.disciplineLevel == "Master" || streakStats.currentStreak >= 100 || streakStats.badge100UnlockTime > 0L -> 100
                    streakStats.disciplineLevel == "Disciplined" || streakStats.currentStreak >= 50 || streakStats.badge50UnlockTime > 0L -> 50
                    streakStats.disciplineLevel == "Consistent" || streakStats.currentStreak >= 30 || streakStats.badge30UnlockTime > 0L -> 30
                    streakStats.disciplineLevel == "Focused" || streakStats.currentStreak >= 7 || streakStats.badge7UnlockTime > 0L -> 7
                    else -> null
                }

                val displayMilestone = earnedMilestone ?: 7
                val isMilestoneUnlocked = earnedMilestone != null

                AchievementBadge(
                    milestone = displayMilestone,
                    isUnlocked = isMilestoneUnlocked,
                    size = 48.dp
                )
            }
        }
    }
}
