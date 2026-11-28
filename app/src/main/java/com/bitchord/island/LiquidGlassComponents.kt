package com.bitchord.island

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------
// 1. Diagnostica & Blocco Frame Rate Reale (30 / 60 / 120 FPS — Default 60)
// ------------------------------------------------------------

private object LiquidGlassDiagnostics {
    val shaderStatus = MutableStateFlow("AGSL non inizializzato")

    @Volatile
    var shaderFrameVerified = false

    fun updateStatus(status: String) {
        if (shaderStatus.value != status) {
            shaderStatus.value = status
        }
    }
}

private object FrameRateReflection {
    val layoutParamsBoostMethod: Method? by lazy {
        try {
            WindowManager.LayoutParams::class.java.getMethod(
                "setFrameRateBoostOnTouchEnabled",
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            null
        }
    }

    val windowBoostMethod: Method? by lazy {
        try {
            Window::class.java.getMethod(
                "setFrameRateBoostOnTouchEnabled",
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            null
        }
    }

    val setRequestedFrameRateMethod: Method? by lazy {
        try {
            View::class.java.getMethod(
                "setRequestedFrameRate",
                Float::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            null
        }
    }

    val getRequestedFrameRateMethod: Method? by lazy {
        try {
            View::class.java.getMethod("getRequestedFrameRate")
        } catch (_: Throwable) {
            null
        }
    }
}

private object AppFrameRateTracker {
    private const val CAPACITY = 180
    private val timestampsNs = LongArray(CAPACITY)
    private var head = 0
    private var count = 0

    @Synchronized
    fun recordFrame(nowNs: Long) {
        timestampsNs[head] = nowNs
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    @Synchronized
    fun measureAppFps(nowNs: Long = System.nanoTime()): Int {
        if (count < 2) return 0
        val cutoffNs = nowNs - 1_000_000_000L
        var framesInWindow = 0
        var oldestNs = Long.MAX_VALUE
        var newestNs = 0L

        for (i in 0 until count) {
            val ts = timestampsNs[i]
            if (ts >= cutoffNs) {
                framesInWindow++
                if (ts < oldestNs) oldestNs = ts
                if (ts > newestNs) newestNs = ts
            }
        }
        if (framesInWindow < 2) return 0
        val spanNs = newestNs - oldestNs
        if (spanNs <= 50_000_000L) return 0
        return (((framesInWindow - 1) * 1_000_000_000.0) / spanNs)
            .roundToInt()
            .coerceAtLeast(1)
    }
}

private fun selectDesiredDisplayMode(display: Display?, targetFps: Int): Display.Mode? {
    return try {
        val currentMode = display?.mode ?: return null
        val modes = display.supportedModes ?: return null
        if (modes.isEmpty()) return null

        val sameRes = modes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        val pool = sameRes.ifEmpty { modes.toList() }
        if (targetFps <= 30) {
            val closest30 = pool.minByOrNull { abs(it.refreshRate - 30f) }
            if (closest30 != null && closest30.refreshRate <= 35f) {
                closest30
            } else {
                // 60 Hz è divisore esatto di 30 FPS (mai 120 Hz)
                pool.minByOrNull { abs(it.refreshRate - 60f) }
            }
        } else {
            pool.minByOrNull { abs(it.refreshRate - targetFps.toFloat()) }
        }
    } catch (_: Throwable) {
        null
    }
}

@SuppressLint("NewApi")
private fun applyWindowLayoutParamsFrameRate(activity: Activity, targetFps: Int) {
    try {
        val window = activity.window ?: return
        val lp = window.attributes ?: return

        val desiredMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                selectDesiredDisplayMode(activity.display, targetFps)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        if (desiredMode != null) {
            lp.preferredDisplayModeId = desiredMode.modeId
        }
        lp.preferredRefreshRate = when {
            targetFps <= 30 -> if (desiredMode != null && desiredMode.refreshRate <= 35f) desiredMode.refreshRate else 30f
            desiredMode != null -> desiredMode.refreshRate
            else -> targetFps.toFloat()
        }

        val allowTouchBoost = targetFps >= 120
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                lp.setFrameRateBoostOnTouchEnabled(allowTouchBoost)
            } catch (t: Throwable) {
                try {
                    FrameRateReflection.layoutParamsBoostMethod?.invoke(lp, allowTouchBoost)
                } catch (_: Throwable) {
                    Log.w("LiquidGlassFPS", "LayoutParams touchBoost fallback skipped: ${t.message}")
                }
            }
        } else {
            try {
                FrameRateReflection.layoutParamsBoostMethod?.invoke(lp, allowTouchBoost)
            } catch (_: Throwable) {
            }
        }

        window.attributes = lp

        // Verifica anche eventuale mirror su Window (OEM / Android 15+)
        try {
            FrameRateReflection.windowBoostMethod?.invoke(window, allowTouchBoost)
        } catch (_: Throwable) {
        }
    } catch (_: Throwable) {
    }
}

@SuppressLint("NewApi")
fun applyFrameRateRecursive(view: View, fps: Float) {
    if (fps.isNaN() || fps <= 0f) return
    try {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val current = view.getRequestedFrameRate()
                if (current.isNaN() || abs(current - fps) > 0.1f) {
                    view.setRequestedFrameRate(fps)
                }
            } catch (_: Throwable) {
                try {
                    val setter = FrameRateReflection.setRequestedFrameRateMethod
                    val getter = FrameRateReflection.getRequestedFrameRateMethod
                    val current = getter?.invoke(view) as? Float
                    if (current == null || current.isNaN() || abs(current - fps) > 0.1f) {
                        setter?.invoke(view, fps)
                    }
                } catch (_: Throwable) {
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                view.getChildAt(i)?.let { child -> applyFrameRateRecursive(child, fps) }
            }
        }
    } catch (_: Throwable) {
    }
}

fun applyWindowFrameRate(activity: Activity, targetFps: Int) {
    try {
        applyWindowLayoutParamsFrameRate(activity, targetFps)
        if (Build.VERSION.SDK_INT >= 35) {
            activity.window?.peekDecorView()?.let { decor ->
                applyFrameRateRecursive(decor, targetFps.toFloat())
            }
        }
    } catch (_: Throwable) {
    }
}

fun applyFrameRateToComposeView(composeView: View, activity: Activity, targetFps: Int) {
    try {
        applyWindowFrameRate(activity, targetFps)
        if (Build.VERSION.SDK_INT >= 35) {
            applyFrameRateRecursive(composeView, targetFps.toFloat())
        }
    } catch (_: Throwable) {
    }
}

/**
 * Aggancia sia il blocco ARR Android 15/16 sia un vero Frame Pacer lato app in OnPreDrawListener:
 * anche se HyperOS forza il pannello a 120 Hz fissi, 30 FPS e 60 FPS limitano realmente i draw
 * e il carico GPU/CPU al tetto scelto, tracciando gli FPS effettivi resi dall'app.
 */
@Composable
fun BindFrameRateLock(targetFps: TargetFps) {
    val context = LocalContext.current
    val composeView = LocalView.current
    val activity = context as? Activity

    LaunchedEffect(activity, composeView, targetFps) {
        if (activity != null) {
            try {
                applyFrameRateToComposeView(composeView, activity, targetFps.fps)
                composeView.post {
                    try {
                        applyFrameRateToComposeView(composeView, activity, targetFps.fps)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    SideEffect {
        if (activity != null && Build.VERSION.SDK_INT >= 35) {
            try {
                applyFrameRateRecursive(composeView, targetFps.fps.toFloat())
                activity.window?.peekDecorView()?.let { applyFrameRateRecursive(it, targetFps.fps.toFloat()) }
            } catch (_: Throwable) {
            }
        }
    }

    DisposableEffect(activity, composeView, targetFps) {
        if (activity == null) {
            return@DisposableEffect onDispose {}
        }

        val owner = context as? LifecycleOwner
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                try {
                    applyFrameRateToComposeView(composeView, activity, AppPreferences.targetFps.value.fps)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            owner?.lifecycle?.addObserver(lifecycleObserver)
        } catch (_: Throwable) {
        }

        val minDrawIntervalNs = when (targetFps) {
            TargetFps.FPS_30 -> 27_500_000L // ~30 FPS (su 60 Hz o 120 Hz)
            TargetFps.FPS_60 -> 13_500_000L // ~60 FPS (su 120 Hz)
            TargetFps.FPS_120 -> 0L
        }

        val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            private var lastDrawNs = 0L
            private var skippedConsecutive = 0

            override fun onPreDraw(): Boolean {
                try {
                    if (Build.VERSION.SDK_INT >= 35) {
                        val rate = targetFps.fps.toFloat()
                        applyFrameRateRecursive(composeView, rate)
                        activity.window?.peekDecorView()?.let { applyFrameRateRecursive(it, rate) }
                    }
                } catch (_: Throwable) {
                }

                val nowNs = System.nanoTime()
                if (minDrawIntervalNs > 0L && lastDrawNs > 0L) {
                    val elapsedNs = nowNs - lastDrawNs
                    if (elapsedNs < minDrawIntervalNs && skippedConsecutive < 5) {
                        skippedConsecutive++
                        return false
                    }
                }
                skippedConsecutive = 0
                lastDrawNs = nowNs
                AppFrameRateTracker.recordFrame(nowNs)
                return true
            }
        }

        try {
            val vto = composeView.viewTreeObserver
            if (vto.isAlive) {
                vto.addOnPreDrawListener(preDrawListener)
            }
        } catch (_: Throwable) {
        }

        onDispose {
            try {
                owner?.lifecycle?.removeObserver(lifecycleObserver)
            } catch (_: Throwable) {
            }
            try {
                if (composeView.viewTreeObserver.isAlive) {
                    composeView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                }
            } catch (_: Throwable) {
            }
        }
    }
}

private fun readActiveDisplayHz(context: Context): Int {
    return try {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = try {
            (context as? Activity)?.display
        } catch (_: Throwable) {
            null
        } ?: dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rate = display?.refreshRate ?: display?.mode?.refreshRate ?: 60f
        rate.roundToInt().coerceAtLeast(1)
    } catch (_: Throwable) {
        60
    }
}

/**
 * Telemetria in tempo reale per `Impostazioni & Stato`:
 * mantiene vivo un ciclo leggero di invalidazione View (solo mentre il modale è visibile)
 * così `AppFrameRateTracker` misura gli FPS effettivamente renderizzati dall'app sotto il pacer
 * e `displayHz` legge la frequenza hardware attiva del pannello.
 */
@Composable
fun rememberFrameRateTelemetry(targetFps: TargetFps): Pair<Int, Int> {
    val context = LocalContext.current
    val composeView = LocalView.current
    var appFps by remember { mutableIntStateOf(0) }
    var displayHz by remember { mutableIntStateOf(readActiveDisplayHz(context)) }

    DisposableEffect(context) {
        val dm = try {
            context.getSystemService(DisplayManager::class.java)
        } catch (_: Throwable) {
            null
        }
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displayHz = readActiveDisplayHz(context)
            }

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                displayHz = readActiveDisplayHz(context)
            }
        }
        try {
            dm?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
        }
        onDispose {
            try {
                dm?.unregisterDisplayListener(displayListener)
            } catch (_: Throwable) {
            }
        }
    }

    LaunchedEffect(composeView, targetFps) {
        var lastReportMs = SystemClock.uptimeMillis()
        while (isActive) {
            withFrameNanos {
                // Mantiene attivo il flusso di controllo preDraw senza ricomposizioni Compose
                try {
                    composeView.invalidate()
                } catch (_: Throwable) {
                }
            }
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastReportMs >= 450L) {
                appFps = AppFrameRateTracker.measureAppFps()
                displayHz = readActiveDisplayHz(context)
                lastReportMs = nowMs
            }
        }
    }

    return appFps to displayHz
}

// ------------------------------------------------------------
// 2. Motore Liquid Glass iOS 26 (Single-RuntimeShader Bulletproof)
// - UN SOLO RuntimeShader("composable") applicato al Box full-bleed
//   (LiquidAmbientBackground + LazyColumn testi)
// - Tutti i Rect sono calcolati nello stesso coordinate space del Box root
//   tramite boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
// - Nessun rememberGraphicsLayer / drawLayer intermedio: zero bug alpha=0
// - Sintassi Skia AGSL rigorosa (zero const locali, zero array dinamici,
//   edge0 < edge1 in ogni smoothstep, conversioni float/half esplicite)
// - Errori mai silenziati: Log.e + shaderStatus visibile in Impostazioni
// ------------------------------------------------------------

private const val IOS_LIQUID_GLASS_AGSL = """
uniform shader composable;
uniform float4 rect0;
uniform float4 rect1;
uniform float4 rect2;
uniform float4 rect3;
uniform float4 rect4;
uniform float4 rect5;
uniform float radius0;
uniform float radius1;
uniform float radius2;
uniform float radius3;
uniform float radius4;
uniform float radius5;
uniform float style0;
uniform float style1;
uniform float style2;
uniform float style3;
uniform float style4;
uniform float style5;
uniform float density;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

float ign(float2 p) {
    return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
}

float2 snellShift(float2 n, float sinA, float cosA, float eta) {
    float sin2T = eta * eta * sinA * sinA;
    float cosT = sqrt(max(1.0 - sin2T, 0.0001));
    float k = eta * cosA - cosT;
    float tz = max(eta - k * cosA, 0.25);
    return n * ((k * sinA) / tz);
}

float evalShapeSdf(float2 fragCoord, float4 r, float rad) {
    float2 c = 0.5 * (r.xy + r.zw);
    float2 h = max(0.5 * (r.zw - r.xy), float2(2.0, 2.0));
    float cr = clamp(rad, 1.0, min(h.x, h.y));
    return sdRoundBox(fragCoord - c, h, cr);
}

float evalDropShadow(float2 fragCoord, float4 r, float rad, float style, float dpx) {
    float2 c = 0.5 * (r.xy + r.zw) + float2(0.0, 4.5 * dpx);
    float2 h = max(0.5 * (r.zw - r.xy), float2(2.0, 2.0));
    float cr = clamp(rad, 1.0, min(h.x, h.y));
    float sd = sdRoundBox(fragCoord - c, h, cr);
    float spread = style >= 1.85 ? 18.0 * dpx : 12.0 * dpx;
    float strength = style >= 1.85 ? 0.34 : 0.22;
    return (1.0 - smoothstep(0.0, max(spread, 1.0), max(sd, 0.0))) * strength;
}

half4 main(float2 fragCoord) {
    float dpx = max(density, 0.5);
    float pad = 22.0 * dpx;
    float bestD = 1000000.0;
    float4 bestRect = float4(0.0, 0.0, 0.0, 0.0);
    float bestRad = 1.0;
    float bestStyle = 0.0;
    float shadowAcc = 0.0;

    if (style0 > 0.01 && rect0.z > rect0.x + 2.0 && rect0.w > rect0.y + 2.0 &&
        fragCoord.x >= rect0.x - pad && fragCoord.x <= rect0.z + pad &&
        fragCoord.y >= rect0.y - pad && fragCoord.y <= rect0.w + pad) {
        float d = evalShapeSdf(fragCoord, rect0, radius0);
        shadowAcc = max(shadowAcc, evalDropShadow(fragCoord, rect0, radius0, style0, dpx));
        if (d < bestD) {
            bestD = d;
            bestRect = rect0;
            bestRad = radius0;
            bestStyle = style0;
        }
    }
    if (style1 > 0.01 && rect1.z > rect1.x + 2.0 && rect1.w > rect1.y + 2.0 &&
        fragCoord.x >= rect1.x - pad && fragCoord.x <= rect1.z + pad &&
        fragCoord.y >= rect1.y - pad && fragCoord.y <= rect1.w + pad) {
        float d = evalShapeSdf(fragCoord, rect1, radius1);
        shadowAcc = max(shadowAcc, evalDropShadow(fragCoord, rect1, radius1, style1, dpx));
        if (d < bestD) {
            bestD = d;
            bestRect = rect1;
            bestRad = radius1;
            bestStyle = style1;
        }
    }
    if (style2 > 0.01 && rect2.z > rect2.x + 2.0 && rect2.w > rect2.y + 2.0 &&
        fragCoord.x >= rect2.x - pad && fragCoord.x <= rect2.z + pad &&
        fragCoord.y >= rect2.y - pad && fragCoord.y <= rect2.w + pad) {
        float d = evalShapeSdf(fragCoord, rect2, radius2);
        shadowAcc = max(shadowAcc, evalDropShadow(fragCoord, rect2, radius2, style2, dpx));
        if (d < bestD) {
            bestD = d;
            bestRect = rect2;
            bestRad = radius2;
            bestStyle = style2;
        }
    }
    if (style3 > 0.01 && rect3.z > rect3.x + 2.0 && rect3.w > rect3.y + 2.0 &&
        fragCoord.x >= rect3.x - pad && fragCoord.x <= rect3.z + pad &&
        fragCoord.y >= rect3.y - pad && fragCoord.y <= rect3.w + pad) {
        float d = evalShapeSdf(fragCoord, rect3, radius3);
        shadowAcc = max(shadowAcc, evalDropShadow(fragCoord, rect3, radius3, style3, dpx));
        if (d < bestD) {
            bestD = d;
            bestRect = rect3;
            bestRad = radius3;
            bestStyle = style3;
        }
    }
    if (style4 > 0.01 && rect4.z > rect4.x + 2.0 && rect4.w > rect4.y + 2.0 &&
        fragCoord.x >= rect4.x - pad && fragCoord.x <= rect4.z + pad &&
        fragCoord.y >= rect4.y - pad && fragCoord.y <= rect4.w + pad) {
        float d = evalShapeSdf(fragCoord, rect4, radius4);
        shadowAcc = max(shadowAcc, evalDropShadow(fragCoord, rect4, radius4, style4, dpx));
        if (d < bestD) {
            bestD = d;
            bestRect = rect4;
            bestRad = radius4;
            bestStyle = style4;
        }
    }
    if (style5 > 0.01 && rect5.z > rect5.x + 2.0 && rect5.w > rect5.y + 2.0 &&
        fragCoord.x >= rect5.x - pad && fragCoord.x <= rect5.z + pad &&
        fragCoord.y >= rect5.y - pad && fragCoord.y <= rect5.w + pad) {
        float d = evalShapeSdf(fragCoord, rect5, radius5);
        shadowAcc = max(shadowAcc, evalDropShadow(fragCoord, rect5, radius5, style5, dpx));
        if (d <= 0.75 || d < bestD) {
            bestD = d;
            bestRect = rect5;
            bestRad = radius5;
            bestStyle = style5;
        }
    }

    half4 baseHalf = composable.eval(fragCoord);
    if (baseHalf.a < 0.001) {
        return baseHalf;
    }

    float baseAlpha = float(baseHalf.a);
    float3 outsideRgb = float3(baseHalf.rgb) * (1.0 - shadowAcc);
    if (bestStyle <= 0.01 || bestD >= 0.75) {
        return half4(half3(outsideRgb), baseHalf.a);
    }

    float2 c = 0.5 * (bestRect.xy + bestRect.zw);
    float2 halfSize = max(0.5 * (bestRect.zw - bestRect.xy), float2(2.0, 2.0));
    float rad = clamp(bestRad, 1.0, min(halfSize.x, halfSize.y));
    float2 p = fragCoord - c;

    float eps = 1.25;
    float gx = sdRoundBox(p + float2(eps, 0.0), halfSize, rad) - sdRoundBox(p - float2(eps, 0.0), halfSize, rad);
    float gy = sdRoundBox(p + float2(0.0, eps), halfSize, rad) - sdRoundBox(p - float2(0.0, eps), halfSize, rad);
    float2 grad = float2(gx, gy);
    float gradLen = length(grad);
    float2 n = gradLen > 0.0001 ? (grad / gradLen) : float2(0.0, -1.0);

    float minHalf = min(halfSize.x, halfSize.y);
    float distIn = max(-bestD, 0.0);
    float maxBezel = max(minHalf * 0.85, 1.0);
    float minBezel = min(2.0 * dpx, maxBezel);
    float bezel = clamp(17.0 * dpx, minBezel, maxBezel);
    float t = clamp(distIn / bezel, 0.0, 1.0);
    float u = 1.0 - t;
    float sinA = clamp(pow(max(u, 0.0001), 1.65), 0.0, 0.985);
    float z = sqrt(max(1.0 - sinA * sinA, 0.0001));

    float pressBoost = (bestStyle > 1.0 && bestStyle < 1.8)
        ? clamp((bestStyle - 1.0) * 2.0, 0.0, 1.0)
        : 0.0;
    float isPopover = (bestStyle >= 1.8 && bestStyle < 2.5) ? 1.0 : 0.0;
    float isModal = bestStyle >= 2.5 ? 1.0 : 0.0;

    float blurRad = mix(4.2, 2.0, pressBoost) * dpx;
    if (isPopover > 0.5) blurRad = 6.2 * dpx;
    if (isModal > 0.5) blurRad = 7.2 * dpx;

    float zoomAmt = mix(2.4, 4.2, pressBoost) * dpx;
    if (isModal > 0.5 || isPopover > 0.5) zoomAmt = 1.8 * dpx;

    float refractAmt = mix(14.5, 20.5, pressBoost) * dpx;
    float2 localNorm = p / max(halfSize, float2(1.0, 1.0));
    float2 centerLens = -localNorm * zoomAmt * z;
    float2 posG = fragCoord + centerLens + snellShift(n, sinA, z, 1.0 / 1.52) * refractAmt;

    // Campionamento blur morbido a 7 tap ruotati da IGN (srotolato, zero loop dinamici su Vulkan/Skia)
    float3 rgb;
    if (blurRad <= 0.6) {
        rgb = float3(composable.eval(posG).rgb);
    } else {
        float seedAngle = ign(fragCoord) * 6.2831853;
        float c0 = cos(seedAngle);
        float s0 = sin(seedAngle);
        float2 d1 = float2(c0, s0);
        float2 d2 = float2(0.5 * c0 - 0.8660254 * s0, 0.5 * s0 + 0.8660254 * c0);
        float2 d3 = float2(-0.5 * c0 - 0.8660254 * s0, -0.5 * s0 + 0.8660254 * c0);
        float rOuter = blurRad;
        float rInner = blurRad * 0.52;

        half4 sc = composable.eval(posG);
        half4 s1 = composable.eval(posG + d1 * rOuter);
        half4 s2 = composable.eval(posG - d1 * rOuter);
        half4 s3 = composable.eval(posG + d2 * rInner);
        half4 s4 = composable.eval(posG - d2 * rInner);
        half4 s5 = composable.eval(posG + d3 * rOuter);
        half4 s6 = composable.eval(posG - d3 * rOuter);

        float wSum = 1.4 * float(sc.a)
            + float(s1.a) + float(s2.a)
            + 1.2 * float(s3.a) + 1.2 * float(s4.a)
            + float(s5.a) + float(s6.a);
        float3 acc = 1.4 * float3(sc.rgb)
            + float3(s1.rgb) + float3(s2.rgb)
            + 1.2 * float3(s3.rgb) + 1.2 * float3(s4.rgb)
            + float3(s5.rgb) + float3(s6.rgb);
        rgb = wSum > 0.01 ? (acc / wSum) : float3(sc.rgb);
    }

    // Dispersione prismatica RGB lungo il menisco perimetrale curvo
    if (t < 0.76) {
        float2 posR = fragCoord + centerLens + snellShift(n, sinA, z, 1.0 / 1.38) * refractAmt;
        float2 posB = fragCoord + centerLens + snellShift(n, sinA, z, 1.0 / 1.70) * refractAmt;
        half4 sR = composable.eval(posR);
        half4 sB = composable.eval(posB);
        float chromaFade = 1.0 - smoothstep(0.04, 0.76, t);
        float chromaMix = chromaFade * mix(0.48, 0.85, pressBoost);
        if (sR.a > 0.1) {
            rgb.r = mix(rgb.r, float(sR.r) / float(sR.a), chromaMix);
        }
        if (sB.a > 0.1) {
            rgb.b = mix(rgb.b, float(sB.b) / float(sB.a), chromaMix);
        }
    }

    // Vibrancy iOS 26 (1.30x) + Tinta scura traslucida calibrata
    float luma = dot(rgb, float3(0.299, 0.587, 0.114));
    float satBoost = mix(1.30, 1.38, pressBoost);
    rgb = mix(float3(luma, luma, luma), rgb, satBoost);

    float3 tintColor = (isModal > 0.5 || isPopover > 0.5)
        ? float3(0.047, 0.055, 0.086)
        : float3(0.063, 0.071, 0.110);
    float tintAlpha = mix(0.28, 0.18, pressBoost);
    if (isPopover > 0.5) tintAlpha = 0.46;
    if (isModal > 0.5) tintAlpha = 0.52;
    rgb = mix(rgb, tintColor, tintAlpha);

    // Sheen lucido superiore + leggera caustica inferiore
    float normY = clamp(p.y / max(halfSize.y, 1.0), -1.0, 1.0);
    float topGlaze = (1.0 - smoothstep(-1.0, 0.28, normY)) * mix(0.065, 0.11, pressBoost);
    float botCaustic = smoothstep(0.45, 1.0, normY) * 0.028;
    rgb += float3(topGlaze + botCaustic, topGlaze + botCaustic, topGlaze + botCaustic);

    // Sottile scanalatura 3D interna sotto il bordo speculare
    float groove = smoothstep(1.1 * dpx, 2.5 * dpx, distIn) * (1.0 - smoothstep(2.5 * dpx, 6.0 * dpx, distIn));
    rgb *= (1.0 - groove * 0.06);

    // Riflesso speculare Fresnel razor-sharp (~1.35dp) modulato dalla luminanza sottostante
    float2 keyLight = float2(-0.5594, -0.8289);
    float2 backLight = float2(0.5594, 0.8289);
    float specKey = pow(max(dot(n, keyLight), 0.0001), 2.2);
    float specBack = pow(max(dot(n, backLight), 0.0001), 2.8) * 0.58;
    float rimDir = specKey + specBack;
    float fresnel = pow(max(1.0 - z, 0.0001), 2.2);

    float hairline = 1.0 - smoothstep(0.0, 1.35 * dpx, distIn);
    float innerHalo = (1.0 - smoothstep(0.0, 5.5 * dpx, distIn)) * (0.05 + 0.12 * fresnel);

    float lumaLift = 0.78 + 0.48 * clamp(luma, 0.0, 1.0);
    float rimBright = mix(0.90, 1.22, pressBoost) * lumaLift;
    float rimAdd = hairline * (0.18 + 0.74 * rimDir) * rimBright + innerHalo * (0.25 + 0.75 * specKey);

    float3 glassRgb = clamp(rgb + float3(rimAdd, rimAdd, rimAdd), float3(0.0, 0.0, 0.0), float3(1.0, 1.0, 1.0));
    float aa = 1.0 - smoothstep(-0.75, 0.75, bestD);
    float3 finalRgb = mix(outsideRgb, glassRgb, aa);

    return half4(half3(finalRgb * baseAlpha), baseHalf.a);
}
"""

private data class GlassShapeSpec(
    val rect: Rect = Rect.Zero,
    val cornerRadiusPx: Float = 0f,
    val style: Float = 0f
)

private fun GlassShapeSpec.isRenderable(): Boolean {
    return style > 0.01f && !rect.isEmpty && (rect.right - rect.left) > 2f && (rect.bottom - rect.top) > 2f
}

private fun safeBlurRenderEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusPx <= 0.5f) return null
    return try {
        RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP).asComposeRenderEffect()
    } catch (t: Throwable) {
        Log.e("LiquidGlass", "createBlurEffect failed: ${t.message}", t)
        null
    }
}

