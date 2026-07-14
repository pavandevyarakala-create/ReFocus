package com.reeltrak.app.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.reeltrak.app.features.settings.viewmodel.SettingsViewModel

import androidx.navigation.NavHostController

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val strictModeEnabled by viewModel.isStrictModeEnabled.collectAsStateWithLifecycle()
    val dailyIntent by viewModel.dailyIntent.collectAsStateWithLifecycle()
    
    var trackInstagram by remember { mutableStateOf(true) }
    var trackYouTube by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Core Preferences", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Strict Mode")
                    Switch(checked = strictModeEnabled, onCheckedChange = { viewModel.setStrictMode(it) })
                }
                Text("When enabled, the app block cannot be bypassed until tomorrow.", fontSize = 12.sp, color = Color.Gray)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Daily Reel Limit")
                    Text("$dailyIntent", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Target Apps", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trackInstagram, onCheckedChange = { trackInstagram = it })
                    Text("Instagram Reels")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trackYouTube, onCheckedChange = { trackYouTube = it })
                    Text("YouTube Shorts")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { navController.navigate(com.reeltrak.app.core.navigation.Routes.DEBUG_OVERLAY) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Open Overlay Debug Harness")
        }
    }
}
