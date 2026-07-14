package com.reeltrak.app.features.dashboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayGoalCard(
    currentSavedGoalProvider: () -> Int,
    isLocked: Boolean,
    onSaveGoal: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSavedGoal = currentSavedGoalProvider()
    // If the saved goal is -1, we treat it as "No goal set", but default the selector to 500
    val defaultDisplay = if (currentSavedGoal >= 0) currentSavedGoal.toString() else "500"
    var textValue by remember(currentSavedGoal) { mutableStateOf(defaultDisplay) }
    
    val subtitleText = when {
        isLocked -> "Already set for today"
        currentSavedGoal == -1 -> "Limit not set for today"
        currentSavedGoal == 0 -> "No limit today"
        else -> "Daily Limit"
    }
    
    val subtitleColor = if (currentSavedGoal == -1) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val focusManager = LocalFocusManager.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side: Title and Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Today's Limit",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right side: TextField and Save Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newValue ->
                        // Only allow numeric input
                        if (!isLocked && (newValue.isEmpty() || newValue.all { it.isDigit() })) {
                            textValue = newValue
                        }
                    },
                    enabled = !isLocked,
                    modifier = Modifier.width(90.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            val goal = textValue.toIntOrNull() ?: 500
                            val validatedGoal = goal.coerceIn(0, 10000)
                            textValue = validatedGoal.toString()
                            onSaveGoal(validatedGoal)
                        }
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val goal = textValue.toIntOrNull() ?: 500
                        val validatedGoal = goal.coerceIn(0, 10000)
                        textValue = validatedGoal.toString()
                        onSaveGoal(validatedGoal)
                    },
                    enabled = !isLocked,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.height(48.dp) // Match roughly the height of the text field
                ) {
                    Text("Save")
                }
            }
        }
    }
}