@Composable
private fun rememberLiquidGlassRenderEffect(
    shape0: GlassShapeSpec = GlassShapeSpec(),
    shape1: GlassShapeSpec = GlassShapeSpec(),
    shape2: GlassShapeSpec = GlassShapeSpec(),
    shape3: GlassShapeSpec = GlassShapeSpec(),
    shape4: GlassShapeSpec = GlassShapeSpec(),
    shape5: GlassShapeSpec = GlassShapeSpec(),
    densityPx: Float
): androidx.compose.ui.graphics.RenderEffect? {
    val context = LocalContext.current
    val shaderSafeMode by AppPreferences.shaderSafeMode.collectAsState()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        SideEffect {
            LiquidGlassDiagnostics.updateStatus("Richiede Android 13+")
        }
        return null
    }

    if (shaderSafeMode) {
        SideEffect {
            LiquidGlassDiagnostics.updateStatus("Safe Mode (crash nativo)")
        }
        return null
    }

    val compiledPair = remember(shaderSafeMode) {
        if (!LiquidGlassDiagnostics.shaderFrameVerified) {
            AppPreferences.markShaderPendingFrameSync(context, true)
        }
        try {
            RuntimeShader(IOS_LIQUID_GLASS_AGSL).also {
                if (!LiquidGlassDiagnostics.shaderFrameVerified) {
                    AppPreferences.markShaderPendingFrameSync(context, false)
                }
            } to "AGSL OK"
        } catch (t: Throwable) {
            AppPreferences.markShaderPendingFrameSync(context, false)
            val detail = "${t.javaClass.simpleName}: ${t.message ?: "errore sconosciuto"}"
            Log.e("LiquidGlass", "RuntimeShader compilation failed: $detail", t)
            null to detail
        }
    }

    val compiledShader = compiledPair.first
    val anyActive = shape0.isRenderable() ||
        shape1.isRenderable() ||
        shape2.isRenderable() ||
        shape3.isRenderable() ||
        shape4.isRenderable() ||
        shape5.isRenderable()

    val resultPair = remember(compiledShader, shape0, shape1, shape2, shape3, shape4, shape5, densityPx, anyActive) {
        if (compiledShader == null) {
            val fallback = if (shape5.style >= 2.5f) safeBlurRenderEffect(20f) else null
            fallback to compiledPair.second
        } else if (!anyActive) {
            null to "AGSL OK"
        } else {
            try {
                if (!LiquidGlassDiagnostics.shaderFrameVerified) {
                    AppPreferences.markShaderPendingFrameSync(context, true)
                }
                compiledShader.setFloatUniform("rect0", shape0.rect.left, shape0.rect.top, shape0.rect.right, shape0.rect.bottom)
                compiledShader.setFloatUniform("rect1", shape1.rect.left, shape1.rect.top, shape1.rect.right, shape1.rect.bottom)
                compiledShader.setFloatUniform("rect2", shape2.rect.left, shape2.rect.top, shape2.rect.right, shape2.rect.bottom)
                compiledShader.setFloatUniform("rect3", shape3.rect.left, shape3.rect.top, shape3.rect.right, shape3.rect.bottom)
                compiledShader.setFloatUniform("rect4", shape4.rect.left, shape4.rect.top, shape4.rect.right, shape4.rect.bottom)
                compiledShader.setFloatUniform("rect5", shape5.rect.left, shape5.rect.top, shape5.rect.right, shape5.rect.bottom)
                compiledShader.setFloatUniform("radius0", shape0.cornerRadiusPx.coerceAtLeast(1f))
                compiledShader.setFloatUniform("radius1", shape1.cornerRadiusPx.coerceAtLeast(1f))
                compiledShader.setFloatUniform("radius2", shape2.cornerRadiusPx.coerceAtLeast(1f))
                compiledShader.setFloatUniform("radius3", shape3.cornerRadiusPx.coerceAtLeast(1f))
                compiledShader.setFloatUniform("radius4", shape4.cornerRadiusPx.coerceAtLeast(1f))
                compiledShader.setFloatUniform("radius5", shape5.cornerRadiusPx.coerceAtLeast(1f))
                compiledShader.setFloatUniform("style0", shape0.style)
                compiledShader.setFloatUniform("style1", shape1.style)
                compiledShader.setFloatUniform("style2", shape2.style)
                compiledShader.setFloatUniform("style3", shape3.style)
                compiledShader.setFloatUniform("style4", shape4.style)
                compiledShader.setFloatUniform("style5", shape5.style)
                compiledShader.setFloatUniform("density", densityPx.coerceAtLeast(0.5f))
                val effect = RenderEffect.createRuntimeShaderEffect(compiledShader, "composable").asComposeRenderEffect()
                effect to "AGSL OK"
            } catch (t: Throwable) {
                AppPreferences.markShaderPendingFrameSync(context, false)
                val detail = "${t.javaClass.simpleName}: ${t.message ?: "errore uniform/effect"}"
                Log.e("LiquidGlass", "RenderEffect update failed: $detail", t)
                val fallback = if (shape5.style >= 2.5f) safeBlurRenderEffect(20f) else null
                fallback to detail
            }
        }
    }

    val effect = resultPair.first
    LaunchedEffect(effect, shaderSafeMode) {
        if (!shaderSafeMode && effect != null && !LiquidGlassDiagnostics.shaderFrameVerified) {
            withFrameNanos { }
            withFrameNanos { }
            LiquidGlassDiagnostics.shaderFrameVerified = true
            AppPreferences.markShaderPendingFrameSync(context, false)
        }
    }

    val statusText = resultPair.second
    SideEffect {
        LiquidGlassDiagnostics.updateStatus(statusText)
    }

    return effect
}

