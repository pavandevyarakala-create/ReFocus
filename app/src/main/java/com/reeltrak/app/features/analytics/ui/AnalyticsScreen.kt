package com.reeltrak.app.features.analytics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnalyticsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text("Analytics", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Weekly Trend", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                // Placeholder Bar Chart
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    listOf(40, 80, 20, 100, 60, 30, 10).forEach { height ->
                        Box(
                            modifier = Modifier
                                .width(30.dp)
                                .height(height.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Total Time Saved", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("14 Hours 30 Minutes", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
