package com.bitchord.island

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0F0E13)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("HyperIsland Lyrics", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Attiva i 2 permessi qui sotto, poi avvia un brano su BitChord.", color = Color.White.copy(0.7f), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                PermissionButton("1. Accesso alle Notifiche (legge BitChord)") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermissionButton("2. Abilita Live Updates / Super Island") {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, packageName) })
                }
            }
        }
    }
}

@Composable
private fun PermissionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2440)),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(label, color = Color.White, fontSize = 14.sp) }
}