private fun Modifier.reportBoundsInRoot(onBounds: (Rect) -> Unit): Modifier =
    this.onGloballyPositioned { coords ->
        onBounds(coords.boundsInRoot())
    }

// ------------------------------------------------------------
// 3. Stili Testo & Modificatore Pulsanti Crystal Glass
// ------------------------------------------------------------
private val GlassTextShadowStyle = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.44f),
        offset = Offset(0f, 1.5f),
        blurRadius = 6f
    )
)

fun Modifier.iosGlassButton(
    shape: Shape = CircleShape,
    elevated: Boolean = false,
    pressed: Boolean = false,
    tintColor: Color = Color.Transparent
): Modifier = this
    .clip(shape)
    .then(
        if (tintColor != Color.Transparent) {
            Modifier.background(tintColor, shape)
        } else {
            Modifier
        }
    )
    .background(
        Brush.verticalGradient(
            0.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.24f
                    elevated -> 0.22f
                    else -> 0.11f
                }
            ),
            0.40f to Color.White.copy(
                alpha = when {
                    pressed -> 0.06f
                    elevated -> 0.07f
                    else -> 0.03f
                }
            ),
            1.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.14f
                    elevated -> 0.11f
                    else -> 0.05f
                }
            )
        )
    )
    .border(
        width = if (pressed) 1.0.dp else 0.8.dp,
        brush = Brush.linearGradient(
            0.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.94f
                    elevated -> 0.72f
                    else -> 0.48f
                }
            ),
            0.45f to Color.White.copy(alpha = if (pressed) 0.28f else 0.09f),
            1.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.70f
                    elevated -> 0.46f
                    else -> 0.26f
                }
            )
        ),
        shape = shape
    )

private fun Modifier.iosGlassFallbackSurface(
    active: Boolean,
    shape: Shape,
    tintAlpha: Float = 0.42f
): Modifier = if (!active) {
    this
} else {
    this
        .clip(shape)
        .background(Color(0xFF10121C).copy(alpha = tintAlpha), shape)
        .border(0.9.dp, Color.White.copy(alpha = 0.34f), shape)
}

