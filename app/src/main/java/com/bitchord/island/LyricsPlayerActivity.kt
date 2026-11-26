package com.bitchord.island

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme

class LyricsPlayerActivity : ComponentActivity() {
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
                LiquidGlassLyricsPlayerScreen(
                    font = font,
                    showCloseButton = true,
                    onClose = { finish() }
                )
            }
        }
    }
}
