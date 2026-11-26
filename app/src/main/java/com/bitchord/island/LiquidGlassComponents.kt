package com.bitchord.island

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

private enum class AppStage {
    SPLASH,
    PERMISSIONS,
    PLAYER
}

class MainActivity : ComponentActivity() {

    private val permissionRefreshTick = mutableIntStateOf(0)

    private val postNotifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefreshTick.intValue++
        if (granted && BitChordListenerService.isNotificationAccessGranted(this)) {
            BitChordListenerService.ensureConnected(this, forceRepublish = true)
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings(this)
        }
    }

    private fun allRequiredPermissionsReady(): Boolean {
        return BitChordListenerService.isNotificationAccessGranted(this) &&
            BitChordListenerService.areNotificationsEnabled(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0xFF07070C.toInt()))
        AppPreferences.init(this)
        applyWindowFrameRate(this, AppPreferences.targetFps.value.fps)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (allRequiredPermissionsReady()) {
            BitChordListenerService.ensureConnected(this, forceRepublish = true)
        }

        setContent {
            MaterialTheme {
                val font = rememberDynamicFont()
                val targetFps by AppPreferences.targetFps.collectAsState()
                var stage by remember { mutableStateOf(AppStage.SPLASH) }
                val refreshTick by permissionRefreshTick

                LaunchedEffect(targetFps) {
                    applyWindowFrameRate(this@MainActivity, targetFps.fps)
                }

                LaunchedEffect(refreshTick) {
                    if (allRequiredPermissionsReady()) {
                        repeat(6) { attempt ->
                            BitChordListenerService.ensureConnected(this@MainActivity, forceRepublish = true)
                            if (BitChordListenerService.isRunning() && attempt > 0) return@LaunchedEffect
                            delay(350L)
                        }
                    }
                }

                Crossfade(
                    targetState = stage,
                    animationSpec = tween(320),
                    label = "appStage"
                ) { current ->
                    when (current) {
                        AppStage.SPLASH -> {
                            DynamicLyricsSplashScreen(
                                font = font,
                                onFinished = {
                                    if (allRequiredPermissionsReady()) {
                                        BitChordListenerService.ensureConnected(this@MainActivity, forceRepublish = true)
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
                                refreshTick = refreshTick,
                                onRequestNotifications = { requestPostNotificationsIfNeeded() },
                                onAllReady = {
                                    BitChordListenerService.ensureConnected(this@MainActivity, forceRepublish = true)
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
        applyWindowFrameRate(this, AppPreferences.targetFps.value.fps)
        permissionRefreshTick.intValue++
        if (allRequiredPermissionsReady()) {
            BitChordListenerService.ensureConnected(this, forceRepublish = true)
        }
    }
}