// ------------------------------------------------------------
// 4. Font stile Apple SF Pro Display (sf_pro.ttf locale o Inter)
// ------------------------------------------------------------
private fun isValidFontFile(file: File): Boolean {
    return try {
        if (!file.exists() || file.length() < 40_000L) return false
        val header = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(header) != 4) return false
        }
        val isSfnt = header[0] == 0x00.toByte() &&
            header[1] == 0x01.toByte() &&
            header[2] == 0x00.toByte() &&
            header[3] == 0x00.toByte()
        val tag = String(header, Charsets.US_ASCII)
        if (!isSfnt && tag != "OTTO" && tag != "true" && tag != "ttcf") {
            return false
        }
        val tf = android.graphics.Typeface.createFromFile(file)
        tf != null && tf != android.graphics.Typeface.DEFAULT
    } catch (_: Throwable) {
        false
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberDynamicFont(): FontFamily {
    val context = LocalContext.current
    val family by produceState<FontFamily>(initialValue = FontFamily.SansSerif) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val localSfProId = context.resources.getIdentifier("sf_pro", "font", context.packageName)
                if (localSfProId != 0) {
                    try {
                        return@withContext FontFamily(
                            Font(localSfProId, weight = FontWeight.Normal, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
                            Font(localSfProId, weight = FontWeight.Medium, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
                            Font(localSfProId, weight = FontWeight.SemiBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
                            Font(localSfProId, weight = FontWeight.Bold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
                            Font(localSfProId, weight = FontWeight.ExtraBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
                        )
                    } catch (_: Throwable) {
                    }
                }

                val dir = File(context.filesDir, "fonts")
                val file = File(dir, "InterAppleStyle.ttf")
                if (file.exists() && !isValidFontFile(file)) {
                    try {
                        file.delete()
                    } catch (_: Throwable) {
                    }
                }
                if (!file.exists() || file.length() < 40_000L) {
                    if (!dir.exists()) dir.mkdirs()
                    val urls = listOf(
                        "https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf",
                        "https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bslnt%2Cwght%5D.ttf",
                        "https://github.com/rsms/inter/raw/master/docs/font-files/InterVariable.ttf"
                    )
                    for (urlStr in urls) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 6000
                                readTimeout = 6000
                                instanceFollowRedirects = true
                            }
                            if (conn.responseCode in 200..299) {
                                conn.inputStream.use { input ->
                                    file.outputStream().use { output -> input.copyTo(output) }
                                }
                                if (isValidFontFile(file)) {
                                    break
                                } else {
                                    try {
                                        file.delete()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            try {
                                if (file.exists() && !isValidFontFile(file)) file.delete()
                            } catch (_: Throwable) {
                            }
                        } finally {
                            try {
                                conn?.disconnect()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                if (isValidFontFile(file)) {
                    try {
                        FontFamily(
                            Font(file, weight = FontWeight.Normal, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
                            Font(file, weight = FontWeight.Medium, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
                            Font(file, weight = FontWeight.SemiBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
                            Font(file, weight = FontWeight.Bold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
                            Font(file, weight = FontWeight.ExtraBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
                        )
                    } catch (_: Throwable) {
                        try {
                            file.delete()
                        } catch (_: Throwable) {
                        }
                        null
                    }
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
        if (loaded != null) value = loaded
    }
    return family
}

// ------------------------------------------------------------
// 5. Sfondo Vivido ad Alto Contrasto per Rifrazione Liquid Glass
// ------------------------------------------------------------
@Composable
fun LiquidAmbientBackground(
    cover: Bitmap?,
    palette: CoverPalette
) {
    val coverImg = remember(cover) {
        if (cover != null && !cover.isRecycled && cover.width > 0 && cover.height > 0) {
            try {
                Bitmap.createScaledBitmap(cover, 192, 192, true).asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    val blurEffect = remember {
        safeBlurRenderEffect(24f)
    }

    val baseBrush = remember(coverImg, palette) {
        if (coverImg != null) {
            Brush.verticalGradient(
                colors = listOf(
                    palette.accent.copy(alpha = 0.86f),
                    palette.primary,
                    Color(0xFF05060C)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF070914),
                    Color(0xFF05060C),
                    Color(0xFF04040A)
                )
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(baseBrush)) {
        if (coverImg != null) {
            Image(
                bitmap = coverImg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                        renderEffect = blurEffect
                    }
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (w <= 1f || h <= 1f) return@Canvas

                val r1 = (w * 0.85f).coerceAtLeast(1f)
                val r2 = (w * 0.90f).coerceAtLeast(1f)

                // Bagliori radiali saturi su nero profondo (#05060C)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color(0xFF2563EB).copy(alpha = 0.56f),
                            Color(0xFF4F46E5).copy(alpha = 0.24f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.16f, h * 0.16f),
                        radius = r1
                    ),
                    center = Offset(w * 0.16f, h * 0.16f),
                    radius = r1
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color(0xFFDB2777).copy(alpha = 0.50f),
                            Color(0xFF7C3AED).copy(alpha = 0.22f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.86f, h * 0.78f),
                        radius = r2
                    ),
                    center = Offset(w * 0.86f, h * 0.78f),
                    radius = r2
                )

                // Fasci luminosi diagonali definiti (massima resa della rifrazione su bordi e menisco)
                rotate(degrees = -26f, pivot = Offset(w * 0.50f, h * 0.22f)) {
                    val beamH = (h * 0.19f).coerceAtLeast(2f)
                    val topY = h * 0.04f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.25f to Color(0xFF38BDF8).copy(alpha = 0.42f),
                            0.50f to Color(0xFF60A5FA).copy(alpha = 0.72f),
                            0.75f to Color(0xFF6366F1).copy(alpha = 0.36f),
                            1.0f to Color.Transparent,
                            startY = topY,
                            endY = topY + beamH
                        ),
                        topLeft = Offset(-w * 0.40f, topY),
                        size = Size((w * 1.85f).coerceAtLeast(2f), beamH),
                        cornerRadius = CornerRadius((h * 0.09f).coerceAtLeast(1f), (h * 0.09f).coerceAtLeast(1f))
                    )
                }

                rotate(degrees = -24f, pivot = Offset(w * 0.50f, h * 0.52f)) {
                    val beamH = (h * 0.22f).coerceAtLeast(2f)
                    val topY = h * 0.40f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.28f to Color(0xFF8B5CF6).copy(alpha = 0.44f),
                            0.50f to Color(0xFFEC4899).copy(alpha = 0.68f),
                            0.76f to Color(0xFF6366F1).copy(alpha = 0.34f),
                            1.0f to Color.Transparent,
                            startY = topY,
                            endY = topY + beamH
                        ),
                        topLeft = Offset(-w * 0.40f, topY),
                        size = Size((w * 1.85f).coerceAtLeast(2f), beamH),
                        cornerRadius = CornerRadius((h * 0.11f).coerceAtLeast(1f), (h * 0.11f).coerceAtLeast(1f))
                    )
                }

                rotate(degrees = -22f, pivot = Offset(w * 0.50f, h * 0.86f)) {
                    val beamH = (h * 0.18f).coerceAtLeast(2f)
                    val topY = h * 0.77f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.25f to Color(0xFF3B82F6).copy(alpha = 0.48f),
                            0.52f to Color(0xFFF43F5E).copy(alpha = 0.62f),
                            0.80f to Color(0xFF8B5CF6).copy(alpha = 0.28f),
                            1.0f to Color.Transparent,
                            startY = topY,
                            endY = topY + beamH
                        ),
                        topLeft = Offset(-w * 0.40f, topY),
                        size = Size((w * 1.85f).coerceAtLeast(2f), beamH),
                        cornerRadius = CornerRadius((h * 0.09f).coerceAtLeast(1f), (h * 0.09f).coerceAtLeast(1f))
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.10f),
                        0.5f to Color.Black.copy(alpha = 0.15f),
                        1.0f to Color.Black.copy(alpha = 0.24f)
                    )
                )
        )
    }
}

// ------------------------------------------------------------
// 6. Icona Bolla Testi + Splash Screen
// ------------------------------------------------------------
@Composable
fun DynamicLyricsBubbleGlyph(
    wavePhase: Float = 0.8f,
    shimmer: Float = -1f,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas

        val bubbleW = w * 0.64f
        val bubbleH = h * 0.48f
        val bx = (w - bubbleW) / 2f
        val by = (h - bubbleH) / 2f - h * 0.03f
        val br = bubbleH * 0.34f

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFF4D7D), Color(0xFFC026D3), Color(0xFF6366F1)),
                start = Offset(bx, by),
                end = Offset(bx + bubbleW, by + bubbleH)
            ),
            topLeft = Offset(bx, by),
            size = Size(bubbleW, bubbleH),
            cornerRadius = CornerRadius(br, br)
        )

        val tail = Path().apply {
            moveTo(bx + bubbleW * 0.14f, by + bubbleH * 0.82f)
            lineTo(bx + bubbleW * 0.04f, by + bubbleH * 1.14f)
            lineTo(bx + bubbleW * 0.34f, by + bubbleH * 0.94f)
            close()
        }
        drawPath(
            path = tail,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFE0339B), Color(0xFF9333EA)),
                start = Offset(bx, by + bubbleH * 0.7f),
                end = Offset(bx + bubbleW * 0.4f, by + bubbleH * 1.15f)
            )
        )

        val activePulse = (sin(wavePhase) + 1f) * 0.5f
        val lineX = bx + bubbleW * 0.17f
        val widths = floatArrayOf(bubbleW * 0.46f, bubbleW * 0.64f, bubbleW * 0.36f)
        val heights = floatArrayOf(h * 0.052f, h * 0.062f, h * 0.052f)
        val ys = floatArrayOf(by + bubbleH * 0.24f, by + bubbleH * 0.46f, by + bubbleH * 0.70f)
        val alphas = floatArrayOf(
            0.45f + 0.30f * (1f - activePulse),
            0.82f + 0.18f * activePulse,
            0.38f + 0.32f * activePulse
        )

        for (i in 0 until 3) {
            val lh = heights[i]
            drawRoundRect(
                color = Color.White.copy(alpha = alphas[i]),
                topLeft = Offset(lineX, ys[i]),
                size = Size(widths[i], lh),
                cornerRadius = CornerRadius(lh / 2f, lh / 2f)
            )
        }

        if (shimmer >= -0.6f) {
            val sheenX = w * shimmer
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.24f), Color.Transparent),
                    start = Offset(sheenX - w * 0.45f, 0f),
                    end = Offset(sheenX + w * 0.25f, h)
                )
            )
        }
    }
}

@Composable
fun DynamicLyricsSplashScreen(
    font: FontFamily,
    onFinished: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entered = true
        delay(1200L)
        onFinished()
    }

    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.74f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "splashScale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "splashAlpha"
    )

    val infinite = rememberInfiniteTransition(label = "splashWave")
    val wavePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    val shimmer by infinite.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF15122B), Color(0xFF0B0A16), Color(0xFF06060C))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(250.dp)
                .graphicsLayer { alpha = contentAlpha * 0.78f }
                .drawBehind {
                    if (size.width <= 1f || size.height <= 1f) return@drawBehind
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFE11D48).copy(alpha = 0.42f),
                                Color(0xFF6366F1).copy(alpha = 0.24f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = (size.minDimension / 2f).coerceAtLeast(1f)
                        )
                    )
                }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = contentAlpha
                scaleX = scale
                scaleY = scale
            }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(104.dp)
                    .iosGlassButton(
                        shape = RoundedCornerShape(30.dp),
                        elevated = true
                    )
            ) {
                DynamicLyricsBubbleGlyph(wavePhase = wavePhase, shimmer = shimmer)
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = "DynamicLyrics",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 25.sp,
                letterSpacing = (-0.6).sp,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Live Island • Sync Lyrics",
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = (-0.1).sp,
                color = Color.White.copy(alpha = 0.58f)
            )
        }
    }
}

// ------------------------------------------------------------
// 7. Schermata Avvio / Permessi in Vero Liquid Glass (Single-Shader)
// ------------------------------------------------------------
@Composable
fun LiquidPermissionsScreen(
    font: FontFamily,
    refreshTick: Int = 0,
    onRequestNotifications: () -> Unit = {},
    onAllReady: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val densityPx = density.density
    val shaderStatus by LiquidGlassDiagnostics.shaderStatus.collectAsState()
    val shaderSafeMode by AppPreferences.shaderSafeMode.collectAsState()
    val shaderFailed = shaderSafeMode || (shaderStatus != "AGSL OK" && shaderStatus != "AGSL non inizializzato")

    val appInfoDone by AppPreferences.appInfoStepDone.collectAsState()
    var postNotifOk by remember {
        mutableStateOf(BitChordListenerService.areNotificationsEnabled(context))
    }
    var listenerGranted by remember {
        mutableStateOf(BitChordListenerService.isNotificationAccessGranted(context))
    }
    var awaitingAppInfoReturn by remember { mutableStateOf(false) }

    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var badgeBounds by remember { mutableStateOf(Rect.Zero) }
    var card1Bounds by remember { mutableStateOf(Rect.Zero) }
    var card2Bounds by remember { mutableStateOf(Rect.Zero) }
    var card3Bounds by remember { mutableStateOf(Rect.Zero) }

    val card1Interaction = remember { MutableInteractionSource() }
    val card2Interaction = remember { MutableInteractionSource() }
    val card3Interaction = remember { MutableInteractionSource() }
    val card1Pressed by card1Interaction.collectIsPressedAsState()
    val card2Pressed by card2Interaction.collectIsPressedAsState()
    val card3Pressed by card3Interaction.collectIsPressedAsState()

    val style1 by animateFloatAsState(
        targetValue = if (card1Pressed) 1.5f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "permStyle1"
    )
    val style2 by animateFloatAsState(
        targetValue = if (card2Pressed) 1.5f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "permStyle2"
    )
    val style3 by animateFloatAsState(
        targetValue = if (card3Pressed) 1.5f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "permStyle3"
    )

    val badgeRadiusPx = with(density) { 24.dp.toPx() }
    val cardRadiusPx = with(density) { 28.dp.toPx() }

    val shape0 = if (badgeBounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = badgeBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = badgeRadiusPx,
        style = 1.0f
    )
    val shape1 = if (card1Bounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = card1Bounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = cardRadiusPx,
        style = style1
    )
    val shape2 = if (card2Bounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = card2Bounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = cardRadiusPx,
        style = style2
    )
    val shape3 = if (card3Bounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = card3Bounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = cardRadiusPx,
        style = style3
    )

    val permRenderEffect = rememberLiquidGlassRenderEffect(
        shape0 = shape0,
        shape1 = shape1,
        shape2 = shape2,
        shape3 = shape3,
        densityPx = densityPx
    )

    fun recheck() {
        val pOk = BitChordListenerService.areNotificationsEnabled(context)
        val lOk = BitChordListenerService.isNotificationAccessGranted(context)
        postNotifOk = pOk
        listenerGranted = lOk
        if (pOk && lOk) {
            BitChordListenerService.ensureConnected(context, forceRepublish = true)
            onAllReady()
        }
    }

    LaunchedEffect(refreshTick) { recheck() }

    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (awaitingAppInfoReturn) {
                    awaitingAppInfoReturn = false
                    AppPreferences.markAppInfoDone(context, true)
                }
                recheck()
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootTopLeft = it.boundsInRoot().topLeft }
    ) {
        // Sfondo full-bleed filtrato dal RuntimeShader Liquid Glass (rifrazione reale su Badge + 3 Card)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { renderEffect = permRenderEffect }
        ) {
            LiquidAmbientBackground(cover = null, palette = CoverPalette())
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(76.dp)
                    .reportBoundsInRoot { badgeBounds = it }
                    .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(24.dp), tintAlpha = 0.36f)
                    .padding(4.dp)
            ) {
                DynamicLyricsBubbleGlyph()
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "DynamicLyrics",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                letterSpacing = (-0.7).sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = GlassTextShadowStyle
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Attiva i permessi per avviare la pillola Live Island.",
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = (-0.2).sp,
                color = Color.White.copy(alpha = 0.80f),
                textAlign = TextAlign.Center,
                style = GlassTextShadowStyle
            )

            Spacer(Modifier.height(28.dp))

            PermissionLiquidGlassCard(
                stepNumber = "1",
                title = "Notifiche & Pillola",
                subtitle = "Consenti notifiche e aggiornamenti live per mostrare la pillola.",
                buttonLabel = if (postNotifOk) "Attivo" else "Consenti",
                completed = postNotifOk,
                pressed = card1Pressed,
                interactionSource = card1Interaction,
                shaderFailed = shaderFailed,
                font = font,
                onBounds = { card1Bounds = it },
                onClick = onRequestNotifications
            )

            Spacer(Modifier.height(14.dp))

            PermissionLiquidGlassCard(
                stepNumber = "2",
                title = "Info App",
                subtitle = "Consenti impostazioni con restrizioni e nessuna limitazione batteria.",
                buttonLabel = if (appInfoDone) "Riapri" else "Apri",
                completed = appInfoDone,
                pressed = card2Pressed,
                interactionSource = card2Interaction,
                shaderFailed = shaderFailed,
                font = font,
                onBounds = { card2Bounds = it },
                onClick = {
                    awaitingAppInfoReturn = true
                    openAppInfoSettings(context)
                }
            )

            Spacer(Modifier.height(14.dp))

            PermissionLiquidGlassCard(
                stepNumber = "3",
                title = "Accesso alle notifiche",
                subtitle = "Legge il brano in riproduzione e sincronizza i testi in tempo reale.",
                buttonLabel = if (listenerGranted) "Attivo" else "Attiva",
                completed = listenerGranted,
                pressed = card3Pressed,
                interactionSource = card3Interaction,
                shaderFailed = shaderFailed,
                font = font,
                onBounds = { card3Bounds = it },
                onClick = { openNotificationListenerSettings(context) }
            )
        }
    }
}

