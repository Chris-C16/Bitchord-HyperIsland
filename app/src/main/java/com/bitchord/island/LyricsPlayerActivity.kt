package com.bitchord.island

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class LyricsPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0xFF07070C.toInt()))
        AppPreferences.init(this)
        applyWindowFrameRate(this, AppPreferences.targetFps.value.fps)

        setContent {
            MaterialTheme {
                val font = rememberDynamicFont()
                val targetFps by AppPreferences.targetFps.collectAsState()

                LaunchedEffect(targetFps) {
                    applyWindowFrameRate(this@LyricsPlayerActivity, targetFps.fps)
                }

                LiquidGlassLyricsPlayerScreen(
                    font = font,
                    showCloseButton = true,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyWindowFrameRate(this, AppPreferences.targetFps.value.fps)
    }
}
