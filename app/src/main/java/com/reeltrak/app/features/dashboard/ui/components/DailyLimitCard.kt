package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.core.ui.theme.ReFocusColors
import com.reeltrak.app.core.ui.theme.tactilePress

@Composable
fun DailyLimitCard(
    isLimitEnabled: Boolean,
    currentLimit: Int,
    isStrictModeActive: Boolean,
    isEntitled: Boolean = true,
    isLoading: Boolean = false,
    onToggleLimit: (Boolean) -> Unit = {},
    onSaveLimit: (Int) -> Unit = {},
    onLockedClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(16.dp)
    val displayValue = if (currentLimit > 0) currentLimit.toString() else "200"
    var textValue by remember(currentLimit, isLimitEnabled) { mutableStateOf(displayValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val parsedGoal = textValue.toIntOrNull()
    val isValid = parsedGoal != null && parsedGoal in 1..10000
    val isUnchanged = parsedGoal == currentLimit
    val isInvalid = textValue.isNotEmpty() && !isValid

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = ReFocusColors.shadowAmbient,
                spotColor = ReFocusColors.shadowSpot
            )
            .then(
                if (!isEntitled || isStrictModeActive) {
                    Modifier.clickable(enabled = !isLoading) { onLockedClick() }
                } else {
                    Modifier
                }
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ReFocusColors.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            // Header Row: Title, Lock Badges, Subtitle, and Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Daily Limit",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = ReFocusColors.textPrimary
                        )

                        if (!isEntitled) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ReFocusColors.accent.copy(alpha = 0.12f))
                                    .border(
                                        BorderStroke(1.dp, ReFocusColors.accent.copy(alpha = 0.25f)),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = "Premium feature",
                                        modifier = Modifier.size(10.dp),
                                        tint = ReFocusColors.accent
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "PREMIUM",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = ReFocusColors.accent
                                    )
                                }
                            }
                        } else if (isStrictModeActive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ReFocusColors.surfaceStrong)
                                    .border(BorderStroke(1.dp, ReFocusColors.border), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = "Locked by Strict Mode",
                                        modifier = Modifier.size(10.dp),
                                        tint = ReFocusColors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "LOCKED",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = ReFocusColors.textSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val subtitle = when {
                        !isEntitled -> "Set scrolling boundaries • Premium feature"
                        isLoading -> "Loading..."
                        isStrictModeActive -> "Locked by Strict Mode • Resets tomorrow"
                        !isLimitEnabled -> "Off • No limit enforced"
                        isInvalid -> "Enter 1 to 10,000 reels"
                        else -> "Active • $currentLimit reels daily target"
                    }

                    val subtitleColor = when {
                        !isEntitled -> ReFocusColors.textMuted
                        isStrictModeActive -> ReFocusColors.textMuted
                        !isLimitEnabled -> ReFocusColors.textMuted
                        isInvalid -> ReFocusColors.errorText
                        else -> if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
                    }

                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        ),
                        color = subtitleColor
                    )
                }

                // Right side control: Lock indicator for free users or Toggle Switch
                if (!isEntitled) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ReFocusColors.surfaceStrong)
                            .border(BorderStroke(1.dp, ReFocusColors.border), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Daily Limit locked. Tap to view details.",
                            modifier = Modifier.size(16.dp),
                            tint = ReFocusColors.textMuted
                        )
                    }
                } else if (isStrictModeActive) {
                    // Locked in ON position while Strict Mode is active
                    Switch(
                        checked = true,
                        onCheckedChange = {},
                        enabled = false,
                        colors = SwitchDefaults.colors(
                            disabledCheckedThumbColor = ReFocusColors.textInverse.copy(alpha = 0.85f),
                            disabledCheckedTrackColor = ReFocusColors.accent.copy(alpha = 0.55f),
                            disabledCheckedBorderColor = ReFocusColors.border
                        )
                    )
                } else {
                    // Interactive Switch when Strict Mode is OFF
                    Switch(
                        checked = isLimitEnabled,
                        onCheckedChange = { isChecked ->
                            onToggleLimit(isChecked)
                        },
                        enabled = !isLoading,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ReFocusColors.textInverse,
                            checkedTrackColor = ReFocusColors.accent,
                            checkedBorderColor = ReFocusColors.accent,
                            uncheckedThumbColor = ReFocusColors.textMuted,
                            uncheckedTrackColor = ReFocusColors.surfaceSoft,
                            uncheckedBorderColor = ReFocusColors.border
                        )
                    )
                }
            }

            // Expanded Section: Revealed when Daily Limit toggle is ON
            AnimatedVisibility(
                visible = isLimitEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    HorizontalDivider(
                        color = ReFocusColors.borderSubtle,
                        thickness = 1.dp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Daily Target:",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.5.sp
                            ),
                            color = ReFocusColors.textSecondary
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 1. Numeric Input Box
                            val inputShape = RoundedCornerShape(10.dp)
                            val canEdit = isEntitled && !isStrictModeActive
                            val inputBgColor = when {
                                !canEdit -> ReFocusColors.surfaceRecessed
                                isInputFocused -> if (ReFocusColors.isDark) ReFocusColors.surfaceStrong else ReFocusColors.surface
                                else -> if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surface
                            }
                            val inputBorder = when {
                                !canEdit -> BorderStroke(1.dp, ReFocusColors.borderSubtle)
                                isInvalid -> BorderStroke(1.5.dp, ReFocusColors.error)
                                isInputFocused -> BorderStroke(
                                    1.5.dp,
                                    if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
                                )
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
                                        if (!canEdit) Modifier.clickable { onLockedClick() } else Modifier
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
                                        color = if (!canEdit) {
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
                                            if (isValid && canEdit && !isUnchanged) {
                                                focusManager.clearFocus()
                                                val goal = parsedGoal ?: 200
                                                val validatedGoal = goal.coerceIn(1, 10000)
                                                textValue = validatedGoal.toString()
                                                onSaveLimit(validatedGoal)
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

                            // 2. Tactile Save Button / Saved State / Locked State
                            val saveButtonShape = RoundedCornerShape(10.dp)
                            val saveInteraction = remember { MutableInteractionSource() }

                            when {
                                isStrictModeActive -> {
                                    // Locked by Strict Mode
                                    val lockedBg = if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surfaceStrong
                                    val lockedBorder = BorderStroke(1.dp, ReFocusColors.border)

                                    Box(
                                        modifier = Modifier
                                            .height(40.dp)
                                            .wrapContentWidth()
                                            .clip(saveButtonShape)
                                            .background(lockedBg)
                                            .border(lockedBorder, saveButtonShape)
                                            .clickable { onLockedClick() }
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = ReFocusColors.textSecondary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Locked",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.5.sp,
                                                    letterSpacing = 0.2.sp
                                                ),
                                                color = ReFocusColors.textSecondary
                                            )
                                        }
                                    }
                                }
                                isUnchanged -> {
                                    // Saved state (current value matches saved limit)
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
                                isValid && textValue.isNotEmpty() -> {
                                    // Value changed: Active Save Button ready to press
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
                                                val goal = parsedGoal ?: 200
                                                val validatedGoal = goal.coerceIn(1, 10000)
                                                textValue = validatedGoal.toString()
                                                onSaveLimit(validatedGoal)
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
                                    // Invalid or empty input
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
            }
        }
    }
}