@Composable
private fun PermissionLiquidGlassCard(
    stepNumber: String,
    title: String,
    subtitle: String,
    buttonLabel: String,
    completed: Boolean,
    pressed: Boolean,
    interactionSource: MutableInteractionSource,
    shaderFailed: Boolean,
    font: FontFamily,
    onBounds: (Rect) -> Unit,
    onClick: () -> Unit
) {
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
        label = "permCardScale"
    )
    val pillScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.60f, stiffness = 560f),
        label = "permPillScale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .reportBoundsInRoot(onBounds)
            .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(28.dp), tintAlpha = 0.38f)
            .clip(RoundedCornerShape(28.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (completed) Color(0xFF22C55E).copy(alpha = 0.24f)
                    else Color.White.copy(alpha = 0.10f)
                )
                .border(
                    width = 0.8.dp,
                    color = if (completed) Color(0xFF86EFAC).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.34f),
                    shape = CircleShape
                )
        ) {
            if (completed) {
                CheckIcon(color = Color(0xFF4ADE80), size = 18.dp)
            } else {
                Text(
                    text = stepNumber,
                    fontFamily = font,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White,
                    style = GlassTextShadowStyle
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = (-0.3).sp,
                color = Color.White,
                style = GlassTextShadowStyle
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontFamily = font,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color.White.copy(alpha = 0.84f),
                style = GlassTextShadowStyle
            )
        }

        Spacer(Modifier.width(10.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 86.dp, height = 38.dp)
                .graphicsLayer {
                    scaleX = pillScale
                    scaleY = pillScale
                }
                .iosGlassButton(
                    shape = RoundedCornerShape(50),
                    elevated = !completed,
                    pressed = pressed,
                    tintColor = if (completed) Color(0xFF22C55E).copy(alpha = 0.26f) else Color.Transparent
                )
        ) {
            Text(
                text = buttonLabel,
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = (-0.2).sp,
                color = if (completed) Color(0xFF86EFAC) else Color.White,
                style = GlassTextShadowStyle
            )
        }
    }
}

// ------------------------------------------------------------
// 8. Player Lyrics iOS 26 (Capsule Staccate + Maschera StatusBar DstIn)
// ------------------------------------------------------------
private fun findLiquidLineIndex(lines: List<LyricLine>, posMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var ans = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startMs <= posMs) {
            ans = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return ans
}

