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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    val lastShaderError = MutableStateFlow<String?>(null)
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
    val currentMode = display?.mode ?: return null
    val modes = display.supportedModes ?: return null
    if (modes.isEmpty()) return null

    val sameRes = modes.filter {
        it.physicalWidth == currentMode.physicalWidth &&
            it.physicalHeight == currentMode.physicalHeight
    }
    val pool = sameRes.ifEmpty { modes.toList() }
    return if (targetFps <= 30) {
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
}

@SuppressLint("NewApi")
private fun applyWindowLayoutParamsFrameRate(activity: Activity, targetFps: Int) {
    val window = activity.window ?: return
    val lp = window.attributes

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
}

@SuppressLint("NewApi")
fun applyFrameRateRecursive(view: View, fps: Float) {
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
            applyFrameRateRecursive(view.getChildAt(i), fps)
        }
    }
}

fun applyWindowFrameRate(activity: Activity, targetFps: Int) {
    applyWindowLayoutParamsFrameRate(activity, targetFps)
    if (Build.VERSION.SDK_INT >= 35) {
        activity.window?.decorView?.let { decor ->
            applyFrameRateRecursive(decor, targetFps.toFloat())
        }
    }
}

fun applyFrameRateToComposeView(composeView: View, activity: Activity, targetFps: Int) {
    applyWindowFrameRate(activity, targetFps)
    if (Build.VERSION.SDK_INT >= 35) {
        applyFrameRateRecursive(composeView, targetFps.toFloat())
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
            applyFrameRateToComposeView(composeView, activity, targetFps.fps)
            composeView.post {
                applyFrameRateToComposeView(composeView, activity, targetFps.fps)
            }
        }
    }

    SideEffect {
        if (activity != null && Build.VERSION.SDK_INT >= 35) {
            applyFrameRateRecursive(composeView, targetFps.fps.toFloat())
            activity.window?.decorView?.let { applyFrameRateRecursive(it, targetFps.fps.toFloat()) }
        }
    }

    DisposableEffect(activity, composeView, targetFps) {
        if (activity == null) {
            return@DisposableEffect onDispose {}
        }

        val owner = context as? LifecycleOwner
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applyFrameRateToComposeView(composeView, activity, AppPreferences.targetFps.value.fps)
            }
        }
        owner?.lifecycle?.addObserver(lifecycleObserver)

        val minDrawIntervalNs = when (targetFps) {
            TargetFps.FPS_30 -> 27_500_000L // ~30 FPS (su 60 Hz o 120 Hz)
            TargetFps.FPS_60 -> 13_500_000L // ~60 FPS (su 120 Hz)
            TargetFps.FPS_120 -> 0L
        }

        val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            private var lastDrawNs = 0L
            private var skippedConsecutive = 0

            override fun onPreDraw(): Boolean {
                if (Build.VERSION.SDK_INT >= 35) {
                    val rate = targetFps.fps.toFloat()
                    applyFrameRateRecursive(composeView, rate)
                    activity.window?.decorView?.let { applyFrameRateRecursive(it, rate) }
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

        val vto = composeView.viewTreeObserver
        if (vto.isAlive) {
            vto.addOnPreDrawListener(preDrawListener)
        }

        onDispose {
            owner?.lifecycle?.removeObserver(lifecycleObserver)
            if (composeView.viewTreeObserver.isAlive) {
                composeView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
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
        val dm = context.getSystemService(DisplayManager::class.java)
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displayHz = readActiveDisplayHz(context)
            }

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                displayHz = readActiveDisplayHz(context)
            }
        }
        dm?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(displayListener) }
    }

    LaunchedEffect(composeView, targetFps) {
        var lastReportMs = SystemClock.uptimeMillis()
        while (isActive) {
            withFrameNanos {
                // Mantiene attivo il flusso di controllo preDraw senza ricomposizioni Compose
                composeView.invalidate()
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
// 2. Motore Liquid Glass iOS 26 (Per-Capsula su GraphicsLayer condiviso)
// - Sfondo + Testi registrati UNA SOLA VOLTA in backdropLayer (Compose >= 1.7)
// - Ogni capsula / cerchio disegna backdropLayer traslato nella propria bounds,
//   concatenato con:
//     RenderEffect.createChainEffect(IOS_CAPSULE_GLASS_AGSL, createBlurEffect(CLAMP))
// - Sintassi Skia AGSL rigorosa (edge0 < edge1 in tutti gli smoothstep, cast espliciti)
// - Errori loggati con Log.e("LiquidGlass", ...) + avviso visibile in Impostazioni
// ------------------------------------------------------------

private const val IOS_CAPSULE_GLASS_AGSL = """
uniform shader composable;
uniform float2 size;
uniform float cornerRadius;
uniform float density;
uniform float tintAlpha;
uniform float lensStrength;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

float2 snellShift(float2 n, float sinA, float cosA, float eta) {
    float sin2T = eta * eta * sinA * sinA;
    float cosT = sqrt(max(1.0 - sin2T, 0.0001));
    float k = eta * cosA - cosT;
    float tz = max(eta - k * cosA, 0.25);
    return n * ((k * sinA) / tz);
}

half4 main(float2 fragCoord) {
    float2 safeSize = max(size, float2(4.0, 4.0));
    float2 halfSize = 0.5 * safeSize;
    float rad = clamp(cornerRadius, 1.0, min(halfSize.x, halfSize.y));
    float2 p = fragCoord - halfSize;

    float d = sdRoundBox(p, halfSize, rad);
    float eps = 1.25;
    float gx = sdRoundBox(p + float2(eps, 0.0), halfSize, rad) - sdRoundBox(p - float2(eps, 0.0), halfSize, rad);
    float gy = sdRoundBox(p + float2(0.0, eps), halfSize, rad) - sdRoundBox(p - float2(0.0, eps), halfSize, rad);
    float len = length(float2(gx, gy));
    float2 n = len > 0.0001 ? float2(gx, gy) / len : float2(0.0, -1.0);

    float minHalf = min(halfSize.x, halfSize.y);
    float distIn = max(-d, 0.0);
    float bezel = max(min(18.0 * density, minHalf * 0.78), 1.0);
    float t = clamp(distIn / bezel, 0.0, 1.0);
    float sinA = 1.0 - t;
    float z = sqrt(max(1.0 - sinA * sinA, 0.0001));

    // Magnificazione convessa al centro + rifrazione fisica di Snell sul menisco curvo
    float2 localNorm = p / max(halfSize, float2(1.0, 1.0));
    float zoomAmt = mix(1.0, 3.2, lensStrength) * density;
    float refractAmt = mix(9.5, 14.5, lensStrength) * density;
    float2 centerLens = -localNorm * zoomAmt * z;
    float2 posG = fragCoord + centerLens + snellShift(n, sinA, z, 1.0 / 1.52) * refractAmt;
    posG = clamp(posG, float2(0.5, 0.5), safeSize - float2(0.5, 0.5));

    half4 baseSample = composable.eval(posG);
    half3 rgb = baseSample.rgb;

    // Dispersione cromatica RGB prismatica sui bordi curvi
    if (t < 0.80) {
        float2 posR = fragCoord + centerLens + snellShift(n, sinA, z, 1.0 / 1.40) * refractAmt;
        float2 posB = fragCoord + centerLens + snellShift(n, sinA, z, 1.0 / 1.66) * refractAmt;
        posR = clamp(posR, float2(0.5, 0.5), safeSize - float2(0.5, 0.5));
        posB = clamp(posB, float2(0.5, 0.5), safeSize - float2(0.5, 0.5));
        half rEdge = composable.eval(posR).r;
        half bEdge = composable.eval(posB).b;
        float chromaFade = 1.0 - smoothstep(0.04, 0.80, t);
        float chromaMix = chromaFade * mix(0.46, 0.92, lensStrength);
        rgb.r = mix(rgb.r, rEdge, half(chromaMix));
        rgb.b = mix(rgb.b, bEdge, half(chromaMix));
    }

    // Vibrancy iOS 26 + Tinta scura traslucida calibrata
    half luma = dot(rgb, half3(0.299, 0.587, 0.114));
    rgb = mix(half3(luma, luma, luma), rgb, half(mix(1.32, 1.48, lensStrength)));
    half3 darkTint = half3(0.045, 0.050, 0.082);
    rgb = mix(rgb, darkTint, half(clamp(tintAlpha, 0.0, 0.85)));

    // Lucido lente superiore & caustica inferiore (tutti smoothstep con edge0 < edge1)
    float normY = clamp(p.y / max(halfSize.y, 1.0), -1.0, 1.0);
    float topFactor = 1.0 - smoothstep(-1.0, 0.35, normY);
    float botFactor = smoothstep(0.50, 1.0, normY);
    float glaze = topFactor * mix(0.055, 0.105, lensStrength) + botFactor * mix(0.02, 0.045, lensStrength);
    rgb += half3(half(glaze), half(glaze), half(glaze));

    // Sottile ombra interna di spessore vetro
    float grooveUp = smoothstep(0.8 * density, 2.4 * density, distIn);
    float grooveDn = 1.0 - smoothstep(2.4 * density, 5.5 * density, distIn);
    rgb *= half(1.0 - grooveUp * grooveDn * 0.045);

    // Riflesso speculare Fresnel razor-sharp (~0.95dp, dual-rim alto-sx + basso-dx)
    float2 keyLight = normalize(float2(-0.55, -0.84));
    float2 rimLight = normalize(float2(0.55, 0.84));
    float fresnel = pow(max(1.0 - z, 0.0001), 2.0);
    float specKey = pow(max(dot(n, keyLight), 0.0001), 2.2);
    float specBack = pow(max(dot(n, rimLight), 0.0001), 3.0) * 0.52;
    float dirWeight = specKey + specBack;

    float hairlineRim = 1.0 - smoothstep(0.0, 0.95 * density, distIn);
    float softCaustic = (1.0 - smoothstep(0.0, 4.8 * density, distIn)) * (0.06 + 0.14 * fresnel);
    float rimGain = hairlineRim * (0.18 + mix(0.75, 0.96, lensStrength) * dirWeight) +
                    softCaustic * (0.20 + 0.80 * specKey);

    rgb = clamp(rgb + half3(half(rimGain), half(rimGain), half(rimGain)), half3(0.0, 0.0, 0.0), half3(1.0, 1.0, 1.0));
    return half4(rgb * baseSample.a, baseSample.a);
}
"""

private fun buildCapsuleGlassRenderEffect(
    boxSize: IntSize,
    cornerRadiusPx: Float,
    blurPx: Float,
    densityPx: Float,
    tintAlpha: Float,
    lensStrength: Float
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (boxSize.width <= 2 || boxSize.height <= 2) return null

    return try {
        val shader = RuntimeShader(IOS_CAPSULE_GLASS_AGSL).apply {
            setFloatUniform("size", boxSize.width.toFloat(), boxSize.height.toFloat())
            setFloatUniform("cornerRadius", cornerRadiusPx)
            setFloatUniform("density", densityPx)
            setFloatUniform("tintAlpha", tintAlpha)
            setFloatUniform("lensStrength", lensStrength.coerceIn(0f, 1f))
        }
        val lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "composable")
        val finalEffect = if (blurPx >= 1.2f) {
            val blurEffect = RenderEffect.createBlurEffect(
                blurPx,
                blurPx,
                Shader.TileMode.CLAMP
            )
            RenderEffect.createChainEffect(lensEffect, blurEffect)
        } else {
            lensEffect
        }
        LiquidGlassDiagnostics.lastShaderError.value = null
        finalEffect.asComposeRenderEffect()
    } catch (t: Throwable) {
        Log.e("LiquidGlass", "RuntimeShader/RenderEffect failed", t)
        LiquidGlassDiagnostics.lastShaderError.value = t.message ?: t::class.java.simpleName
        null
    }
}

/**
 * Superficie Liquid Glass per-capsula:
 * disegna `backdropLayer` traslato esattamente sulle proprie coordinate rispetto allo schermo
 * filtrato da `createChainEffect(IOS_CAPSULE_GLASS_AGSL, createBlurEffect(CLAMP))`.
 * Quando `pressed == true`, riduce dinamicamente la sfocatura per trasformarsi in "Goccia Liquida"
 * cristallina a rifrazione convessa e dispersione prismatica sui bordi.
 */
@Composable
private fun LiquidGlassCapsuleBox(
    backdropLayer: GraphicsLayer,
    backdropTopLeft: Offset,
    shape: Shape,
    cornerRadiusDp: Dp,
    blurDp: Dp,
    tintAlpha: Float,
    pressed: Boolean = false,
    elevated: Boolean = false,
    backdropInvalidator: () -> Any = { Unit },
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val densityPx = density.density
    var myTopLeft by remember { mutableStateOf(Offset.Zero) }
    var mySize by remember { mutableStateOf(IntSize.Zero) }

    val pressFraction by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 460f),
        label = "capsulePress"
    )

    val scale = 1f + 0.065f * pressFraction
    val effectiveBlurDp = if (blurDp <= 2.dp) blurDp.value else blurDp.value * (1f - 0.88f * pressFraction)
    val effectiveTint = tintAlpha * (1f - 0.65f * pressFraction)
    val lensStrength = (if (blurDp <= 2.dp) 0.95f else 0.36f + 0.64f * pressFraction)
    val cornerRadiusPx = with(density) { cornerRadiusDp.toPx() }
    val blurPx = with(density) { effectiveBlurDp.dp.toPx() }

    val renderEffect = remember(
        mySize,
        cornerRadiusPx,
        blurPx,
        densityPx,
        effectiveTint,
        lensStrength
    ) {
        buildCapsuleGlassRenderEffect(
            boxSize = mySize,
            cornerRadiusPx = cornerRadiusPx,
            blurPx = blurPx,
            densityPx = densityPx,
            tintAlpha = effectiveTint,
            lensStrength = lensStrength
        )
    }

    val relOffset = myTopLeft - backdropTopLeft

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                myTopLeft = bounds.topLeft
                mySize = coords.size
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (elevated || pressed) 14.dp else 9.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.55f),
                clip = false
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    clip = true
                    this.shape = shape
                    this.renderEffect = renderEffect
                }
                .then(
                    if (renderEffect == null) {
                        Modifier.background(
                            Color(0xFF0E1018).copy(alpha = (effectiveTint + 0.34f).coerceIn(0.28f, 0.78f))
                        )
                    } else {
                        Modifier
                    }
                )
                .drawBehind {
                    backdropInvalidator()
                    if (mySize.width > 0 && mySize.height > 0) {
                        translate(left = -relOffset.x, top = -relOffset.y) {
                            drawLayer(backdropLayer)
                        }
                    }
                }
        )

        if (renderEffect == null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(0.85.dp, Color.White.copy(alpha = 0.34f), shape)
            )
        }

        content()
    }
}

