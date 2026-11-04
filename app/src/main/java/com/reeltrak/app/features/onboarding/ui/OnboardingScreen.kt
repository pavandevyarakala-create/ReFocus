package com.reeltrak.app.features.onboarding.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.reeltrak.app.features.onboarding.viewmodel.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onSetupComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    var intentValue by remember { mutableStateOf("0") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Set Your Daily Intent", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("How many reels maximum?", color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(intentValue, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "OK")
        )
        
        keys.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    Button(
                        onClick = {
                            when (key) {
                                "C" -> intentValue = "0"
                                "OK" -> {
                                    viewModel.saveDailyIntent(intentValue.toIntOrNull() ?: 0)
                                    onSetupComplete()
                                }
                                else -> {
                                    if (intentValue == "0") intentValue = key
                                    else if (intentValue.length < 3) intentValue += key
                                }
                            }
                        },
                        modifier = Modifier.size(80.dp).padding(4.dp)
                    ) {
                        Text(key, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}