@Composable
fun LiquidGlassLyricsPlayerScreen(
    font: FontFamily,
    showCloseButton: Boolean = false,
    onClose: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val densityPx = density.density
    val shaderStatus by LiquidGlassDiagnostics.shaderStatus.collectAsState()
    val shaderSafeMode by AppPreferences.shaderSafeMode.collectAsState()
    val shaderFailed = shaderSafeMode || (shaderStatus != "AGSL OK" && shaderStatus != "AGSL non inizializzato")

    val title by LyricsPlaybackStore.title.collectAsState()
    val artist by LyricsPlaybackStore.artist.collectAsState()
    val cover by LyricsPlaybackStore.cover.collectAsState()
    val palette by LyricsPlaybackStore.palette.collectAsState()
    val lines by LyricsPlaybackStore.lines.collectAsState()
    val isPlaying by LyricsPlaybackStore.isPlaying.collectAsState()
    val durationMs by LyricsPlaybackStore.durationMs.collectAsState()
    val syncOffsetMs by LyricsPlaybackStore.syncOffsetMs.collectAsState()
    val basePositionMs by LyricsPlaybackStore.basePositionMs.collectAsState()
    val preferredPlayer by AppPreferences.preferredPlayer.collectAsState()
    val targetFps by AppPreferences.targetFps.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }

    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var topBarBounds by remember { mutableStateOf(Rect.Zero) }
    var hamburgerBounds by remember { mutableStateOf(Rect.Zero) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }
    var minusBounds by remember { mutableStateOf(Rect.Zero) }
    var plusBounds by remember { mutableStateOf(Rect.Zero) }
    var ctaBounds by remember { mutableStateOf(Rect.Zero) }
    var popoverBounds by remember { mutableStateOf(Rect.Zero) }
    var settingsBounds by remember { mutableStateOf(Rect.Zero) }

    val menuInteraction = remember { MutableInteractionSource() }
    val minusInteraction = remember { MutableInteractionSource() }
    val plusInteraction = remember { MutableInteractionSource() }
    val prevInteraction = remember { MutableInteractionSource() }
    val playInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val closeInteraction = remember { MutableInteractionSource() }
    val ctaInteraction = remember { MutableInteractionSource() }

    val menuPressed by menuInteraction.collectIsPressedAsState()
    val minusPressed by minusInteraction.collectIsPressedAsState()
    val plusPressed by plusInteraction.collectIsPressedAsState()
    val prevPressed by prevInteraction.collectIsPressedAsState()
    val playPressed by playInteraction.collectIsPressedAsState()
    val nextPressed by nextInteraction.collectIsPressedAsState()
    val closePressed by closeInteraction.collectIsPressedAsState()
    val ctaPressed by ctaInteraction.collectIsPressedAsState()
    var scrubberDragging by remember { mutableStateOf(false) }

    LaunchedEffect(menuExpanded) {
        if (!menuExpanded) popoverBounds = Rect.Zero
    }
    LaunchedEffect(showSettingsSheet) {
        if (showSettingsSheet) {
            topBarBounds = Rect.Zero
            hamburgerBounds = Rect.Zero
            dockBounds = Rect.Zero
            minusBounds = Rect.Zero
            plusBounds = Rect.Zero
            ctaBounds = Rect.Zero
        } else {
            settingsBounds = Rect.Zero
        }
    }

    val hamburgerStyle by animateFloatAsState(
        targetValue = if (menuPressed || menuExpanded) 1.5f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
        label = "hamburgerStyle"
    )
    val dockStyle by animateFloatAsState(
        targetValue = if (scrubberDragging || prevPressed || playPressed || nextPressed) 1.45f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "dockStyle"
    )
    val minusStyle by animateFloatAsState(
        targetValue = if (minusPressed) 1.5f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 540f),
        label = "minusStyle"
    )
    val plusStyle by animateFloatAsState(
        targetValue = if (plusPressed) 1.5f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 540f),
        label = "plusStyle"
    )
    val ctaStyle by animateFloatAsState(
        targetValue = if (ctaPressed) 1.5f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
        label = "ctaStyle"
    )
    val topBarStyle by animateFloatAsState(
        targetValue = if (closePressed) 1.35f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
        label = "topBarStyle"
    )

    val topRadiusPx = with(density) { 27.dp.toPx() }
    val circleRadiusPx = with(density) { 26.dp.toPx() }
    val dockRadiusPx = with(density) { 28.dp.toPx() }
    val pillRadiusPx = with(density) { 29.dp.toPx() }
    val ctaRadiusPx = with(density) { 25.dp.toPx() }
    val popoverRadiusPx = with(density) { 26.dp.toPx() }
    val modalRadiusPx = with(density) { 36.dp.toPx() }

    val shape0 = if (showSettingsSheet || topBarBounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = topBarBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = topRadiusPx,
        style = topBarStyle
    )
    val shape1 = if (showSettingsSheet || hamburgerBounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = hamburgerBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = circleRadiusPx,
        style = hamburgerStyle
    )
    val shape2 = if (showSettingsSheet || dockBounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = dockBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = dockRadiusPx,
        style = dockStyle
    )
    val shape3 = if (showSettingsSheet || minusBounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = minusBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = pillRadiusPx,
        style = minusStyle
    )
    val shape4 = if (showSettingsSheet || plusBounds.isEmpty) GlassShapeSpec() else GlassShapeSpec(
        rect = plusBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
        cornerRadiusPx = pillRadiusPx,
        style = plusStyle
    )
    val showCta = !showSettingsSheet && !menuExpanded && lines.isEmpty() && title.isBlank()
    val shape5 = when {
        showSettingsSheet && !settingsBounds.isEmpty -> GlassShapeSpec(
            rect = settingsBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
            cornerRadiusPx = modalRadiusPx,
            style = 3.0f
        )
        !showSettingsSheet && menuExpanded && !popoverBounds.isEmpty -> GlassShapeSpec(
            rect = popoverBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
            cornerRadiusPx = popoverRadiusPx,
            style = 2.0f
        )
        showCta && !ctaBounds.isEmpty -> GlassShapeSpec(
            rect = ctaBounds.translate(-rootTopLeft.x, -rootTopLeft.y),
            cornerRadiusPx = ctaRadiusPx,
            style = ctaStyle
        )
        else -> GlassShapeSpec()
    }

    val liquidRenderEffect = rememberLiquidGlassRenderEffect(
        shape0 = shape0,
        shape1 = shape1,
        shape2 = shape2,
        shape3 = shape3,
        shape4 = shape4,
        shape5 = shape5,
        densityPx = densityPx
    )

    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                BitChordListenerService.ensureConnected(context, forceRepublish = true)
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(statusBannerText) {
        if (statusBannerText != null) {
            delay(2600L)
            statusBannerText = null
        }
    }

    val coverBitmap = remember(cover) {
        cover?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
    }

    val currentPosState = remember { mutableLongStateOf(LyricsPlaybackStore.currentPositionMs()) }
    val rawPosState = remember { mutableLongStateOf(LyricsPlaybackStore.rawPositionMs()) }
    var activeIndex by remember(lines) { mutableIntStateOf(-1) }
    var forceNextLineSync by remember { mutableStateOf(true) }

    LaunchedEffect(syncOffsetMs) { forceNextLineSync = true }

    // Pacing esatto per word-sync e progress in base a 30 / 60 / 120 FPS
    LaunchedEffect(isPlaying, syncOffsetMs, basePositionMs, lines, targetFps) {
        val activeFrameIntervalMs = when (targetFps) {
            TargetFps.FPS_30 -> 33L
            TargetFps.FPS_60 -> 16L
            TargetFps.FPS_120 -> 8L
        }
        val lineOnlyIntervalMs = when (targetFps) {
            TargetFps.FPS_30 -> 160L
            TargetFps.FPS_60 -> 100L
            TargetFps.FPS_120 -> 60L
        }

        var nextTickAt = SystemClock.uptimeMillis()
        while (true) {
            val pos = LyricsPlaybackStore.currentPositionMs()
            val raw = LyricsPlaybackStore.rawPositionMs()
            currentPosState.longValue = pos
            rawPosState.longValue = raw

            if (lines.isEmpty()) {
                activeIndex = -1
            } else {
                var idx = findLiquidLineIndex(lines, pos)
                if (isPlaying && !forceNextLineSync && activeIndex in lines.indices && idx < activeIndex) {
                    val lagMs = lines[activeIndex].startMs - pos
                    if (lagMs in 0L..1500L) {
                        idx = activeIndex
                    }
                }
                forceNextLineSync = false
                if (idx != activeIndex) {
                    activeIndex = idx
                }
            }

            if (!isPlaying) break
            val hasWordSync = activeIndex in lines.indices && lines[activeIndex].words.isNotEmpty()
            val stepMs = if (hasWordSync) activeFrameIntervalMs else lineOnlyIntervalMs
            nextTickAt += stepMs
            val now = SystemClock.uptimeMillis()
            if (nextTickAt <= now) {
                nextTickAt = now + stepMs
            }
            delay((nextTickAt - now).coerceAtLeast(1L))
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    val dimAlpha by animateFloatAsState(
        targetValue = if (showSettingsSheet) 0.24f else 0f,
        animationSpec = tween(180),
        label = "sheetDim"
    )

    val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootTopLeft = it.boundsInRoot().topLeft }
    ) {
        // STRATO 0 UNICO: Sfondo + Testi + Dim morbido filtrati da ONE RuntimeShader Liquid Glass
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { renderEffect = liquidRenderEffect }
        ) {
            LiquidAmbientBackground(cover = cover, palette = palette)

            if (lines.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = statusBarTopDp + 80.dp,
                            bottom = navBarBottomDp + 132.dp,
                            start = 32.dp,
                            end = 32.dp
                        )
                ) {
                    Text(
                        text = if (title.isBlank()) "Nessun brano attivo" else "Nessun testo sincronizzato",
                        fontFamily = font,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        letterSpacing = (-0.5).sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (title.isBlank()) {
                            "Avvia ${if (preferredPlayer == PreferredPlayer.AUTO) "il tuo player" else preferredPlayer.label} per visualizzare i testi in tempo reale"
                        } else {
                            "Nessun testo sincronizzato trovato per questo brano"
                        },
                        fontFamily = font,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        letterSpacing = (-0.2).sp,
                        color = Color.White.copy(alpha = 0.74f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Maschera Offscreen + BlendMode.DstIn: i testi sfumano prima della status bar
                // restando nitidi e visibili dietro Top Bar e Dock inferiore
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            val h = size.height.coerceAtLeast(1f)
                            val statusTopPx = statusBarTopDp.toPx()
                            val topZeroY = (statusTopPx * 0.62f).coerceAtLeast(1f)
                            val topFullY = (statusTopPx + 14.dp.toPx()).coerceAtLeast(topZeroY + 4f)
                            val topStartStop = (topZeroY / h).coerceIn(0.001f, 0.22f)
                            val topEndStop = (topFullY / h).coerceIn(topStartStop + 0.005f, 0.28f)

                            val navBotPx = navBarBottomDp.toPx()
                            val botFullY = (h - navBotPx - 12.dp.toPx()).coerceAtLeast(topFullY + 20f)
                            val botZeroY = (h - navBotPx * 0.35f).coerceAtLeast(botFullY + 4f)
                            val botStartStop = (botFullY / h).coerceIn(topEndStop + 0.05f, 0.98f)
                            val botEndStop = (botZeroY / h).coerceIn(botStartStop + 0.005f, 0.999f)

                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    topStartStop to Color.Transparent,
                                    topEndStop to Color.Black,
                                    botStartStop to Color.Black,
                                    botEndStop to Color.Transparent,
                                    1.0f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = statusBarTopDp + 94.dp,
                            bottom = navBarBottomDp + 142.dp,
                            start = 24.dp,
                            end = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        itemsIndexed(
                            items = lines,
                            key = { idx, line -> "${idx}_${line.startMs}" }
                        ) { idx, line ->
                            LiquidLyricLineItem(
                                line = line,
                                isActive = idx == activeIndex,
                                font = font,
                                currentPosProvider = { currentPosState.longValue },
                                onClick = {
                                    forceNextLineSync = true
                                    activeIndex = idx
                                    LyricsPlaybackStore.onSeekTo?.invoke(
                                        (line.startMs - syncOffsetMs).coerceAtLeast(0L)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            if (dimAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dimAlpha))
                )
            }
        }

        // CTA AVVIO RAPIDO LIQUID GLASS QUANDO NON C'È MUSICA ATTIVA
        if (showCta) {
            val launchLabel = if (preferredPlayer == PreferredPlayer.AUTO) {
                "Avvia Player"
            } else {
                "Avvia ${preferredPlayer.label}"
            }
            val ctaScale by animateFloatAsState(
                targetValue = if (ctaPressed) 0.93f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 540f),
                label = "ctaScale"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 110.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(50.dp)
                        .graphicsLayer {
                            scaleX = ctaScale
                            scaleY = ctaScale
                        }
                        .reportBoundsInRoot { ctaBounds = it }
                        .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(50), tintAlpha = 0.42f)
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = ctaInteraction,
                            indication = null,
                            onClick = { BitChordListenerService.triggerPlayPreferred(context) }
                        )
                        .padding(horizontal = 22.dp)
                ) {
                    PlayIcon(size = 16.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = launchLabel,
                        fontFamily = font,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        letterSpacing = (-0.2).sp,
                        color = Color.White,
                        style = GlassTextShadowStyle
                    )
                }
            }
        }

        // STRATO 1: TOP BAR + HAMBURGER STACCATI (nascosti quando Impostazioni è aperto)
        if (!showSettingsSheet) {
            val hamburgerScale by animateFloatAsState(
                targetValue = if (menuPressed) 0.91f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
                label = "hamburgerScale"
            )
            val closeScale by animateFloatAsState(
                targetValue = if (closePressed) 0.90f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
                label = "closeScale"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                // rect0: Top Bar Capsule
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .reportBoundsInRoot { topBarBounds = it }
                        .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(50), tintAlpha = 0.36f)
                        .padding(horizontal = 8.dp)
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .border(0.75.dp, Color.White.copy(alpha = 0.52f), CircleShape)
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .iosGlassButton(CircleShape, elevated = false)
                        ) {
                            MiniWaveIcon(size = 17.dp)
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.ifBlank { "DynamicLyrics" },
                            fontFamily = font,
                            color = Color.White,
                            fontSize = 14.sp,
                            letterSpacing = (-0.35).sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = GlassTextShadowStyle
                        )
                        Text(
                            text = artist.ifBlank { "Player: ${preferredPlayer.label}" },
                            fontFamily = font,
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 11.5.sp,
                            letterSpacing = (-0.15).sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = GlassTextShadowStyle
                        )
                    }

                    if (showCloseButton && onClose != null) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .graphicsLayer {
                                    scaleX = closeScale
                                    scaleY = closeScale
                                }
                                .iosGlassButton(CircleShape, elevated = false, pressed = closePressed)
                                .clickable(
                                    interactionSource = closeInteraction,
                                    indication = null,
                                    onClick = onClose
                                )
                        ) {
                            CloseIcon(size = 13.dp)
                        }
                    } else {
                        Spacer(Modifier.width(6.dp))
                    }
                }

                Spacer(Modifier.width(10.dp))

                // rect1: Cerchio Menu Hamburger (52dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .graphicsLayer {
                            scaleX = hamburgerScale
                            scaleY = hamburgerScale
                        }
                        .reportBoundsInRoot { hamburgerBounds = it }
                        .iosGlassFallbackSurface(shaderFailed, CircleShape, tintAlpha = 0.36f)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = menuInteraction,
                            indication = null,
                            onClick = { menuExpanded = !menuExpanded }
                        )
                ) {
                    HamburgerIcon(size = 18.dp)
                }
            }

            // STRATO 2: BOTTOM DOCK (rect3: -0.5s | rect2: Scrubber + Sync + Prev/Play/Next | rect4: +0.5s)
            val minusScale by animateFloatAsState(
                targetValue = if (minusPressed) 0.91f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
                label = "minusScale"
            )
            val plusScale by animateFloatAsState(
                targetValue = if (plusPressed) 0.91f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
                label = "plusScale"
            )
            val prevScale by animateFloatAsState(
                targetValue = if (prevPressed) 0.89f else 1f,
                animationSpec = spring(dampingRatio = 0.60f, stiffness = 560f),
                label = "prevScale"
            )
            val playScale by animateFloatAsState(
                targetValue = if (playPressed) 0.91f else 1f,
                animationSpec = spring(dampingRatio = 0.60f, stiffness = 560f),
                label = "playScale"
            )
            val nextScale by animateFloatAsState(
                targetValue = if (nextPressed) 0.89f else 1f,
                animationSpec = spring(dampingRatio = 0.60f, stiffness = 560f),
                label = "nextScale"
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // rect3: Pillola staccata -0.5s
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 62.dp, height = 58.dp)
                        .graphicsLayer {
                            scaleX = minusScale
                            scaleY = minusScale
                        }
                        .reportBoundsInRoot { minusBounds = it }
                        .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(29.dp), tintAlpha = 0.36f)
                        .clip(RoundedCornerShape(29.dp))
                        .clickable(
                            interactionSource = minusInteraction,
                            indication = null,
                            onClick = { LyricsPlaybackStore.syncOffsetMs.value -= 500L }
                        )
                ) {
                    Text(
                        text = "-0.5s",
                        fontFamily = font,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        letterSpacing = (-0.2).sp,
                        color = Color.White,
                        style = GlassTextShadowStyle
                    )
                }

                Spacer(Modifier.width(10.dp))

                // rect2: Capsula Centrale Unificata (Scrubber + Sync chip + Prev / Play-Pause / Next)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .reportBoundsInRoot { dockBounds = it }
                        .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(28.dp), tintAlpha = 0.36f)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LiquidEmbeddedScrubberRow(
                        durationMs = durationMs,
                        syncOffsetMs = syncOffsetMs,
                        font = font,
                        rawPosProvider = { rawPosState.longValue },
                        onDragChange = { scrubberDragging = it },
                        onResetSync = {
                            val defOffset = AppPreferences.defaultSyncOffsetMs.value
                            LyricsPlaybackStore.syncOffsetMs.value = defOffset
                            val s = if (defOffset >= 0L) "+" else ""
                            statusBannerText = String.format(Locale.US, "Sync ripristinato %s%.1fs", s, defOffset / 1000f)
                        }
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .graphicsLayer {
                                    scaleX = prevScale
                                    scaleY = prevScale
                                }
                                .iosGlassButton(CircleShape, elevated = false, pressed = prevPressed)
                                .clickable(
                                    interactionSource = prevInteraction,
                                    indication = null,
                                    onClick = { LyricsPlaybackStore.onPrevious?.invoke() }
                                )
                        ) {
                            SkipPrevIcon(size = 17.dp)
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = 60.dp, height = 44.dp)
                                .graphicsLayer {
                                    scaleX = playScale
                                    scaleY = playScale
                                }
                                .iosGlassButton(RoundedCornerShape(50), elevated = true, pressed = playPressed)
                                .clickable(
                                    interactionSource = playInteraction,
                                    indication = null,
                                    onClick = {
                                        val handler = LyricsPlaybackStore.onPlayPause
                                        if (handler != null) {
                                            handler.invoke()
                                        } else {
                                            BitChordListenerService.triggerPlayPreferred(context)
                                        }
                                    }
                                )
                        ) {
                            if (isPlaying) PauseIcon(size = 21.dp) else PlayIcon(size = 21.dp)
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .graphicsLayer {
                                    scaleX = nextScale
                                    scaleY = nextScale
                                }
                                .iosGlassButton(CircleShape, elevated = false, pressed = nextPressed)
                                .clickable(
                                    interactionSource = nextInteraction,
                                    indication = null,
                                    onClick = { LyricsPlaybackStore.onNext?.invoke() }
                                )
                        ) {
                            SkipNextIcon(size = 17.dp)
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                // rect4: Pillola staccata +0.5s
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 62.dp, height = 58.dp)
                        .graphicsLayer {
                            scaleX = plusScale
                            scaleY = plusScale
                        }
                        .reportBoundsInRoot { plusBounds = it }
                        .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(29.dp), tintAlpha = 0.36f)
                        .clip(RoundedCornerShape(29.dp))
                        .clickable(
                            interactionSource = plusInteraction,
                            indication = null,
                            onClick = { LyricsPlaybackStore.syncOffsetMs.value += 500L }
                        )
                ) {
                    Text(
                        text = "+0.5s",
                        fontFamily = font,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        letterSpacing = (-0.2).sp,
                        color = Color.White,
                        style = GlassTextShadowStyle
                    )
                }
            }
        }

        // STRATO 3: POPOVER MENU HAMBURGER (rect5 quando aperto)
        if (menuExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { menuExpanded = false }
            )
        }

        AnimatedVisibility(
            visible = menuExpanded,
            enter = fadeIn(tween(150)) + scaleIn(
                initialScale = 0.88f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f)
            ),
            exit = fadeOut(tween(110)) + scaleOut(
                targetScale = 0.92f,
                transformOrigin = TransformOrigin(1f, 0f)
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 74.dp, end = 16.dp)
        ) {
            val granted = BitChordListenerService.isNotificationAccessGranted(context)
            val serviceConnected by BitChordListenerService.isServiceConnected.collectAsState()
            val activeNow = granted && serviceConnected

            Column(
                modifier = Modifier
                    .width(238.dp)
                    .reportBoundsInRoot { popoverBounds = it }
                    .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(26.dp), tintAlpha = 0.62f)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (activeNow) Color(0xFF22C55E).copy(alpha = 0.18f)
                            else Color(0xFFF59E0B).copy(alpha = 0.20f)
                        )
                        .clickable {
                            menuExpanded = false
                            val ok = BitChordListenerService.isNotificationAccessGranted(context)
                            when {
                                !ok -> openNotificationListenerSettings(context)
                                !serviceConnected -> {
                                    BitChordListenerService.forceHardRebind(context)
                                    statusBannerText = "Riaggancio forzato in corso…"
                                }
                                else -> {
                                    BitChordListenerService.ensureConnected(context, forceRepublish = true)
                                    statusBannerText = "Servizio attivo • ${targetFps.label}"
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                if (activeNow) Color(0xFF22C55E).copy(alpha = 0.28f)
                                else Color(0xFFF59E0B).copy(alpha = 0.28f)
                            )
                    ) {
                        CheckIcon(
                            color = if (activeNow) Color(0xFF4ADE80) else Color(0xFFFCD34D),
                            size = 16.dp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (activeNow) "Servizio attivo" else "Servizio scollegato",
                            fontFamily = font,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            letterSpacing = (-0.2).sp,
                            color = Color.White
                        )
                        Text(
                            text = if (activeNow) "Tocca per verificare" else "Tocca per attivare",
                            fontFamily = font,
                            fontSize = 11.sp,
                            color = if (activeNow) Color(0xFF86EFAC) else Color(0xFFFDE68A)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            menuExpanded = false
                            showSettingsSheet = true
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .iosGlassButton(CircleShape, elevated = false)
                    ) {
                        GearIcon(color = Color.White, size = 16.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Impostazioni",
                            fontFamily = font,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            letterSpacing = (-0.2).sp,
                            color = Color.White
                        )
                        Text(
                            text = "Player, FPS, offset e stato",
                            fontFamily = font,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.74f)
                        )
                    }
                }
            }
        }

        // TOAST DI CONFERMA
        AnimatedVisibility(
            visible = statusBannerText != null,
            enter = fadeIn(tween(180)) + slideInVertically { -it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 74.dp, start = 24.dp, end = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF0A261D).copy(alpha = 0.74f))
                    .iosGlassButton(RoundedCornerShape(50), elevated = true)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                CheckIcon(color = Color(0xFF4ADE80), size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusBannerText.orEmpty(),
                    fontFamily = font,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.White
                )
            }
        }

        // PANNELLO IMPOSTAZIONI & VERIFICA STATO LIQUID GLASS (rect5 quando aperto)
        AnimatedVisibility(
            visible = showSettingsSheet,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize()
        ) {
            LiquidSettingsModal(
                font = font,
                shaderFailed = shaderFailed,
                onModalBounds = { settingsBounds = it },
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

@Composable
private fun LiquidEmbeddedScrubberRow(
    durationMs: Long,
    syncOffsetMs: Long,
    font: FontFamily,
    rawPosProvider: () -> Long,
    onDragChange: (Boolean) -> Unit,
    onResetSync: () -> Unit
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val rawPosMs = rawPosProvider()
    val liveFraction = (rawPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val shownFraction = if (isDragging) dragFraction else liveFraction
    val shownTimeMs = if (isDragging) (dragFraction * safeDuration).toLong() else rawPosMs

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 2.dp)
    ) {
        Text(
            text = formatPlayerTime(shownTimeMs),
            fontFamily = font,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 10.5.sp,
            style = GlassTextShadowStyle
        )

        Spacer(Modifier.width(8.dp))

        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(safeDuration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        isDragging = true
                        onDragChange(true)
                        dragFraction = (down.position.x / w).coerceIn(0f, 1f)

                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                            if (change == null || !change.pressed) {
                                change?.consume()
                                break
                            }
                            pointerId = change.id
                            if (change.positionChange() != Offset.Zero) {
                                change.consume()
                            }
                            dragFraction = (change.position.x / w).coerceIn(0f, 1f)
                        }

                        val targetSeekMs = (dragFraction * safeDuration).toLong().coerceIn(0L, safeDuration)
                        isDragging = false
                        onDragChange(false)
                        LyricsPlaybackStore.onSeekTo?.invoke(targetSeekMs)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
                val trackH = if (isDragging) 6.dp.toPx() else 3.8.dp.toPx()
                val cy = size.height / 2f
                val corner = CornerRadius(trackH / 2f, trackH / 2f)

                drawRoundRect(
                    color = Color.White.copy(alpha = 0.24f),
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = corner
                )
                val activeW = (size.width * shownFraction).coerceIn(0f, size.width)
                if (activeW > 0.5f) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(0f, cy - trackH / 2f),
                        size = Size(activeW, trackH),
                        cornerRadius = corner
                    )
                }
                val thumbR = if (isDragging) 7.dp.toPx() else 4.dp.toPx()
                val thumbCenter = Offset(
                    activeW.coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR)),
                    cy
                )
                if (isDragging) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.28f),
                        radius = thumbR * 1.65f,
                        center = thumbCenter
                    )
                }
                drawCircle(
                    color = Color.White,
                    radius = thumbR,
                    center = thumbCenter
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        val sign = if (syncOffsetMs >= 0L) "+" else ""
        Text(
            text = String.format(Locale.US, "Sync %s%.1fs", sign, syncOffsetMs / 1000f),
            fontFamily = font,
            color = Color.White.copy(alpha = 0.94f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            style = GlassTextShadowStyle,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onResetSync)
                .padding(horizontal = 7.dp, vertical = 3.dp)
        )

        Spacer(Modifier.width(6.dp))

        Text(
            text = formatPlayerTime(durationMs),
            fontFamily = font,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 10.5.sp,
            style = GlassTextShadowStyle
        )
    }
}

