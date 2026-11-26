package com.bitchord.island

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*

private enum class AppStage {
    SPLASH,
    PERMISSIONS,
    PLAYER
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0xFF07070C.toInt()))
        AppPreferences.init(this)

        setContent {
            MaterialTheme {
                val font = rememberDynamicFont()
                var stage by remember { mutableStateOf(AppStage.SPLASH) }

                Crossfade(
                    targetState = stage,
                    animationSpec = tween(360),
                    label = "appStage"
                ) { current ->
                    when (current) {
                        AppStage.SPLASH -> {
                            DynamicLyricsSplashScreen(
                                font = font,
                                onFinished = {
                                    if (BitChordListenerService.isNotificationAccessGranted(this@MainActivity)) {
                                        BitChordListenerService.ensureConnected(this@MainActivity)
                                        stage = AppStage.PLAYER
                                    } else {
                                        stage = AppStage.PERMISSIONS
                                    }
                                }
                            )
                        }
                        AppStage.PERMISSIONS -> {
                            LiquidPermissionsScreen(
                                font = font,
                                onAllReady = {
                                    BitChordListenerService.ensureConnected(this@MainActivity)
                                    stage = AppStage.PLAYER
                                }
                            )
                        }
                        AppStage.PLAYER -> {
                            LiquidGlassLyricsPlayerScreen(
                                font = font,
                                showCloseButton = false
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (BitChordListenerService.isNotificationAccessGranted(this)) {
            BitChordListenerService.ensureConnected(this)
        }
    }
}