/**
 * Overlay "Goccia Liquida" trasparente usato SOLO sopra i controlli interni (es. Play/Pause, chip)
 * quando premuti: a riposo è a costo zero, al tocco campiona il backdrop NITIDO con lente convessa.
 */
@Composable
private fun LiquidDropletOverlay(
    backdropLayer: GraphicsLayer,
    backdropTopLeft: Offset,
    shape: Shape,
    cornerRadiusDp: Dp,
    pressed: Boolean,
    tintColor: Color = Color.Transparent,
    backdropInvalidator: () -> Any = { Unit },
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val densityPx = density.density
    var myTopLeft by remember { mutableStateOf(Offset.Zero) }
    var mySize by remember { mutableStateOf(IntSize.Zero) }

    val pressFraction by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.50f, stiffness = 460f),
        label = "innerDroplet"
    )
    val scale = 1f + 0.085f * pressFraction
    val cornerRadiusPx = with(density) { cornerRadiusDp.toPx() }

    val dropEffect = remember(mySize, cornerRadiusPx, pressFraction, densityPx) {
        if (pressFraction <= 0.02f) {
            null
        } else {
            buildCapsuleGlassRenderEffect(
                boxSize = mySize,
                cornerRadiusPx = cornerRadiusPx,
                blurPx = 0f, // nitidissimo, lente convessa pura!
                densityPx = densityPx,
                tintAlpha = 0.04f,
                lensStrength = pressFraction
            )
        }
    }

    val relOffset = myTopLeft - backdropTopLeft

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onGloballyPositioned { coords ->
                myTopLeft = coords.boundsInRoot().topLeft
                mySize = coords.size
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        if (pressFraction > 0.02f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        alpha = pressFraction
                        this.renderEffect = dropEffect
                    }
                    .drawBehind {
                        backdropInvalidator()
                        if (mySize.width > 0 && mySize.height > 0) {
                            translate(left = -relOffset.x, top = -relOffset.y) {
                                drawLayer(backdropLayer)
                            }
                        }
                    }
            )
        } else if (tintColor != Color.Transparent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(tintColor)
            )
        }
        content()
    }
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
    pressed: Boolean = false
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            0.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.14f
                    elevated -> 0.22f
                    else -> 0.11f
                }
            ),
            0.40f to Color.White.copy(
                alpha = when {
                    pressed -> 0.03f
                    elevated -> 0.07f
                    else -> 0.03f
                }
            ),
            1.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.08f
                    elevated -> 0.11f
                    else -> 0.05f
                }
            )
        )
    )
    .border(
        width = 0.8.dp,
        brush = Brush.linearGradient(
            0.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.90f
                    elevated -> 0.70f
                    else -> 0.48f
                }
            ),
            0.45f to Color.White.copy(alpha = if (pressed) 0.22f else 0.08f),
            1.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.65f
                    elevated -> 0.44f
                    else -> 0.26f
                }
            )
        ),
        shape = shape
    )