@Composable
private fun LiquidLyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    font: FontFamily,
    currentPosProvider: () -> Long,
    onClick: () -> Unit
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.34f,
        animationSpec = tween(220),
        label = "lyricAlpha"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (isActive) 1.03f else 0.97f,
        animationSpec = tween(220),
        label = "lyricScale"
    )

    val rowModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            alpha = animatedAlpha
            scaleX = animatedScale
            scaleY = animatedScale
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )

    if (isActive && line.words.isNotEmpty()) {
        val posMs = currentPosProvider()
        val annotated = buildAnnotatedString {
            line.words.forEach { w ->
                val spoken = posMs >= w.startMs
                withStyle(
                    SpanStyle(
                        color = if (spoken) Color.White else Color.White.copy(alpha = 0.44f),
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(w.text)
                }
            }
        }
        Text(
            text = annotated,
            fontFamily = font,
            color = Color.White,
            fontSize = 27.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.6).sp,
            fontWeight = FontWeight.Bold,
            modifier = rowModifier
        )
    } else {
        Text(
            text = line.fullText,
            fontFamily = font,
            color = Color.White,
            fontSize = if (isActive) 27.sp else 21.sp,
            lineHeight = if (isActive) 34.sp else 28.sp,
            letterSpacing = if (isActive) (-0.6).sp else (-0.35).sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            modifier = rowModifier
        )
    }
}

