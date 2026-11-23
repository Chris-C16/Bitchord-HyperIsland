package com.bitchord.island

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SetupScreen(
                    onNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onLiveUpdates = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                        startActivity(intent)
                    },
                    onAppInfo = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    },
                    onOpenPlayer = {
                        startActivity(Intent(this, LyricsPlayerActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    onNotificationAccess: () -> Unit,
    onLiveUpdates: () -> Unit,
    onAppInfo: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0D14))
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "HyperIsland Lyrics",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Attiva i permessi qui sotto, poi avvia un brano su BitChord.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))

        val btnColors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282342))
        val shape = RoundedCornerShape(14.dp)

        Button(onClick = onNotificationAccess, modifier = Modifier.fillMaxWidth().height(54.dp), colors = btnColors, shape = shape) {
            Text("1. Accesso alle Notifiche (legge BitChord)", color = Color.White)
        }
        Button(onClick = onLiveUpdates, modifier = Modifier.fillMaxWidth().height(54.dp), colors = btnColors, shape = shape) {
            Text("2. Notifiche e Live Updates (Android 16)", color = Color.White)
        }
        Button(onClick = onAppInfo, modifier = Modifier.fillMaxWidth().height(54.dp), colors = btnColors, shape = shape) {
            Text("3. Info App (Impostazioni con restrizioni)", color = Color.White)
        }
        OutlinedButton(onClick = onOpenPlayer, modifier = Modifier.fillMaxWidth().height(54.dp), shape = shape) {
            Text("Apri Player Lyrics", color = Color.White)
        }
    }
}
