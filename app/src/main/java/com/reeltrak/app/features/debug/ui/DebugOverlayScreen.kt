package com.reeltrak.app.features.debug.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DebugOverlayScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    
    // State variables
    var overlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    
    // UI state
    var isAttached by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(0) }
    
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "ACTION_DEBUG_STATE_REPORT") {
                    isAttached = intent.getBooleanExtra("isAttached", false)
                    isVisible = intent.getBooleanExtra("isVisible", false)
                    count = intent.getIntExtra("count", 0)
                }
            }
        }
        val filter = IntentFilter("ACTION_DEBUG_STATE_REPORT")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    
    // Polling for permission and state
    LaunchedEffect(Unit) {
        while (true) {
            overlayPermission = Settings.canDrawOverlays(context)
            context.sendBroadcast(Intent("ACTION_DEBUG_REQUEST_STATE"))
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Production Overlay Debug Dashboard", style = MaterialTheme.typography.headlineMedium)
        
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Session & Overlay Status", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                StatusRow("Overlay Permission", overlayPermission)
                StatusRow("WindowManager Attached", isAttached)
                StatusRow("Overlay Visible (Active Session)", isVisible)
                Text("Current Count: $count")
            }
        }
        
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Mock Architecture API", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(onClick = { context.sendBroadcast(Intent("ACTION_DEBUG_SHOW_OVERLAY")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mock ENTERING_REELS / ACTIVE")
                }
                Button(onClick = { context.sendBroadcast(Intent("ACTION_DEBUG_HIDE_OVERLAY")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mock EXITING / IDLE")
                }
                Button(onClick = { context.sendBroadcast(Intent("ACTION_DEBUG_INCREMENT")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mock Successful Detection & Increment")
                }
                Button(onClick = { context.sendBroadcast(Intent("ACTION_DEBUG_RESET")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset Overlay Count")
                }
                Button(onClick = { context.sendBroadcast(Intent("ACTION_DEBUG_RECREATE")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Force Recreate Floating Manager")
                }
                Button(onClick = { context.sendBroadcast(Intent("ACTION_DEBUG_DESTROY")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Force Destroy Floating Manager")
                }
            }
        }
        
        Button(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth()) {
            Text("Go Back")
        }
    }
}

@Composable
fun StatusRow(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(if (isOk) Color.Green else Color.Red)
        )
    }
}