// ------------------------------------------------------------
// 9. Modale Impostazioni & Verifica Stato (con Diagnostica Shader & FPS Reali)
// ------------------------------------------------------------
@Composable
private fun LiquidSettingsModal(
    font: FontFamily,
    shaderFailed: Boolean,
    onModalBounds: (Rect) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val composeView = LocalView.current
    val preferredPlayer by AppPreferences.preferredPlayer.collectAsState()
    val defaultOffsetMs by AppPreferences.defaultSyncOffsetMs.collectAsState()
    val targetFps by AppPreferences.targetFps.collectAsState()
    val activePkg by LyricsPlaybackStore.activePlayerPackage.collectAsState()
    val lastPkg by AppPreferences.lastActiveMusicPackage.collectAsState()
    val shaderStatus by LiquidGlassDiagnostics.shaderStatus.collectAsState()
    val shaderSafeMode by AppPreferences.shaderSafeMode.collectAsState()
    val lastCrash by AppPreferences.lastUncaughtCrash.collectAsState()

    val notifGranted = BitChordListenerService.isNotificationAccessGranted(context)
    val postNotifOk = BitChordListenerService.areNotificationsEnabled(context)
    val serviceAlive by BitChordListenerService.isServiceConnected.collectAsState()
    val accConnected by BitChordQualityAccessibilityService.isServiceConnected.collectAsState()
    var resumeTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                resumeTrigger++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }
    val bitChordAccessibilityOk = remember(accConnected, resumeTrigger) {
        accConnected || BitChordQualityAccessibilityService.isAccessibilityServiceEnabled(context)
    }
    val (appFps, displayHz) = rememberFrameRateTelemetry(targetFps)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .reportBoundsInRoot(onModalBounds)
                .iosGlassFallbackSurface(shaderFailed, RoundedCornerShape(36.dp), tintAlpha = 0.72f)
                .clip(RoundedCornerShape(36.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.42f))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Impostazioni & Stato",
                    fontFamily = font,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = (-0.5).sp,
                    color = Color.White
                )
                GlassCircleIconButton(
                    size = 34.dp,
                    elevated = false,
                    onClick = onDismiss
                ) {
                    CloseIcon(size = 13.dp)
                }
            }

            // Safe Mode automatico dopo eventuale crash nativo Skia / AGSL
            if (shaderSafeMode) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.22f))
                        .border(0.8.dp, Color(0xFFFBBF24).copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Shader AGSL disattivato automaticamente dopo crash nativo",
                        fontFamily = font,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFDE68A)
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassPillTextButton(
                        label = "Riattiva Shader AGSL",
                        font = font,
                        onClick = {
                            LiquidGlassDiagnostics.shaderFrameVerified = false
                            AppPreferences.setShaderSafeMode(context, false)
                        }
                    )
                }
            } else if (shaderFailed) {
                // Avviso visibile rosso se RuntimeShader o RenderEffect fallisce via eccezione Kotlin
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Shader Liquid Glass non attivo: $shaderStatus",
                    fontFamily = font,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFCA5A5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.24f))
                        .border(0.8.dp, Color(0xFFF87171).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Eventuale crash non gestito catturato all'avvio precedente
            if (lastCrash.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Ultimo crash rilevato (tocca per pulire):\n$lastCrash",
                    fontFamily = font,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFFFEE2E2),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFDC2626).copy(alpha = 0.28f))
                        .border(0.8.dp, Color(0xFFF87171).copy(alpha = 0.60f), RoundedCornerShape(14.dp))
                        .clickable { AppPreferences.clearLastCrash(context) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            // SEZIONE 1: VERIFICA SISTEMA
            Text(
                text = "VERIFICA SISTEMA",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.62f)
            )
            Spacer(Modifier.height(10.dp))

            DiagnosticRow(
                label = "Notifiche & Pillola",
                status = if (postNotifOk) "Attivo" else "Richiesto",
                ok = postNotifOk,
                font = font,
                onClick = { openAppNotificationSettings(context) }
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticRow(
                label = "Accesso alle notifiche",
                status = if (notifGranted) "Attivo" else "Richiesto",
                ok = notifGranted,
                font = font,
                onClick = { openNotificationListenerSettings(context) }
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticRow(
                label = "Qualità reale BitChord",
                status = if (bitChordAccessibilityOk) "Attivo" else "Non attivo",
                ok = bitChordAccessibilityOk,
                font = font,
                onClick = { openAccessibilitySettings(context) }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Attiva DynamicLyrics per leggere Bit e kHz reali da BitChord",
                fontFamily = font,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.68f),
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticRow(
                label = "Servizio DynamicLyrics",
                status = if (serviceAlive) "Attivo" else "Riaggancia",
                ok = serviceAlive,
                font = font,
                onClick = {
                    if (serviceAlive) {
                        BitChordListenerService.ensureConnected(context, forceRepublish = true)
                    } else {
                        BitChordListenerService.forceHardRebind(context)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticRow(
                label = "Info App & Batteria",
                status = "Apri impostazioni",
                ok = AppPreferences.appInfoStepDone.collectAsState().value,
                font = font,
                onClick = { openAppInfoSettings(context) }
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticRow(
                label = "Player rilevato",
                status = activePkg.ifBlank { lastPkg.ifBlank { "In attesa di musica" } },
                ok = activePkg.isNotBlank() || lastPkg.isNotBlank(),
                font = font,
                onClick = { BitChordListenerService.ensureConnected(context, forceRepublish = true) }
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticRow(
                label = "Motore Liquid Glass",
                status = if (shaderSafeMode) "Safe Mode (tocca per riattivare)" else shaderStatus,
                ok = !shaderSafeMode && shaderStatus == "AGSL OK",
                font = font,
                onClick = {
                    if (shaderSafeMode) {
                        LiquidGlassDiagnostics.shaderFrameVerified = false
                        AppPreferences.setShaderSafeMode(context, false)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticRow(
                label = "Log crash precedente",
                status = if (lastCrash.isBlank()) "Nessuno" else "Presente (tocca per pulire)",
                ok = lastCrash.isBlank(),
                font = font,
                onClick = { AppPreferences.clearLastCrash(context) }
            )

            Spacer(Modifier.height(22.dp))

            // SEZIONE 2: BLOCCO FRAME RATE (30 / 60 / 120 FPS)
            Text(
                text = "BLOCCO FRAME RATE (FPS)",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.62f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Predefinito: 60 FPS. Scegli 30 per massima batteria o 120 per fluidità massima.",
                fontFamily = font,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TargetFps.entries.forEach { option ->
                    val selected = option == targetFps
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val pillScale by animateFloatAsState(
                        targetValue = if (pressed) 0.94f else 1f,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 560f),
                        label = "fpsScale"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer {
                                scaleX = pillScale
                                scaleY = pillScale
                            }
                            .iosGlassButton(
                                shape = RoundedCornerShape(50),
                                elevated = selected,
                                pressed = pressed,
                                tintColor = if (selected) Color(0xFF6366F1).copy(alpha = 0.44f) else Color.Transparent
                            )
                            .clickable(
                                interactionSource = interaction,
                                indication = null
                            ) {
                                AppPreferences.setTargetFps(context, option)
                                (context as? Activity)?.let {
                                    applyFrameRateToComposeView(composeView, it, option.fps)
                                }
                            }
                            .padding(vertical = 11.dp)
                    ) {
                        Text(
                            text = option.label,
                            fontFamily = font,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            letterSpacing = (-0.2).sp,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .iosGlassButton(RoundedCornerShape(14.dp), elevated = false)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (appFps > 0 && abs(appFps - targetFps.fps) <= 6) Color(0xFF4ADE80)
                                else Color(0xFF93C5FD)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Display: ${displayHz} Hz • App: ${if (appFps > 0) "$appFps FPS" else "…"}",
                        fontFamily = font,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
                Text(
                    text = "Target ${targetFps.fps}",
                    fontFamily = font,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.74f)
                )
            }

            if (targetFps.fps < 120 && displayHz >= 115) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Suggerimento: imposta frequenza su Predefinita in HyperOS per scendere anche a 60 Hz hardware.",
                    fontFamily = font,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Color(0xFFFDE68A).copy(alpha = 0.90f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(Modifier.height(22.dp))

            // SEZIONE 3: PLAYER PREDEFINITO
            Text(
                text = "PLAYER PREDEFINITO (TASTO PLAY)",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.62f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Automatico usa sempre l'ultima app musicale avviata senza risvegliare Spotify.",
                fontFamily = font,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PreferredPlayer.entries.forEach { player ->
                    val selected = player == preferredPlayer
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val chipScale by animateFloatAsState(
                        targetValue = if (pressed) 0.94f else 1f,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 560f),
                        label = "playerChipScale"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = chipScale
                                scaleY = chipScale
                            }
                            .iosGlassButton(
                                RoundedCornerShape(50),
                                elevated = selected,
                                pressed = pressed,
                                tintColor = if (selected) Color(0xFF6366F1).copy(alpha = 0.44f) else Color.Transparent
                            )
                            .clickable(
                                interactionSource = interaction,
                                indication = null
                            ) {
                                AppPreferences.setPreferredPlayer(context, player)
                                BitChordListenerService.ensureConnected(context, forceRepublish = true)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = player.label,
                            fontFamily = font,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // SEZIONE 4: OFFSET PREDEFINITO
            Text(
                text = "OFFSET PREDEFINITO",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.62f)
            )
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .iosGlassButton(RoundedCornerShape(22.dp), elevated = false)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                GlassPillTextButton(
                    label = "-0.1s",
                    font = font,
                    onClick = { AppPreferences.setDefaultSyncOffsetMs(context, defaultOffsetMs - 100L) }
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val sign = if (defaultOffsetMs >= 0L) "+" else ""
                    Text(
                        text = String.format(Locale.US, "%s%.1fs", sign, defaultOffsetMs / 1000f),
                        fontFamily = font,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = (-0.4).sp,
                        color = Color.White
                    )
                    if (defaultOffsetMs != AppPreferences.FACTORY_DEFAULT_OFFSET_MS) {
                        Text(
                            text = "Ripristina +0.6s",
                            fontFamily = font,
                            fontSize = 11.sp,
                            color = Color(0xFF93C5FD),
                            modifier = Modifier.clickable {
                                AppPreferences.setDefaultSyncOffsetMs(
                                    context,
                                    AppPreferences.FACTORY_DEFAULT_OFFSET_MS
                                )
                            }
                        )
                    }
                }

                GlassPillTextButton(
                    label = "+0.1s",
                    font = font,
                    onClick = { AppPreferences.setDefaultSyncOffsetMs(context, defaultOffsetMs + 100L) }
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    status: String,
    ok: Boolean,
    font: FontFamily,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .iosGlassButton(RoundedCornerShape(18.dp), elevated = false)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    if (ok) Color(0xFF22C55E).copy(alpha = 0.26f)
                    else Color(0xFFF59E0B).copy(alpha = 0.24f)
                )
        ) {
            CheckIcon(
                color = if (ok) Color(0xFF4ADE80) else Color(0xFFFCD34D),
                size = 14.dp
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontFamily = font,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = (-0.2).sp,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = status,
            fontFamily = font,
            fontSize = 12.sp,
            color = if (ok) Color(0xFF86EFAC) else Color(0xFFFDE68A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ------------------------------------------------------------
// 10. Pulsanti Modale & Icone Vector
// ------------------------------------------------------------
@Composable
private fun GlassPillTextButton(
    label: String,
    font: FontFamily,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
        label = "pillBtnScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .iosGlassButton(RoundedCornerShape(50), elevated = pressed, pressed = pressed)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontFamily = font,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = (-0.2).sp,
            color = Color.White,
            style = GlassTextShadowStyle
        )
    }
}

@Composable
private fun GlassCircleIconButton(
    size: Dp,
    elevated: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val isPressed = enabled && pressed
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
        label = "circleBtnScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .iosGlassButton(CircleShape, elevated = elevated || isPressed, pressed = isPressed)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        content()
    }
}

@Composable
private fun HamburgerIcon(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = size.toPx() * 0.12f
        val y1 = this.size.height * 0.24f
        val y2 = this.size.height * 0.50f
        val y3 = this.size.height * 0.76f
        drawLine(Color.White, Offset(0f, y1), Offset(this.size.width, y1), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(0f, y2), Offset(this.size.width, y2), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(0f, y3), Offset(this.size.width, y3), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun CheckIcon(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.16f, this@Canvas.size.height * 0.52f)
            lineTo(this@Canvas.size.width * 0.42f, this@Canvas.size.height * 0.78f)
            lineTo(this@Canvas.size.width * 0.86f, this@Canvas.size.height * 0.26f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = size.toPx() * 0.15f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun GearIcon(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outerR = this.size.minDimension * 0.44f
        val innerR = this.size.minDimension * 0.20f
        val strokeW = size.toPx() * 0.12f

        for (i in 0 until 8) {
            val angle = i * (PI / 4.0)
            val x1 = cx + (outerR * 0.72f * cos(angle)).toFloat()
            val y1 = cy + (outerR * 0.72f * sin(angle)).toFloat()
            val x2 = cx + (outerR * cos(angle)).toFloat()
            val y2 = cy + (outerR * sin(angle)).toFloat()
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = strokeW, cap = StrokeCap.Round)
        }
        drawCircle(color, radius = outerR * 0.68f, style = Stroke(width = strokeW))
        drawCircle(color, radius = innerR, style = Stroke(width = strokeW * 0.85f))
    }
}

@Composable
private fun CloseIcon(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = size.toPx() * 0.14f
        drawLine(Color.White, Offset(0f, 0f), Offset(this.size.width, this.size.height), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(this.size.width, 0f), Offset(0f, this.size.height), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun PlayIcon(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.30f, this@Canvas.size.height * 0.16f)
            lineTo(this@Canvas.size.width * 0.86f, this@Canvas.size.height * 0.50f)
            lineTo(this@Canvas.size.width * 0.30f, this@Canvas.size.height * 0.84f)
            close()
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = 3.5f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )
        drawPath(path = path, color = Color.White)
    }
}

@Composable
private fun PauseIcon(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width * 0.22f
        val h = this.size.height * 0.64f
        val top = (this.size.height - h) / 2f
        val r = CornerRadius(w / 2f, w / 2f)
        drawRoundRect(Color.White, Offset(this.size.width * 0.22f, top), Size(w, h), r)
        drawRoundRect(Color.White, Offset(this.size.width * 0.56f, top), Size(w, h), r)
    }
}

@Composable
private fun SkipNextIcon(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.18f, this@Canvas.size.height * 0.20f)
            lineTo(this@Canvas.size.width * 0.68f, this@Canvas.size.height * 0.50f)
            lineTo(this@Canvas.size.width * 0.18f, this@Canvas.size.height * 0.80f)
            close()
        }
        drawPath(path, Color.White)
        val barW = this.size.width * 0.13f
        drawRoundRect(
            Color.White,
            Offset(this.size.width * 0.72f, this.size.height * 0.20f),
            Size(barW, this.size.height * 0.60f),
            CornerRadius(barW / 2f, barW / 2f)
        )
    }
}

@Composable
private fun SkipPrevIcon(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.82f, this@Canvas.size.height * 0.20f)
            lineTo(this@Canvas.size.width * 0.32f, this@Canvas.size.height * 0.50f)
            lineTo(this@Canvas.size.width * 0.82f, this@Canvas.size.height * 0.80f)
            close()
        }
        drawPath(path, Color.White)
        val barW = this.size.width * 0.13f
        drawRoundRect(
            Color.White,
            Offset(this.size.width * 0.15f, this.size.height * 0.20f),
            Size(barW, this.size.height * 0.60f),
            CornerRadius(barW / 2f, barW / 2f)
        )
    }
}

@Composable
private fun MiniWaveIcon(size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val heights = floatArrayOf(0.45f, 0.90f, 0.45f)
        val barW = this.size.width * 0.20f
        val gap = this.size.width * 0.14f
        val startX = (this.size.width - (barW * 3 + gap * 2)) / 2f
        val cy = this.size.height / 2f
        for (i in 0 until 3) {
            val h = this.size.height * heights[i]
            drawRoundRect(
                Color.White.copy(alpha = 0.88f),
                Offset(startX + i * (barW + gap), cy - h / 2f),
                Size(barW, h),
                CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

// ------------------------------------------------------------
// Helper di sistema
// ------------------------------------------------------------
fun openAppInfoSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}

fun openAppNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        openAppInfoSettings(context)
    }
}

fun openNotificationListenerSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}

fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}

private fun formatPlayerTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
