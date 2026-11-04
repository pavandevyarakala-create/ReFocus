package com.reeltrak.app.features.dashboard.ui.components

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
fun TodayGoalCard(
    currentSavedGoalProvider: () -> Int,
    isLocked: Boolean,
    isEntitled: Boolean = true,
    onSaveGoal: (Int) -> Unit,
    onDailyLimitLockedClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentSavedGoal = currentSavedGoalProvider()
    val defaultDisplay = if (currentSavedGoal >= 0) currentSavedGoal.toString() else "500"
    var textValue by remember(currentSavedGoal) { mutableStateOf(defaultDisplay) }
    var isInputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val parsedGoal = textValue.toIntOrNull()
    val isValid = parsedGoal != null && parsedGoal in 0..10000
    val isInvalid = textValue.isNotEmpty() && !isValid

    val subtitleText = when {
        !isEntitled -> "Premium feature"
        isLocked -> "Locked until tomorrow"
        isInvalid -> "Enter 0 to 10,000 reels"
        currentSavedGoal == -1 -> "Limit not set for today"
        currentSavedGoal == 0 -> "No limit today"
        else -> "Active daily goal"
    }

    val subtitleColor = when {
        !isEntitled -> if (ReFocusColors.isDark) ReFocusColors.accentHighlight else ReFocusColors.accentDark
        isInvalid -> ReFocusColors.errorText
        currentSavedGoal == -1 -> ReFocusColors.warningText
        isLocked -> ReFocusColors.textMuted
        else -> ReFocusColors.textSecondary
    }

    val cardShape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = ReFocusColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ReFocusColors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side: Title and Subtitle
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                Text(
                    text = "Today's Limit",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
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

            // Right side: Numeric Input Box and Persistent Save Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Clearly Defined Numeric Input Box
                val inputShape = RoundedCornerShape(10.dp)
                val canEdit = isEntitled && !isLocked
                val inputBgColor = when {
                    !isEntitled -> ReFocusColors.surfaceRecessed
                    isLocked -> ReFocusColors.surfaceRecessed
                    isInputFocused -> if (ReFocusColors.isDark) ReFocusColors.surfaceStrong else ReFocusColors.surface
                    else -> if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surface
                }
                val inputBorder = when {
                    !isEntitled -> BorderStroke(1.dp, ReFocusColors.borderSubtle)
                    isLocked -> BorderStroke(1.dp, ReFocusColors.borderSubtle)
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
                            color = if (!isEntitled || isLocked) {
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

                // 2. Persistent Save Button / Confirmed Limit Saved Button
                val saveButtonShape = RoundedCornerShape(10.dp)
                val saveInteraction = remember { MutableInteractionSource() }

                when {
                    !isEntitled -> {
                        val lockBg = if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surfaceStrong
                        val lockBorder = BorderStroke(1.dp, ReFocusColors.border)

                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .clip(saveButtonShape)
                                .background(lockBg)
                                .border(lockBorder, saveButtonShape)
                                .clickable { onDailyLimitLockedClick() }
                                .padding(horizontal = 11.dp),
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
                    isLocked -> {
                        val savedBg = if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surfaceStrong
                        val savedBorder = BorderStroke(1.dp, ReFocusColors.border)

                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .clip(saveButtonShape)
                                .background(savedBg)
                                .border(savedBorder, saveButtonShape)
                                .padding(horizontal = 11.dp),
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
                                    text = "Limit Saved",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = if (ReFocusColors.isDark) ReFocusColors.textPrimary else ReFocusColors.textPrimary
                                )
                            }
                        }
                    }
                    isValid && textValue.isNotEmpty() -> {
                        Box(
                            modifier = Modifier
                                .height(40.dp)
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
                        val inactiveBg = if (ReFocusColors.isDark) ReFocusColors.surfaceFloating else ReFocusColors.surfaceStrong
                        val inactiveBorder = BorderStroke(
                            1.dp,
                            if (isInvalid) ReFocusColors.errorBorder else ReFocusColors.border
                        )

                        Box(
                            modifier = Modifier
                                .height(40.dp)
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