// ------------------------------------------------------------
// 4. Font stile Apple SF Pro Display (sf_pro.ttf locale o Inter)
// ------------------------------------------------------------
@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberDynamicFont(): FontFamily {
    val context = LocalContext.current
    val family by produceState<FontFamily>(initialValue = FontFamily.SansSerif) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val localSfProId = context.resources.getIdentifier("sf_pro", "font", context.packageName)
                if (localSfProId != 0) {
                    return@withContext FontFamily(
                        Font(localSfProId, weight = FontWeight.Normal, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
                        Font(localSfProId, weight = FontWeight.Medium, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
                        Font(localSfProId, weight = FontWeight.SemiBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
                        Font(localSfProId, weight = FontWeight.Bold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
                        Font(localSfProId, weight = FontWeight.ExtraBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
                    )
                }

                val dir = File(context.filesDir, "fonts")
                val file = File(dir, "InterAppleStyle.ttf")
                if (!file.exists() || file.length() < 40_000L) {
                    if (!dir.exists()) dir.mkdirs()
                    val urls = listOf(
                        "https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf",
                        "https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bslnt%2Cwght%5D.ttf",
                        "https://github.com/rsms/inter/raw/master/docs/font-files/InterVariable.ttf"
                    )
                    for (urlStr in urls) {
                        try {
                            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 6000
                                readTimeout = 6000
                                instanceFollowRedirects = true
                            }
                            if (conn.responseCode in 200..299) {
                                conn.inputStream.use { input ->
                                    file.outputStream().use { output -> input.copyTo(output) }
                                }
                                conn.disconnect()
                                if (file.length() > 40_000L) break
                            } else {
                                conn.disconnect()
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (file.exists() && file.length() > 40_000L) {
                    FontFamily(
                        Font(file, weight = FontWeight.Normal, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
                        Font(file, weight = FontWeight.Medium, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
                        Font(file, weight = FontWeight.SemiBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
                        Font(file, weight = FontWeight.Bold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
                        Font(file, weight = FontWeight.ExtraBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
                    )
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
// 5. Sfondo Vivido per Rifrazione Liquid Glass
// ------------------------------------------------------------
@Composable
fun LiquidAmbientBackground(
    cover: Bitmap?,
    palette: CoverPalette
) {
    val coverImg = remember(cover) {
        if (cover != null && !cover.isRecycled && cover.width > 0 && cover.height > 0) {
            try {
                Bitmap.createScaledBitmap(cover, 128, 128, true).asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    val blurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP).asComposeRenderEffect()
        } else {
            null
        }
    }

    val fallbackBrush = remember(palette) {
        Brush.verticalGradient(
            colors = listOf(
                palette.accent.copy(alpha = 0.78f),
                palette.primary,
                Color(0xFF090912)
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(fallbackBrush)) {
        if (coverImg != null) {
            Image(
                bitmap = coverImg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.28f
                        scaleY = 1.28f
                        renderEffect = blurEffect
                    }
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF6366F1).copy(alpha = 0.62f), Color.Transparent),
                        center = Offset(size.width * 0.22f, size.height * 0.18f),
                        radius = size.width * 0.80f
                    ),
                    center = Offset(size.width * 0.22f, size.height * 0.18f),
                    radius = size.width * 0.80f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFFEC4899).copy(alpha = 0.48f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.78f),
                        radius = size.width * 0.80f
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.78f),
                    radius = size.width * 0.80f
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.16f),
                        0.5f to Color.Black.copy(alpha = 0.24f),
                        1.0f to Color.Black.copy(alpha = 0.36f)
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
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFE11D48).copy(alpha = 0.42f),
                                Color(0xFF6366F1).copy(alpha = 0.24f),
                                Color.Transparent
                            )
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
// 7. Schermata Avvio / Permessi in Vero Liquid Glass Per-Capsula
// ------------------------------------------------------------
@Composable
fun LiquidPermissionsScreen(
    font: FontFamily,
    refreshTick: Int = 0,
    onRequestNotifications: () -> Unit = {},
    onAllReady: () -> Unit
) {
    val context = LocalContext.current
    val appInfoDone by AppPreferences.appInfoStepDone.collectAsState()
    var postNotifOk by remember {
        mutableStateOf(BitChordListenerService.areNotificationsEnabled(context))
    }
    var listenerGranted by remember {
        mutableStateOf(BitChordListenerService.isNotificationAccessGranted(context))
    }
    var awaitingAppInfoReturn by remember { mutableStateOf(false) }

    val backdropLayer = rememberGraphicsLayer()
    var backdropTopLeft by remember { mutableStateOf(Offset.Zero) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop registrato una sola volta
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { backdropTopLeft = it.boundsInRoot().topLeft }
                .drawWithContent {
                    backdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdropLayer)
                }
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
            LiquidGlassCapsuleBox(
                backdropLayer = backdropLayer,
                backdropTopLeft = backdropTopLeft,
                shape = RoundedCornerShape(24.dp),
                cornerRadiusDp = 24.dp,
                blurDp = 16.dp,
                tintAlpha = 0.22f,
                elevated = true,
                modifier = Modifier.size(76.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                ) {
                    DynamicLyricsBubbleGlyph()
                }
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
                font = font,
                backdropLayer = backdropLayer,
                backdropTopLeft = backdropTopLeft,
                onClick = onRequestNotifications
            )

            Spacer(Modifier.height(14.dp))

            PermissionLiquidGlassCard(
                stepNumber = "2",
                title = "Info App",
                subtitle = "Consenti impostazioni con restrizioni e nessuna limitazione batteria.",
                buttonLabel = if (appInfoDone) "Riapri" else "Apri",
                completed = appInfoDone,
                font = font,
                backdropLayer = backdropLayer,
                backdropTopLeft = backdropTopLeft,
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
                font = font,
                backdropLayer = backdropLayer,
                backdropTopLeft = backdropTopLeft,
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
    font: FontFamily,
    backdropLayer: GraphicsLayer,
    backdropTopLeft: Offset,
    onClick: () -> Unit
) {
    val pillInteraction = remember { MutableInteractionSource() }
    val pillPressed by pillInteraction.collectIsPressedAsState()

    LiquidGlassCapsuleBox(
        backdropLayer = backdropLayer,
        backdropTopLeft = backdropTopLeft,
        shape = RoundedCornerShape(28.dp),
        cornerRadiusDp = 28.dp,
        blurDp = 18.dp,
        tintAlpha = 0.24f,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onClick)
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

            LiquidDropletOverlay(
                backdropLayer = backdropLayer,
                backdropTopLeft = backdropTopLeft,
                shape = RoundedCornerShape(50),
                cornerRadiusDp = 19.dp,
                pressed = pillPressed,
                tintColor = if (completed) Color(0xFF22C55E).copy(alpha = 0.24f) else Color.Transparent,
                modifier = Modifier
                    .size(width = 86.dp, height = 38.dp)
                    .iosGlassButton(
                        shape = RoundedCornerShape(50),
                        elevated = !completed,
                        pressed = pillPressed
                    )
                    .clickable(
                        interactionSource = pillInteraction,
                        indication = null,
                        onClick = onClick
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

    val backdropLayer = rememberGraphicsLayer()
    var backdropTopLeft by remember { mutableStateOf(Offset.Zero) }

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
    var lyricTransitionTick by remember { mutableIntStateOf(0) }

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

    // Durante i 240ms di transizione testo/alpha tiene sincronizzati i layer vetro per-capsula
    LaunchedEffect(activeIndex, targetFps) {
        val step = when (targetFps) {
            TargetFps.FPS_30 -> 33L
            TargetFps.FPS_60 -> 16L
            TargetFps.FPS_120 -> 8L
        }
        val endMs = SystemClock.uptimeMillis() + 260L
        while (SystemClock.uptimeMillis() < endMs) {
            lyricTransitionTick++
            delay(step)
        }
    }

    val backdropInvalidator: () -> Any = {
        listState.firstVisibleItemIndex +
            listState.firstVisibleItemScrollOffset +
            rawPosState.longValue +
            currentPosState.longValue +
            lyricTransitionTick +
            activeIndex +
            title.length +
            artist.length +
            (if (coverBitmap != null) 1 else 0)
    }

    val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        // STRATO 0 (BACKDROP UNICO): Sfondo + Testi con dissolvenza pulita prima di status bar / nav bar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { backdropTopLeft = it.boundsInRoot().topLeft }
                .drawWithContent {
                    backdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdropLayer)
                }
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
        }

        // Dim morbido solo dietro il modale Impostazioni (senza sporcare il vetro della sheet)
        val dimAlpha by animateFloatAsState(
            targetValue = if (showSettingsSheet) 0.36f else 0f,
            animationSpec = tween(180),
            label = "sheetDim"
        )
        if (dimAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha))
            )
        }

        // CTA AVVIO RAPIDO LIQUID GLASS QUANDO NON C'È MUSICA ATTIVA
        if (lines.isEmpty() && title.isBlank() && !showSettingsSheet && !menuExpanded) {
            val launchLabel = if (preferredPlayer == PreferredPlayer.AUTO) {
                "Avvia Player"
            } else {
                "Avvia ${preferredPlayer.label}"
            }
            val ctaInteraction = remember { MutableInteractionSource() }
            val ctaPressed by ctaInteraction.collectIsPressedAsState()

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 110.dp)
            ) {
                LiquidGlassCapsuleBox(
                    backdropLayer = backdropLayer,
                    backdropTopLeft = backdropTopLeft,
                    shape = RoundedCornerShape(50),
                    cornerRadiusDp = 25.dp,
                    blurDp = 16.dp,
                    tintAlpha = 0.22f,
                    pressed = ctaPressed,
                    elevated = true,
                    backdropInvalidator = backdropInvalidator,
                    modifier = Modifier
                        .height(50.dp)
                        .clickable(
                            interactionSource = ctaInteraction,
                            indication = null,
                            onClick = { BitChordListenerService.triggerPlayPreferred(context) }
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxHeight()
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
        }

        // STRATO 1: TOP BAR A MODULI STACCATI (Capsula + Cerchi da 52dp con stacco 10dp)
        if (!showSettingsSheet) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                LiquidGlassCapsuleBox(
                    backdropLayer = backdropLayer,
                    backdropTopLeft = backdropTopLeft,
                    shape = RoundedCornerShape(50),
                    cornerRadiusDp = 26.dp,
                    blurDp = 16.dp,
                    tintAlpha = 0.22f,
                    backdropInvalidator = backdropInvalidator,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                    ) {
                        if (coverBitmap != null) {
                            Image(
                                bitmap = coverBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .border(0.75.dp, Color.White.copy(alpha = 0.48f), CircleShape)
                            )
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
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
                        Spacer(Modifier.width(6.dp))
                    }
                }

                if (showCloseButton && onClose != null) {
                    Spacer(Modifier.width(10.dp))
                    val closeInteraction = remember { MutableInteractionSource() }
                    val closePressed by closeInteraction.collectIsPressedAsState()

                    LiquidGlassCapsuleBox(
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        shape = CircleShape,
                        cornerRadiusDp = 26.dp,
                        blurDp = 16.dp,
                        tintAlpha = 0.22f,
                        pressed = closePressed,
                        backdropInvalidator = backdropInvalidator,
                        modifier = Modifier
                            .size(52.dp)
                            .clickable(
                                interactionSource = closeInteraction,
                                indication = null,
                                onClick = onClose
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CloseIcon(size = 14.dp)
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                val menuInteraction = remember { MutableInteractionSource() }
                val menuPressed by menuInteraction.collectIsPressedAsState()

                LiquidGlassCapsuleBox(
                    backdropLayer = backdropLayer,
                    backdropTopLeft = backdropTopLeft,
                    shape = CircleShape,
                    cornerRadiusDp = 26.dp,
                    blurDp = 16.dp,
                    tintAlpha = 0.22f,
                    pressed = menuPressed,
                    elevated = menuExpanded,
                    backdropInvalidator = backdropInvalidator,
                    modifier = Modifier
                        .size(52.dp)
                        .clickable(
                            interactionSource = menuInteraction,
                            indication = null,
                            onClick = { menuExpanded = !menuExpanded }
                        )
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        HamburgerIcon(size = 18.dp)
                    }
                }
            }

            // STRATO 2: BOTTOM DOCK SNELLE A 2 LIVELLI (Mini-Scrubber + Capsula Media + Pillole Sync)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                LiquidScrubberMiniPill(
                    durationMs = durationMs,
                    syncOffsetMs = syncOffsetMs,
                    font = font,
                    backdropLayer = backdropLayer,
                    backdropTopLeft = backdropTopLeft,
                    backdropInvalidator = backdropInvalidator,
                    rawPosProvider = { rawPosState.longValue },
                    onResetSync = {
                        val defOffset = AppPreferences.defaultSyncOffsetMs.value
                        LyricsPlaybackStore.syncOffsetMs.value = defOffset
                        val s = if (defOffset >= 0L) "+" else ""
                        statusBannerText = String.format(Locale.US, "Sync ripristinato %s%.1fs", s, defOffset / 1000f)
                    }
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val minusInteraction = remember { MutableInteractionSource() }
                    val minusPressed by minusInteraction.collectIsPressedAsState()

                    LiquidGlassCapsuleBox(
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        shape = RoundedCornerShape(50),
                        cornerRadiusDp = 29.dp,
                        blurDp = 16.dp,
                        tintAlpha = 0.22f,
                        pressed = minusPressed,
                        backdropInvalidator = backdropInvalidator,
                        modifier = Modifier
                            .size(width = 64.dp, height = 58.dp)
                            .clickable(
                                interactionSource = minusInteraction,
                                indication = null,
                                onClick = { LyricsPlaybackStore.syncOffsetMs.value -= 500L }
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
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
                    }

                    Spacer(Modifier.width(10.dp))

                    LiquidGlassCapsuleBox(
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        shape = RoundedCornerShape(50),
                        cornerRadiusDp = 29.dp,
                        blurDp = 16.dp,
                        tintAlpha = 0.22f,
                        backdropInvalidator = backdropInvalidator,
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val prevInteraction = remember { MutableInteractionSource() }
                            val prevPressed by prevInteraction.collectIsPressedAsState()
                            LiquidDropletOverlay(
                                backdropLayer = backdropLayer,
                                backdropTopLeft = backdropTopLeft,
                                shape = CircleShape,
                                cornerRadiusDp = 22.dp,
                                pressed = prevPressed,
                                backdropInvalidator = backdropInvalidator,
                                modifier = Modifier
                                    .size(44.dp)
                                    .iosGlassButton(CircleShape, elevated = false, pressed = prevPressed)
                                    .clickable(
                                        interactionSource = prevInteraction,
                                        indication = null,
                                        onClick = { LyricsPlaybackStore.onPrevious?.invoke() }
                                    )
                            ) {
                                SkipPrevIcon(size = 18.dp)
                            }

                            val playInteraction = remember { MutableInteractionSource() }
                            val playPressed by playInteraction.collectIsPressedAsState()
                            LiquidDropletOverlay(
                                backdropLayer = backdropLayer,
                                backdropTopLeft = backdropTopLeft,
                                shape = RoundedCornerShape(50),
                                cornerRadiusDp = 23.dp,
                                pressed = playPressed,
                                backdropInvalidator = backdropInvalidator,
                                modifier = Modifier
                                    .size(width = 62.dp, height = 46.dp)
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
                                if (isPlaying) PauseIcon(size = 22.dp) else PlayIcon(size = 22.dp)
                            }

                            val nextInteraction = remember { MutableInteractionSource() }
                            val nextPressed by nextInteraction.collectIsPressedAsState()
                            LiquidDropletOverlay(
                                backdropLayer = backdropLayer,
                                backdropTopLeft = backdropTopLeft,
                                shape = CircleShape,
                                cornerRadiusDp = 22.dp,
                                pressed = nextPressed,
                                backdropInvalidator = backdropInvalidator,
                                modifier = Modifier
                                    .size(44.dp)
                                    .iosGlassButton(CircleShape, elevated = false, pressed = nextPressed)
                                    .clickable(
                                        interactionSource = nextInteraction,
                                        indication = null,
                                        onClick = { LyricsPlaybackStore.onNext?.invoke() }
                                    )
                            ) {
                                SkipNextIcon(size = 18.dp)
                            }
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    val plusInteraction = remember { MutableInteractionSource() }
                    val plusPressed by plusInteraction.collectIsPressedAsState()

                    LiquidGlassCapsuleBox(
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        shape = RoundedCornerShape(50),
                        cornerRadiusDp = 29.dp,
                        blurDp = 16.dp,
                        tintAlpha = 0.22f,
                        pressed = plusPressed,
                        backdropInvalidator = backdropInvalidator,
                        modifier = Modifier
                            .size(width = 64.dp, height = 58.dp)
                            .clickable(
                                interactionSource = plusInteraction,
                                indication = null,
                                onClick = { LyricsPlaybackStore.syncOffsetMs.value += 500L }
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
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
            }
        }

        // STRATO 3: POPOVER MENU HAMBURGER
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
            exit = fadeOut(tween(130)) + scaleOut(
                targetScale = 0.90f,
                transformOrigin = TransformOrigin(1f, 0f)
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 72.dp, end = 16.dp)
        ) {
            val granted = BitChordListenerService.isNotificationAccessGranted(context)
            val serviceConnected by BitChordListenerService.isServiceConnected.collectAsState()
            val activeNow = granted && serviceConnected

            LiquidGlassCapsuleBox(
                backdropLayer = backdropLayer,
                backdropTopLeft = backdropTopLeft,
                shape = RoundedCornerShape(26.dp),
                cornerRadiusDp = 26.dp,
                blurDp = 22.dp,
                tintAlpha = 0.45f,
                elevated = true,
                backdropInvalidator = backdropInvalidator,
                modifier = Modifier.width(236.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
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

        // PANNELLO IMPOSTAZIONI & VERIFICA STATO LIQUID GLASS
        AnimatedVisibility(
            visible = showSettingsSheet,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            LiquidSettingsModal(
                font = font,
                backdropLayer = backdropLayer,
                backdropTopLeft = backdropTopLeft,
                backdropInvalidator = backdropInvalidator,
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

@Composable
private fun LiquidScrubberMiniPill(
    durationMs: Long,
    syncOffsetMs: Long,
    font: FontFamily,
    backdropLayer: GraphicsLayer,
    backdropTopLeft: Offset,
    backdropInvalidator: () -> Any,
    rawPosProvider: () -> Long,
    onResetSync: () -> Unit
) {
    val density = LocalDensity.current
    val safeDuration = durationMs.coerceAtLeast(1L)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }

    val rawPosMs = rawPosProvider()
    val liveFraction = (rawPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val shownFraction = if (isDragging) dragFraction else liveFraction
    val shownTimeMs = if (isDragging) (dragFraction * safeDuration).toLong() else rawPosMs

    LiquidGlassCapsuleBox(
        backdropLayer = backdropLayer,
        backdropTopLeft = backdropTopLeft,
        shape = RoundedCornerShape(50),
        cornerRadiusDp = 19.dp,
        blurDp = 16.dp,
        tintAlpha = 0.22f,
        backdropInvalidator = backdropInvalidator,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = formatPlayerTime(shownTimeMs),
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 11.sp,
                style = GlassTextShadowStyle
            )

            Spacer(Modifier.width(10.dp))

            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { trackWidthPx = it.size.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(safeDuration) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            isDragging = true
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
                            LyricsPlaybackStore.onSeekTo?.invoke(targetSeekMs)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
                    val trackH = if (isDragging) 6.dp.toPx() else 4.dp.toPx()
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
                    val thumbR = if (isDragging) 6.5.dp.toPx() else 4.dp.toPx()
                    drawCircle(
                        color = Color.White,
                        radius = thumbR,
                        center = Offset(activeW.coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR)), cy)
                    )
                }

                // Goccia liquida cristallina sotto il pollice durante il trascinamento
                if (isDragging) {
                    val dropW = 34.dp
                    val dropWPx = with(density) { dropW.toPx() }
                    val offsetX = (trackWidthPx * shownFraction - dropWPx / 2f)
                        .roundToInt()
                        .coerceIn(0, (trackWidthPx - dropWPx).roundToInt().coerceAtLeast(0))

                    LiquidDropletOverlay(
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        shape = RoundedCornerShape(50),
                        cornerRadiusDp = 12.dp,
                        pressed = true,
                        backdropInvalidator = backdropInvalidator,
                        modifier = Modifier
                            .offset { IntOffset(offsetX, 0) }
                            .size(width = dropW, height = 24.dp)
                            .iosGlassButton(RoundedCornerShape(50), elevated = true, pressed = true)
                    ) {}
                }
            }

            Spacer(Modifier.width(8.dp))

            val sign = if (syncOffsetMs >= 0L) "+" else ""
            Text(
                text = String.format(Locale.US, "Sync %s%.1fs", sign, syncOffsetMs / 1000f),
                fontFamily = font,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                style = GlassTextShadowStyle,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onResetSync)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = formatPlayerTime(durationMs),
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 11.sp,
                style = GlassTextShadowStyle
            )
        }
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
    backdropLayer: GraphicsLayer,
    backdropTopLeft: Offset,
    backdropInvalidator: () -> Any,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val composeView = LocalView.current
    val preferredPlayer by AppPreferences.preferredPlayer.collectAsState()
    val defaultOffsetMs by AppPreferences.defaultSyncOffsetMs.collectAsState()
    val targetFps by AppPreferences.targetFps.collectAsState()
    val activePkg by LyricsPlaybackStore.activePlayerPackage.collectAsState()
    val lastPkg by AppPreferences.lastActiveMusicPackage.collectAsState()
    val shaderError by LiquidGlassDiagnostics.lastShaderError.collectAsState()

    val notifGranted = BitChordListenerService.isNotificationAccessGranted(context)
    val postNotifOk = BitChordListenerService.areNotificationsEnabled(context)
    val serviceAlive by BitChordListenerService.isServiceConnected.collectAsState()
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
        LiquidGlassCapsuleBox(
            backdropLayer = backdropLayer,
            backdropTopLeft = backdropTopLeft,
            shape = RoundedCornerShape(36.dp),
            cornerRadiusDp = 36.dp,
            blurDp = 22.dp,
            tintAlpha = 0.48f,
            elevated = true,
            backdropInvalidator = backdropInvalidator,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        backdropInvalidator = backdropInvalidator,
                        onClick = onDismiss
                    ) {
                        CloseIcon(size = 13.dp)
                    }
                }

                // Avviso visibile rosso se RuntimeShader o RenderEffect fallisce su questo dispositivo
                if (shaderError != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Shader Liquid Glass non attivo: $shaderError",
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

                        LiquidDropletOverlay(
                            backdropLayer = backdropLayer,
                            backdropTopLeft = backdropTopLeft,
                            shape = RoundedCornerShape(50),
                            cornerRadiusDp = 21.dp,
                            pressed = pressed,
                            tintColor = if (selected) Color(0xFF6366F1).copy(alpha = 0.46f) else Color.Transparent,
                            backdropInvalidator = backdropInvalidator,
                            modifier = Modifier
                                .weight(1f)
                                .iosGlassButton(
                                    shape = RoundedCornerShape(50),
                                    elevated = selected,
                                    pressed = pressed
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

                        LiquidDropletOverlay(
                            backdropLayer = backdropLayer,
                            backdropTopLeft = backdropTopLeft,
                            shape = RoundedCornerShape(50),
                            cornerRadiusDp = 20.dp,
                            pressed = pressed,
                            tintColor = if (selected) Color(0xFF6366F1).copy(alpha = 0.46f) else Color.Transparent,
                            backdropInvalidator = backdropInvalidator,
                            modifier = Modifier
                                .iosGlassButton(
                                    RoundedCornerShape(50),
                                    elevated = selected,
                                    pressed = pressed
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
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        backdropInvalidator = backdropInvalidator,
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
                        backdropLayer = backdropLayer,
                        backdropTopLeft = backdropTopLeft,
                        backdropInvalidator = backdropInvalidator,
                        onClick = { AppPreferences.setDefaultSyncOffsetMs(context, defaultOffsetMs + 100L) }
                    )
                }
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
    backdropLayer: GraphicsLayer? = null,
    backdropTopLeft: Offset = Offset.Zero,
    backdropInvalidator: () -> Any = { Unit },
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    if (backdropLayer != null) {
        LiquidDropletOverlay(
            backdropLayer = backdropLayer,
            backdropTopLeft = backdropTopLeft,
            shape = RoundedCornerShape(50),
            cornerRadiusDp = 18.dp,
            pressed = pressed,
            backdropInvalidator = backdropInvalidator,
            modifier = Modifier
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
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
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
}

@Composable
private fun GlassCircleIconButton(
    size: Dp,
    elevated: Boolean = false,
    enabled: Boolean = true,
    backdropLayer: GraphicsLayer? = null,
    backdropTopLeft: Offset = Offset.Zero,
    backdropInvalidator: () -> Any = { Unit },
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    if (backdropLayer != null) {
        LiquidDropletOverlay(
            backdropLayer = backdropLayer,
            backdropTopLeft = backdropTopLeft,
            shape = CircleShape,
            cornerRadiusDp = size / 2f,
            pressed = enabled && pressed,
            backdropInvalidator = backdropInvalidator,
            modifier = Modifier
                .size(size)
                .iosGlassButton(CircleShape, elevated = elevated || (enabled && pressed), pressed = enabled && pressed)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        ) {
            content()
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .iosGlassButton(CircleShape, elevated = elevated || (enabled && pressed), pressed = enabled && pressed)
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

private fun formatPlayerTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
