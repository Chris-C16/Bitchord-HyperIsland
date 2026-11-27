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
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------
// 1. Blocco Frame Rate Reale (30 / 60 / 120 FPS — Default 60 FPS)
// - Chiamate API 35 dirette e verificate a compile-time:
//   * WindowManager.LayoutParams.setFrameRateBoostOnTouchEnabled(targetFps >= 120)
//   * View.getRequestedFrameRate() / View.setRequestedFrameRate(float)
// - Selezione coerente Display.Mode alla stessa risoluzione fisica
// - Enforcement continuo su DecorView + AndroidComposeView (LocalView.current)
// - Contatore FPS reali tramite Choreographer + Frequenza display attiva
// ------------------------------------------------------------

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
            // 60 Hz è divisore intero esatto di 30 FPS (mai 120 Hz)
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
    lp.preferredRefreshRate = targetFps.toFloat()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            selectDesiredDisplayMode(activity.display, targetFps)?.let { mode ->
                lp.preferredDisplayModeId = mode.modeId
            }
        } catch (_: Throwable) {
        }
    }

    if (Build.VERSION.SDK_INT >= 35) {
        try {
            // Fondamentale su Android 15/16: è su WindowManager.LayoutParams, NON su Window
            lp.setFrameRateBoostOnTouchEnabled(targetFps >= 120)
        } catch (_: Throwable) {
        }
    }
    window.attributes = lp
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
 * Aggancia il voto FPS a Window, DecorView e AndroidComposeView (LocalView.current),
 * impedendo a Compose o al touch-boost di riportare la frequenza a 120 Hz durante scroll e animazioni.
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

        val preDrawListener = if (Build.VERSION.SDK_INT >= 35) {
            ViewTreeObserver.OnPreDrawListener {
                val rate = targetFps.fps.toFloat()
                applyFrameRateRecursive(composeView, rate)
                activity.window?.decorView?.let { applyFrameRateRecursive(it, rate) }
                true
            }
        } else {
            null
        }
        val vto = composeView.viewTreeObserver
        if (preDrawListener != null && vto.isAlive) {
            vto.addOnPreDrawListener(preDrawListener)
        }

        onDispose {
            owner?.lifecycle?.removeObserver(lifecycleObserver)
            if (preDrawListener != null && composeView.viewTreeObserver.isAlive) {
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
 * Misura gli FPS effettivi consegnati dal Choreographer di sistema ogni ~0.5s.
 * Inoltre riallinea periodicamente il DisplayHz attivo in caso di cambio dinamico LTPO.
 */
@Composable
fun rememberFrameRateTelemetry(): Pair<Int, Int> {
    val context = LocalContext.current
    var measuredFps by remember { mutableIntStateOf(0) }
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

        val choreographer = Choreographer.getInstance()
        var active = true
        var frameCount = 0
        var windowStartNs = 0L

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active) return
                if (windowStartNs == 0L) {
                    windowStartNs = frameTimeNanos
                    frameCount = 0
                } else {
                    frameCount++
                    val elapsedNs = frameTimeNanos - windowStartNs
                    if (elapsedNs >= 500_000_000L) {
                        measuredFps = ((frameCount * 1_000_000_000.0) / elapsedNs)
                            .roundToInt()
                            .coerceAtLeast(1)
                        displayHz = readActiveDisplayHz(context)
                        frameCount = 0
                        windowStartNs = frameTimeNanos
                    }
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)

        onDispose {
            active = false
            choreographer.removeFrameCallback(callback)
            dm?.unregisterDisplayListener(displayListener)
        }
    }
    return measuredFps to displayHz
}

// ------------------------------------------------------------
// 2. Motore Liquid Glass iOS 26 a Doppia Pipeline Ottica
//
// PIPELINE A (IOS_FROSTED_GLASS_AGSL):
//   Concatenata tramite RenderEffect.createChainEffect(...) DOPO
//   RenderEffect.createBlurEffect(..., CLAMP) hardware di Skia.
//   -> Zero ghosting o sdoppiamento testi dietro Top Bar / Dock / Modali!
//   -> Menisco curvo Snell lungo i bordi + tinta scura traslucida calibrata
//      (~22% barre/capsule, ~45–48% modali) + bordo speculare razor-sharp ~0.9dp.
//
// PIPELINE B (IOS_LIQUID_DROP_AGSL):
//   Eseguita direttamente sul backdrop NITIDO non sfocato.
//   -> Attivata dinamicamente sul pulsante premuto o sullo scrubber trascinato:
//      centro cristallino con magnificazione convessa + menisco sferico Snell
//      + dispersione cromatica RGB prismatica sui bordi + rim speculare vivo.
// ------------------------------------------------------------

private const val IOS_FROSTED_GLASS_AGSL = """
uniform shader composable;
uniform float4 r0;
uniform float4 r1;
uniform float4 r2;
uniform float4 r3;
uniform float4 r4;
uniform float4 r5;
uniform float4 r6;
uniform float4 r7;
uniform float4 radA;
uniform float4 radB;
uniform float overlayMode;
uniform float density;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float4 evalShape(float2 fragCoord, float4 rect, float radius) {
    if (rect.z <= rect.x + 2.0 || rect.w <= rect.y + 2.0) {
        return float4(10000.0, 0.0, -1.0, 0.0);
    }
    float2 center = 0.5 * (rect.xy + rect.zw);
    float2 halfSize = 0.5 * (rect.zw - rect.xy);
    float rad = min(radius, min(halfSize.x, halfSize.y));
    float2 p = fragCoord - center;
    float d = sdRoundBox(p, halfSize, rad);

    float eps = 1.25;
    float gx = sdRoundBox(p + float2(eps, 0.0), halfSize, rad) - sdRoundBox(p - float2(eps, 0.0), halfSize, rad);
    float gy = sdRoundBox(p + float2(0.0, eps), halfSize, rad) - sdRoundBox(p - float2(0.0, eps), halfSize, rad);
    float len = length(float2(gx, gy));
    float2 n = len > 0.0001 ? float2(gx, gy) / len : float2(0.0, -1.0);
    float normY = clamp(p.y / max(halfSize.y, 1.0), -1.0, 1.0);
    return float4(d, n.x, n.y, normY);
}

float2 snellShift(float2 n, float sinA, float cosA, float eta) {
    float sin2T = eta * eta * sinA * sinA;
    float cosT = sqrt(max(1.0 - sin2T, 0.0001));
    float k = eta * cosA - cosT;
    float tz = max(eta - k * cosA, 0.25);
    return n * ((k * sinA) / tz);
}

half4 main(float2 fragCoord) {
    float4 best = evalShape(fragCoord, r0, radA.x);
    int activeId = 0;
    float4 activeRect = r0;
    float activeRad = radA.x;

    float4 g1 = evalShape(fragCoord, r1, radA.y);
    if (g1.x < best.x) { best = g1; activeId = 1; activeRect = r1; activeRad = radA.y; }

    float4 g2 = evalShape(fragCoord, r2, radA.z);
    if (g2.x < best.x) { best = g2; activeId = 2; activeRect = r2; activeRad = radA.z; }

    float4 g3 = evalShape(fragCoord, r3, radA.w);
    if (g3.x < best.x) { best = g3; activeId = 3; activeRect = r3; activeRad = radA.w; }

    float4 g4 = evalShape(fragCoord, r4, radB.x);
    if (g4.x < best.x) { best = g4; activeId = 4; activeRect = r4; activeRad = radB.x; }

    float4 g5 = evalShape(fragCoord, r5, radB.y);
    if (g5.x < best.x) { best = g5; activeId = 5; activeRect = r5; activeRad = radB.y; }

    float4 g6 = evalShape(fragCoord, r6, radB.z);
    if (g6.x < best.x) { best = g6; activeId = 6; activeRect = r6; activeRad = radB.z; }

    float4 g7 = evalShape(fragCoord, r7, radB.w);
    if (g7.x < best.x || (overlayMode > 0.5 && g7.x <= 1.2)) {
        best = g7;
        activeId = 7;
        activeRect = r7;
        activeRad = radB.w;
    }

    float d = best.x;
    if (d > 26.0 * density) {
        return half4(0.0);
    }

    float isOverlay = (activeId == 7 && overlayMode > 0.5) ? 1.0 : 0.0;
    float isSheet = (activeId == 7 && overlayMode > 1.5) ? 1.0 : 0.0;

    // Ombra ambientale morbida spostata verso il basso (premultiplied alpha)
    float2 shPos = fragCoord - float2(0.0, 4.5 * density);
    float dSh = evalShape(shPos, activeRect, activeRad).x;
    float shStrength = isOverlay > 0.5 ? 0.34 : 0.24;
    float shAlpha = dSh < 24.0 * density ? exp(-max(dSh, 0.0) / (9.5 * density)) * shStrength : 0.0;

    if (d > 0.85) {
        return half4(0.0, 0.0, 0.0, half(shAlpha));
    }

    float2 n = best.yz;
    float normY = best.w;
    float2 center = 0.5 * (activeRect.xy + activeRect.zw);
    float2 halfSize = max(0.5 * (activeRect.zw - activeRect.xy), float2(1.0));
    float2 localNorm = (fragCoord - center) / halfSize;
    float minHalf = min(halfSize.x, halfSize.y);

    float distIn = max(-d, 0.0);
    float bezelTarget = isSheet > 0.5 ? 24.0 : (isOverlay > 0.5 ? 20.0 : 15.0);
    float bezel = max(min(bezelTarget * density, minHalf * 0.72), 1.0);
    float t = clamp(distIn / bezel, 0.0, 1.0);
    float sinA = 1.0 - t;
    float z = sqrt(max(1.0 - sinA * sinA, 0.0001));

    // Rifrazione a menisco lungo il perimetro sul backdrop già hardware-blurred
    float edgeRefractPx = (isOverlay > 0.5 ? 8.0 : 10.5) * density;
    float2 centerComp = -localNorm * (float2(1.0, 1.6) * density) * z;
    float2 refractedPos = fragCoord + centerComp + snellShift(n, sinA, z, 1.0 / 1.52) * edgeRefractPx;

    half3 glassRgb = composable.eval(refractedPos).rgb;

    // Sottile dispersione cromatica sul bordo curvo del contenitore
    if (t < 0.72) {
        float2 posR = fragCoord + centerComp + snellShift(n, sinA, z, 1.0 / 1.43) * edgeRefractPx;
        float2 posB = fragCoord + centerComp + snellShift(n, sinA, z, 1.0 / 1.64) * edgeRefractPx;
        half rEdge = composable.eval(posR).r;
        half bEdge = composable.eval(posB).b;
        float chromaMix = smoothstep(0.72, 0.05, t) * (isOverlay > 0.5 ? 0.35 : 0.52);
        glassRgb.r = mix(glassRgb.r, rEdge, half(chromaMix));
        glassRgb.b = mix(glassRgb.b, bEdge, half(chromaMix));
    }

    // Vibrancy iOS 26 + Tinta scura traslucida calibrata (22% capsule, 44-48% modali)
    half luma = dot(glassRgb, half3(0.299, 0.587, 0.114));
    glassRgb = mix(half3(luma), glassRgb, half(isOverlay > 0.5 ? 1.28 : 1.38));
    half3 darkTint = half3(0.042, 0.046, 0.072);
    float tintStrength = isSheet > 0.5 ? 0.48 : (isOverlay > 0.5 ? 0.44 : 0.22);
    glassRgb = mix(glassRgb, darkTint, half(tintStrength));

    // Luce interna superiore e rimbalzo inferiore della lente 3D
    float topGlaze = smoothstep(0.35, -1.0, normY) * (isOverlay > 0.5 ? 0.045 : 0.068);
    float bottomBounce = smoothstep(0.55, 1.0, normY) * 0.025;
    glassRgb += half3(half(topGlaze + bottomBounce));

    // Ombra interna sottile di spessore vetro
    float innerGroove = smoothstep(0.8 * density, 2.4 * density, distIn) *
                        smoothstep(5.5 * density, 2.4 * density, distIn) * 0.045;
    glassRgb *= half(1.0 - innerGroove);

    // Bordo speculare razor-sharp (~0.9dp) + dual-rim Fresnel (alto-sx + basso-dx)
    float2 keyLight = normalize(float2(-0.55, -0.84));
    float2 rimLight = normalize(float2(0.55, 0.84));
    float fresnel = pow(max(1.0 - z, 0.0001), 2.1);
    float specKey = pow(max(dot(n, keyLight), 0.0001), 2.3);
    float specBack = pow(max(dot(n, rimLight), 0.0001), 3.2) * 0.50;
    float dirWeight = specKey + specBack;

    float hairlineRim = smoothstep(0.95 * density, 0.0, distIn);
    float softCaustic = smoothstep(4.5 * density, 0.0, distIn) * (0.06 + 0.12 * fresnel);

    float rimBase = isOverlay > 0.5 ? 0.14 : 0.19;
    float rimPeak = isOverlay > 0.5 ? 0.68 : 0.82;
    float specular = hairlineRim * (rimBase + rimPeak * dirWeight) +
                     softCaustic * (0.20 + 0.80 * specKey);

    glassRgb = clamp(glassRgb + half3(half(specular)), half3(0.0), half3(1.0));

    float insideMask = smoothstep(0.85, -0.85, d);
    float outAlpha = insideMask + shAlpha * (1.0 - insideMask);
    return half4(glassRgb * half(insideMask), half(outAlpha));
}
"""

private const val IOS_LIQUID_DROP_AGSL = """
uniform shader composable;
uniform float4 dropRect;
uniform float dropRadius;
uniform float strength;
uniform float density;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float4 evalDrop(float2 fragCoord, float4 rect, float radius) {
    if (rect.z <= rect.x + 2.0 || rect.w <= rect.y + 2.0) {
        return float4(10000.0, 0.0, -1.0, 0.0);
    }
    float2 center = 0.5 * (rect.xy + rect.zw);
    float2 halfSize = 0.5 * (rect.zw - rect.xy);
    float rad = min(radius, min(halfSize.x, halfSize.y));
    float2 p = fragCoord - center;
    float d = sdRoundBox(p, halfSize, rad);

    float eps = 1.20;
    float gx = sdRoundBox(p + float2(eps, 0.0), halfSize, rad) - sdRoundBox(p - float2(eps, 0.0), halfSize, rad);
    float gy = sdRoundBox(p + float2(0.0, eps), halfSize, rad) - sdRoundBox(p - float2(0.0, eps), halfSize, rad);
    float len = length(float2(gx, gy));
    float2 n = len > 0.0001 ? float2(gx, gy) / len : float2(0.0, -1.0);
    float normY = clamp(p.y / max(halfSize.y, 1.0), -1.0, 1.0);
    return float4(d, n.x, n.y, normY);
}

float2 snellShift(float2 n, float sinA, float cosA, float eta) {
    float sin2T = eta * eta * sinA * sinA;
    float cosT = sqrt(max(1.0 - sin2T, 0.0001));
    float k = eta * cosA - cosT;
    float tz = max(eta - k * cosA, 0.25);
    return n * ((k * sinA) / tz);
}

half4 main(float2 fragCoord) {
    if (strength <= 0.01) {
        return half4(0.0);
    }

    float4 g = evalDrop(fragCoord, dropRect, dropRadius);
    float d = g.x;
    if (d > 24.0 * density) {
        return half4(0.0);
    }

    // Ombra ambientale di sollevamento sotto la goccia liquida
    float2 shPos = fragCoord - float2(0.0, 5.0 * density);
    float dSh = evalDrop(shPos, dropRect, dropRadius).x;
    float shAlpha = dSh < 22.0 * density ? exp(-max(dSh, 0.0) / (8.5 * density)) * 0.30 * strength : 0.0;

    if (d > 0.85) {
        return half4(0.0, 0.0, 0.0, half(shAlpha));
    }

    float2 n = g.yz;
    float normY = g.w;
    float2 center = 0.5 * (dropRect.xy + dropRect.zw);
    float2 halfSize = max(0.5 * (dropRect.zw - dropRect.xy), float2(1.0));
    float2 localNorm = (fragCoord - center) / halfSize;
    float minHalf = min(halfSize.x, halfSize.y);

    float distIn = max(-d, 0.0);
    float bezel = max(min(20.0 * density, minHalf * 0.84), 1.0);
    float t = clamp(distIn / bezel, 0.0, 1.0);
    float sinA = 1.0 - t;
    float z = sqrt(max(1.0 - sinA * sinA, 0.0001));

    // Lente convessa cristallina + forte menisco Snell lungo il bordo
    float2 centerZoom = -localNorm * (float2(3.0, 3.6) * density * strength) * z;
    float refractDepthPx = 13.5 * density * strength;
    float etaR = 1.0 / 1.38;
    float etaG = 1.0 / 1.52;
    float etaB = 1.0 / 1.72;

    float2 refractedPos = fragCoord + centerZoom + snellShift(n, sinA, z, etaG) * refractDepthPx;
    half3 glassRgb = composable.eval(refractedPos).rgb;

    // Dispersione cromatica RGB prismatica sui bordi curvi della goccia
    if (t < 0.86) {
        float2 posR = fragCoord + centerZoom + snellShift(n, sinA, z, etaR) * refractDepthPx;
        float2 posB = fragCoord + centerZoom + snellShift(n, sinA, z, etaB) * refractDepthPx;
        half rEdge = composable.eval(posR).r;
        half bEdge = composable.eval(posB).b;
        float chromaMix = smoothstep(0.86, 0.04, t) * 0.92 * strength;
        glassRgb.r = mix(glassRgb.r, rEdge, half(chromaMix));
        glassRgb.b = mix(glassRgb.b, bEdge, half(chromaMix));
    }

    // Centro quasi cristallino con alta vibrancy e luminanza adattiva
    half luma = dot(glassRgb, half3(0.299, 0.587, 0.114));
    glassRgb = mix(half3(luma), glassRgb, half(1.48));
    float adaptiveGain = mix(1.12, 0.94, smoothstep(0.45, 0.85, float(luma)));
    glassRgb *= half(adaptiveGain);
    glassRgb += half3(0.022, 0.028, 0.038);

    // Riflessi interni da goccia di vetro sferica + rim speculare vivo
    float topGlaze = smoothstep(0.25, -1.0, normY) * 0.105;
    float bottomBounce = smoothstep(0.55, 1.0, normY) * 0.048;
    glassRgb += half3(half(topGlaze + bottomBounce));

    float2 keyLight = normalize(float2(-0.54, -0.84));
    float2 rimLight = normalize(float2(0.54, 0.84));
    float fresnel = pow(max(1.0 - z, 0.0001), 1.9);
    float specKey = pow(max(dot(n, keyLight), 0.0001), 2.1);
    float specBack = pow(max(dot(n, rimLight), 0.0001), 3.0) * 0.55;

    float hairlineRim = smoothstep(1.05 * density, 0.0, distIn);
    float softCaustic = smoothstep(5.5 * density, 0.0, distIn) * (0.10 + 0.20 * fresnel);
    float specular = hairlineRim * (0.24 + 0.96 * (specKey + specBack)) +
                     softCaustic * (0.22 + 0.78 * specKey);

    glassRgb = clamp(glassRgb + half3(half(specular)), half3(0.0), half3(1.0));

    float insideAlpha = smoothstep(0.85, -0.85, d) * strength;
    float outAlpha = insideAlpha + shAlpha * (1.0 - insideAlpha);
    return half4(glassRgb * half(insideAlpha), half(outAlpha));
}
"""

@Immutable
data class GlassRectSlots(
    val r0: Rect = Rect.Zero,
    val rad0: Float = 0f,
    val r1: Rect = Rect.Zero,
    val rad1: Float = 0f,
    val r2: Rect = Rect.Zero,
    val rad2: Float = 0f,
    val r3: Rect = Rect.Zero,
    val rad3: Float = 0f,
    val r4: Rect = Rect.Zero,
    val rad4: Float = 0f,
    val r5: Rect = Rect.Zero,
    val rad5: Float = 0f,
    val r6: Rect = Rect.Zero,
    val rad6: Float = 0f,
    val r7: Rect = Rect.Zero,
    val rad7: Float = 0f,
    val overlayMode: Int = 0
) {
    fun hasAnyRect(): Boolean {
        return !r0.isEmpty || !r1.isEmpty || !r2.isEmpty || !r3.isEmpty ||
            !r4.isEmpty || !r5.isEmpty || !r6.isEmpty || !r7.isEmpty
    }
}

private fun buildFrostedBackdropEffect(
    slots: GlassRectSlots,
    blurRadiusPx: Float,
    densityPx: Float
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !slots.hasAnyRect()) return null
    return try {
        val shader = RuntimeShader(IOS_FROSTED_GLASS_AGSL).apply {
            setFloatUniform("r0", slots.r0.left, slots.r0.top, slots.r0.right, slots.r0.bottom)
            setFloatUniform("r1", slots.r1.left, slots.r1.top, slots.r1.right, slots.r1.bottom)
            setFloatUniform("r2", slots.r2.left, slots.r2.top, slots.r2.right, slots.r2.bottom)
            setFloatUniform("r3", slots.r3.left, slots.r3.top, slots.r3.right, slots.r3.bottom)
            setFloatUniform("r4", slots.r4.left, slots.r4.top, slots.r4.right, slots.r4.bottom)
            setFloatUniform("r5", slots.r5.left, slots.r5.top, slots.r5.right, slots.r5.bottom)
            setFloatUniform("r6", slots.r6.left, slots.r6.top, slots.r6.right, slots.r6.bottom)
            setFloatUniform("r7", slots.r7.left, slots.r7.top, slots.r7.right, slots.r7.bottom)
            setFloatUniform("radA", slots.rad0, slots.rad1, slots.rad2, slots.rad3)
            setFloatUniform("radB", slots.rad4, slots.rad5, slots.rad6, slots.rad7)
            setFloatUniform("overlayMode", slots.overlayMode.toFloat())
            setFloatUniform("density", densityPx)
        }
        val blurEffect = RenderEffect.createBlurEffect(
            blurRadiusPx.coerceAtLeast(1f),
            blurRadiusPx.coerceAtLeast(1f),
            Shader.TileMode.CLAMP
        )
        val glassShaderEffect = RenderEffect.createRuntimeShaderEffect(shader, "composable")
        RenderEffect.createChainEffect(glassShaderEffect, blurEffect).asComposeRenderEffect()
    } catch (_: Throwable) {
        null
    }
}

private fun buildLiquidDropEffect(
    dropRect: Rect,
    dropRadiusPx: Float,
    strength: Float,
    densityPx: Float
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (strength <= 0.01f || dropRect.isEmpty) return null
    return try {
        val expandPx = 2.5f * densityPx * strength
        val expanded = Rect(
            left = dropRect.left - expandPx,
            top = dropRect.top - expandPx,
            right = dropRect.right + expandPx,
            bottom = dropRect.bottom + expandPx
        )
        val shader = RuntimeShader(IOS_LIQUID_DROP_AGSL).apply {
            setFloatUniform("dropRect", expanded.left, expanded.top, expanded.right, expanded.bottom)
            setFloatUniform("dropRadius", dropRadiusPx + expandPx)
            setFloatUniform("strength", strength.coerceIn(0f, 1f))
            setFloatUniform("density", densityPx)
        }
        RenderEffect.createRuntimeShaderEffect(shader, "composable").asComposeRenderEffect()
    } catch (_: Throwable) {
        null
    }
}

/**
 * Stato condiviso per tracciare quale controllo è premuto/trascinato e alimentare
 * la lente cristallina "Goccia Liquida" (Pipeline B) in tempo reale sul contenuto sottostante.
 */
@Stable
class LiquidGlassDropletController {
    var activeRect by mutableStateOf(Rect.Zero)
    var activeRadiusPx by mutableFloatStateOf(0f)
    var isActive by mutableStateOf(false)

    fun press(rect: Rect, radiusPx: Float) {
        if (!rect.isEmpty) {
            activeRect = rect
            activeRadiusPx = radiusPx
            isActive = true
        }
    }

    fun updateGeometryIfOwned(oldRect: Rect, newRect: Rect, radiusPx: Float) {
        if (isActive && activeRect == oldRect && !newRect.isEmpty) {
            activeRect = newRect
            activeRadiusPx = radiusPx
        }
    }

    fun releaseIfOwned(rect: Rect) {
        if (activeRect == rect) {
            isActive = false
        }
    }

    fun releaseAny() {
        isActive = false
    }
}

/**
 * Host unificato della doppia pipeline Liquid Glass con pacing GPU rispettoso del TargetFps:
 * - Registra una sola volta il backdrop (sfondo + testi) in `backdropLayer`
 * - Pipeline A: applica `frostedEffect` (Gaussian Blur hardware + menisco + rim speculare)
 * - Pipeline B: applica `dropletEffect` (Lente Liquida cristallina nitida) sull'area premuta
 */
@Composable
fun LiquidGlassBackdropHost(
    glassSlots: GlassRectSlots,
    frostedBlurDp: Dp,
    dropletController: LiquidGlassDropletController,
    targetFps: TargetFps,
    backdropDimAlpha: Float = 0f,
    onRootPositioned: (Offset) -> Unit,
    backdropContent: @Composable BoxScope.() -> Unit,
    foregroundContent: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val composeView = LocalView.current
    val densityPx = density.density
    val frostedBlurPx = with(density) { frostedBlurDp.toPx() }

    val dropletStrength by animateFloatAsState(
        targetValue = if (dropletController.isActive && !dropletController.activeRect.isEmpty) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 460f),
        label = "liquidDropletStrength"
    )

    val frostedEffect = remember(glassSlots, frostedBlurPx, densityPx) {
        buildFrostedBackdropEffect(glassSlots, frostedBlurPx, densityPx)
    }

    val dropletEffect = remember(
        dropletController.activeRect,
        dropletController.activeRadiusPx,
        dropletStrength,
        densityPx
    ) {
        buildLiquidDropEffect(
            dropRect = dropletController.activeRect,
            dropRadiusPx = dropletController.activeRadiusPx,
            strength = dropletStrength,
            densityPx = densityPx
        )
    }

    val backdropLayer = rememberGraphicsLayer()
    val frostedLayer = rememberGraphicsLayer()
    val dropletLayer = rememberGraphicsLayer()

    // Pacing GPU: evita di ri-eseguire il blur hardware oltre 30/60 FPS su pannelli a 120 Hz fissi,
    // garantendo comunque l'ultimo frame assestato tramite un solo redraw differito.
    val pacingState = remember {
        object {
            var lastFrostedUpdateNs = 0L
            var hasCachedFrosted = false
            var lastEffectIdentity: Any? = null
            var lastBackdropDim = -1f
            var deferredRunnable: Runnable? = null
        }
    }

    DisposableEffect(composeView) {
        onDispose {
            pacingState.deferredRunnable?.let { composeView.removeCallbacks(it) }
            pacingState.deferredRunnable = null
        }
    }

    val minGpuIntervalNs = when (targetFps) {
        TargetFps.FPS_30 -> 28_000_000L
        TargetFps.FPS_60 -> 13_500_000L
        TargetFps.FPS_120 -> 0L
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onRootPositioned(it.boundsInRoot().topLeft) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    backdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdropLayer)

                    if (backdropDimAlpha > 0.005f) {
                        drawRect(color = Color.Black.copy(alpha = backdropDimAlpha))
                    }

                    if (frostedEffect != null) {
                        val nowNs = System.nanoTime()
                        val elapsedNs = nowNs - pacingState.lastFrostedUpdateNs
                        val effectChanged = pacingState.lastEffectIdentity !== glassSlots ||
                            abs(pacingState.lastBackdropDim - backdropDimAlpha) > 0.01f

                        if (!pacingState.hasCachedFrosted || effectChanged || elapsedNs >= minGpuIntervalNs) {
                            pacingState.deferredRunnable?.let { composeView.removeCallbacks(it) }
                            pacingState.deferredRunnable = null

                            frostedLayer.renderEffect = frostedEffect
                            frostedLayer.record {
                                drawLayer(backdropLayer)
                            }
                            pacingState.lastFrostedUpdateNs = nowNs
                            pacingState.hasCachedFrosted = true
                            pacingState.lastEffectIdentity = glassSlots
                            pacingState.lastBackdropDim = backdropDimAlpha
                        } else if (pacingState.deferredRunnable == null) {
                            val remainMs = ((minGpuIntervalNs - elapsedNs) / 1_000_000L).coerceIn(2L, 34L)
                            val task = Runnable {
                                pacingState.deferredRunnable = null
                                composeView.invalidate()
                            }
                            pacingState.deferredRunnable = task
                            composeView.postDelayed(task, remainMs)
                        }
                        drawLayer(frostedLayer)
                    }

                    if (dropletEffect != null) {
                        dropletLayer.renderEffect = dropletEffect
                        dropletLayer.record {
                            drawLayer(backdropLayer)
                        }
                        drawLayer(dropletLayer)
                    }
                }
        ) {
            backdropContent()
        }

        foregroundContent()
    }
}

// ------------------------------------------------------------
// 3. Controlli Liquid Glass Interattivi con "Goccia Liquida" al Tocco
// ------------------------------------------------------------

private val GlassTextShadowStyle = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.42f),
        offset = Offset(0f, 1.5f),
        blurRadius = 6f
    )
)

fun Modifier.iosGlassButton(
    shape: Shape = CircleShape,
    elevated: Boolean = false,
    pressed: Boolean = false,
    transparentForDroplet: Boolean = false
): Modifier = this
    .clip(shape)
    .background(
        if (transparentForDroplet && pressed) {
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.06f),
                1.0f to Color.Transparent
            )
        } else {
            Brush.verticalGradient(
                0.0f to Color.White.copy(
                    alpha = when {
                        pressed -> 0.14f
                        elevated -> 0.22f
                        else -> 0.11f
                    }
                ),
                0.38f to Color.White.copy(
                    alpha = when {
                        pressed -> 0.03f
                        elevated -> 0.07f
                        else -> 0.03f
                    }
                ),
                0.70f to Color.White.copy(alpha = 0.01f),
                1.0f to Color.White.copy(
                    alpha = when {
                        pressed -> 0.08f
                        elevated -> 0.11f
                        else -> 0.05f
                    }
                )
            )
        }
    )
    .border(
        width = 0.8.dp,
        brush = Brush.linearGradient(
            0.0f to Color.White.copy(
                alpha = when {
                    pressed -> 0.92f
                    elevated -> 0.72f
                    else -> 0.48f
                }
            ),
            0.45f to Color.White.copy(alpha = if (pressed) 0.24f else 0.08f),
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

@Composable
private fun LiquidGlassDetachedCircle(
    size: Dp,
    enabled: Boolean = true,
    elevated: Boolean = false,
    rootTopLeft: Offset,
    dropletController: LiquidGlassDropletController,
    onRectMeasured: (Rect) -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    var localRect by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { (size / 2f).toPx() }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(enabled && pressed, localRect) {
        if (enabled && pressed && !localRect.isEmpty) {
            dropletController.press(localRect, radiusPx)
        } else {
            dropletController.releaseIfOwned(localRect)
        }
    }

    DisposableEffect(Unit) {
        onDispose { dropletController.releaseIfOwned(localRect) }
    }

    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 1.09f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 460f),
        label = "detachedCircleDrop"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .onGloballyPositioned { coords ->
                val old = localRect
                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                localRect = measured
                onRectMeasured(measured)
                dropletController.updateGeometryIfOwned(old, measured, radiusPx)
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .then(
                if (elevated && !pressed) {
                    Modifier.background(Color.White.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
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
private fun LiquidGlassDetachedPill(
    label: String,
    font: FontFamily,
    width: Dp,
    height: Dp,
    enabled: Boolean = true,
    tintColor: Color = Color.Transparent,
    labelColor: Color = Color.White,
    rootTopLeft: Offset,
    dropletController: LiquidGlassDropletController,
    onRectMeasured: (Rect) -> Unit,
    onClick: () -> Unit
) {
    var localRect by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { (height / 2f).toPx() }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(enabled && pressed, localRect) {
        if (enabled && pressed && !localRect.isEmpty) {
            dropletController.press(localRect, radiusPx)
        } else {
            dropletController.releaseIfOwned(localRect)
        }
    }

    DisposableEffect(Unit) {
        onDispose { dropletController.releaseIfOwned(localRect) }
    }

    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 1.07f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 460f),
        label = "detachedPillDrop"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = width, height = height)
            .onGloballyPositioned { coords ->
                val old = localRect
                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                localRect = measured
                onRectMeasured(measured)
                dropletController.updateGeometryIfOwned(old, measured, radiusPx)
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(if (!pressed) tintColor else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = label,
            fontFamily = font,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = (-0.2).sp,
            color = labelColor,
            style = GlassTextShadowStyle,
            maxLines = 1
        )
    }
}

@Composable
private fun LiquidGlassCapsuleMediaControl(
    width: Dp,
    height: Dp,
    elevated: Boolean = false,
    enabled: Boolean = true,
    rootTopLeft: Offset,
    dropletController: LiquidGlassDropletController,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    var localRect by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { (height / 2f).toPx() }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(enabled && pressed, localRect) {
        if (enabled && pressed && !localRect.isEmpty) {
            dropletController.press(localRect, radiusPx)
        } else {
            dropletController.releaseIfOwned(localRect)
        }
    }

    DisposableEffect(Unit) {
        onDispose { dropletController.releaseIfOwned(localRect) }
    }

    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 1.10f else 1f,
        animationSpec = spring(dampingRatio = 0.50f, stiffness = 460f),
        label = "mediaCapsuleDrop"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = width, height = height)
            .onGloballyPositioned { coords ->
                val old = localRect
                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                localRect = measured
                dropletController.updateGeometryIfOwned(old, measured, radiusPx)
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .iosGlassButton(
                shape = RoundedCornerShape(50),
                elevated = elevated,
                pressed = enabled && pressed,
                transparentForDroplet = true
            )
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
// 7. Schermata Avvio / Permessi Ridisegnata in Vero Liquid Glass
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
    val targetFps by AppPreferences.targetFps.collectAsState()
    val appInfoDone by AppPreferences.appInfoStepDone.collectAsState()
    var postNotifOk by remember {
        mutableStateOf(BitChordListenerService.areNotificationsEnabled(context))
    }
    var listenerGranted by remember {
        mutableStateOf(BitChordListenerService.isNotificationAccessGranted(context))
    }
    var awaitingAppInfoReturn by remember { mutableStateOf(false) }

    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var badgeRect by remember { mutableStateOf(Rect.Zero) }
    var card1Rect by remember { mutableStateOf(Rect.Zero) }
    var card2Rect by remember { mutableStateOf(Rect.Zero) }
    var card3Rect by remember { mutableStateOf(Rect.Zero) }
    var btn1Rect by remember { mutableStateOf(Rect.Zero) }
    var btn2Rect by remember { mutableStateOf(Rect.Zero) }
    var btn3Rect by remember { mutableStateOf(Rect.Zero) }

    val dropletController = remember { LiquidGlassDropletController() }

    val cardRadiusPx = with(density) { 28.dp.toPx() }
    val badgeRadiusPx = with(density) { 24.dp.toPx() }
    val pillRadiusPx = with(density) { 20.dp.toPx() }

    val glassSlots = remember(
        badgeRect,
        card1Rect,
        card2Rect,
        card3Rect,
        btn1Rect,
        btn2Rect,
        btn3Rect,
        density.density
    ) {
        GlassRectSlots(
            r0 = badgeRect,
            rad0 = badgeRadiusPx,
            r1 = card1Rect,
            rad1 = cardRadiusPx,
            r2 = card2Rect,
            rad2 = cardRadiusPx,
            r3 = card3Rect,
            rad3 = cardRadiusPx,
            r4 = btn1Rect,
            rad4 = pillRadiusPx,
            r5 = btn2Rect,
            rad5 = pillRadiusPx,
            r6 = btn3Rect,
            rad6 = pillRadiusPx,
            overlayMode = 0
        )
    }

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

    LiquidGlassBackdropHost(
        glassSlots = glassSlots,
        frostedBlurDp = 18.dp,
        dropletController = dropletController,
        targetFps = targetFps,
        onRootPositioned = { rootTopLeft = it },
        backdropContent = {
            LiquidAmbientBackground(cover = null, palette = CoverPalette())
        },
        foregroundContent = {
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
                // Header pulito in alto con icona bolla testi dentro squircle Liquid Glass
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(76.dp)
                        .onGloballyPositioned {
                            badgeRect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                        }
                        .clip(RoundedCornerShape(24.dp))
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
                    font = font,
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
                    onCardRect = { card1Rect = it },
                    onPillRect = { btn1Rect = it },
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
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
                    onCardRect = { card2Rect = it },
                    onPillRect = { btn2Rect = it },
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
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
                    onCardRect = { card3Rect = it },
                    onPillRect = { btn3Rect = it },
                    onClick = { openNotificationListenerSettings(context) }
                )
            }
        }
    )
}

@Composable
private fun PermissionLiquidGlassCard(
    stepNumber: String,
    title: String,
    subtitle: String,
    buttonLabel: String,
    completed: Boolean,
    font: FontFamily,
    rootTopLeft: Offset,
    dropletController: LiquidGlassDropletController,
    onCardRect: (Rect) -> Unit,
    onPillRect: (Rect) -> Unit,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                onCardRect(it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y))
            }
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

        LiquidGlassDetachedPill(
            label = buttonLabel,
            font = font,
            width = 86.dp,
            height = 38.dp,
            tintColor = if (completed) Color(0xFF22C55E).copy(alpha = 0.24f) else Color.Transparent,
            labelColor = if (completed) Color(0xFF86EFAC) else Color.White,
            rootTopLeft = rootTopLeft,
            dropletController = dropletController,
            onRectMeasured = onPillRect,
            onClick = onClick
        )
    }
}

// ------------------------------------------------------------
// 8. Player Lyrics iOS 26 (Top Capsula + Cerchi Staccati + Dock Snello)
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
    var topCapsuleRect by remember { mutableStateOf(Rect.Zero) }
    var closeCircleRect by remember { mutableStateOf(Rect.Zero) }
    var menuCircleRect by remember { mutableStateOf(Rect.Zero) }
    var progressPillRect by remember { mutableStateOf(Rect.Zero) }
    var syncMinusPillRect by remember { mutableStateOf(Rect.Zero) }
    var mediaCapsuleRect by remember { mutableStateOf(Rect.Zero) }
    var syncPlusPillRect by remember { mutableStateOf(Rect.Zero) }
    var quickLaunchRect by remember { mutableStateOf(Rect.Zero) }
    var overlayRect by remember { mutableStateOf(Rect.Zero) }
    var overlayRadiusDp by remember { mutableStateOf(26.dp) }

    val dropletController = remember { LiquidGlassDropletController() }

    LaunchedEffect(menuExpanded, showSettingsSheet) {
        if (!menuExpanded && !showSettingsSheet) {
            overlayRect = Rect.Zero
        }
    }

    LaunchedEffect(lines.isEmpty(), title) {
        if (lines.isNotEmpty() || title.isNotBlank()) {
            quickLaunchRect = Rect.Zero
        }
    }

    val capsuleRadiusPx = with(density) { 29.dp.toPx() }
    val topCircleRadiusPx = with(density) { 26.dp.toPx() }
    val progressRadiusPx = with(density) { 19.dp.toPx() }
    val auxSlot7Rect = when {
        showSettingsSheet || menuExpanded -> overlayRect
        lines.isEmpty() && title.isBlank() -> quickLaunchRect
        else -> Rect.Zero
    }
    val auxSlot7RadiusPx = when {
        showSettingsSheet || menuExpanded -> with(density) { overlayRadiusDp.toPx() }
        else -> with(density) { 25.dp.toPx() }
    }
    val overlayMode = when {
        showSettingsSheet -> 2
        menuExpanded -> 1
        else -> 0
    }

    val glassSlots = remember(
        topCapsuleRect,
        closeCircleRect,
        menuCircleRect,
        progressPillRect,
        syncMinusPillRect,
        mediaCapsuleRect,
        syncPlusPillRect,
        auxSlot7Rect,
        auxSlot7RadiusPx,
        overlayMode,
        showCloseButton,
        showSettingsSheet,
        density.density
    ) {
        GlassRectSlots(
            r0 = if (showSettingsSheet) Rect.Zero else topCapsuleRect,
            rad0 = topCircleRadiusPx,
            r1 = if (showSettingsSheet || !showCloseButton) Rect.Zero else closeCircleRect,
            rad1 = topCircleRadiusPx,
            r2 = if (showSettingsSheet) Rect.Zero else menuCircleRect,
            rad2 = topCircleRadiusPx,
            r3 = if (showSettingsSheet) Rect.Zero else progressPillRect,
            rad3 = progressRadiusPx,
            r4 = if (showSettingsSheet) Rect.Zero else syncMinusPillRect,
            rad4 = capsuleRadiusPx,
            r5 = if (showSettingsSheet) Rect.Zero else mediaCapsuleRect,
            rad5 = capsuleRadiusPx,
            r6 = if (showSettingsSheet) Rect.Zero else syncPlusPillRect,
            rad6 = capsuleRadiusPx,
            r7 = auxSlot7Rect,
            rad7 = auxSlot7RadiusPx,
            overlayMode = overlayMode
        )
    }

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

    // Cadenza esatta sincronizzata al blocco 30 / 60 / 120 FPS scelto dall'utente
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

    val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiquidGlassBackdropHost(
        glassSlots = glassSlots,
        frostedBlurDp = if (showSettingsSheet || menuExpanded) 21.dp else 15.dp,
        dropletController = dropletController,
        targetFps = targetFps,
        backdropDimAlpha = if (showSettingsSheet) 0.34f else 0f,
        onRootPositioned = { rootTopLeft = it },
        backdropContent = {
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

            // Sfumature pulite sotto status bar e gesture bar
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.56f),
                            1.0f to Color.Transparent
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.44f)
                        )
                    )
            )
        },
        foregroundContent = {
            // CTA AVVIO RAPIDO LIQUID GLASS QUANDO NON C'È MUSICA ATTIVA
            if (lines.isEmpty() && title.isBlank() && !showSettingsSheet && !menuExpanded) {
                val launchLabel = if (preferredPlayer == PreferredPlayer.AUTO) {
                    "Avvia Player"
                } else {
                    "Avvia ${preferredPlayer.label}"
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 110.dp)
                ) {
                    var ctaRect by remember { mutableStateOf(Rect.Zero) }
                    val ctaRadiusPx = with(density) { 25.dp.toPx() }
                    val ctaInteraction = remember { MutableInteractionSource() }
                    val ctaPressed by ctaInteraction.collectIsPressedAsState()

                    LaunchedEffect(ctaPressed, ctaRect) {
                        if (ctaPressed && !ctaRect.isEmpty) {
                            dropletController.press(ctaRect, ctaRadiusPx)
                        } else {
                            dropletController.releaseIfOwned(ctaRect)
                        }
                    }

                    val ctaScale by animateFloatAsState(
                        targetValue = if (ctaPressed) 1.07f else 1f,
                        animationSpec = spring(dampingRatio = 0.52f, stiffness = 460f),
                        label = "quickLaunchDrop"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(50.dp)
                            .onGloballyPositioned { coords ->
                                val old = ctaRect
                                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                                ctaRect = measured
                                quickLaunchRect = measured
                                dropletController.updateGeometryIfOwned(old, measured, ctaRadiusPx)
                            }
                            .graphicsLayer {
                                scaleX = ctaScale
                                scaleY = ctaScale
                            }
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

            // TOP BAR A DUE MODULI STACCATI (Capsula + Cerchi fluttuanti da 10.dp di stacco)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (showSettingsSheet) 0f else 1f }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .onGloballyPositioned {
                            topCapsuleRect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                        }
                        .clip(RoundedCornerShape(50))
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

                if (showCloseButton && onClose != null) {
                    Spacer(Modifier.width(10.dp))
                    LiquidGlassDetachedCircle(
                        size = 52.dp,
                        enabled = !showSettingsSheet,
                        rootTopLeft = rootTopLeft,
                        dropletController = dropletController,
                        onRectMeasured = { closeCircleRect = it },
                        onClick = onClose
                    ) {
                        CloseIcon(size = 14.dp)
                    }
                }

                Spacer(Modifier.width(10.dp))

                LiquidGlassDetachedCircle(
                    size = 52.dp,
                    enabled = !showSettingsSheet,
                    elevated = menuExpanded,
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
                    onRectMeasured = { menuCircleRect = it },
                    onClick = { menuExpanded = !menuExpanded }
                ) {
                    HamburgerIcon(size = 18.dp)
                }
            }

            // BOTTOM DOCK SNELLE A DUE LIVELLI (Mini-Scrubber + Capsula Controlli + Pillole Sync)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (showSettingsSheet) 0f else 1f }
            ) {
                LiquidScrubberMiniPill(
                    durationMs = durationMs,
                    syncOffsetMs = syncOffsetMs,
                    font = font,
                    enabled = !showSettingsSheet,
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
                    rawPosProvider = { rawPosState.longValue },
                    onPillMeasured = { progressPillRect = it },
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
                    LiquidGlassDetachedPill(
                        label = "-0.5s",
                        font = font,
                        width = 64.dp,
                        height = 58.dp,
                        enabled = !showSettingsSheet,
                        rootTopLeft = rootTopLeft,
                        dropletController = dropletController,
                        onRectMeasured = { syncMinusPillRect = it },
                        onClick = { LyricsPlaybackStore.syncOffsetMs.value -= 500L }
                    )

                    Spacer(Modifier.width(10.dp))

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .onGloballyPositioned {
                                mediaCapsuleRect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                            }
                            .clip(RoundedCornerShape(50))
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiquidGlassCapsuleMediaControl(
                            width = 44.dp,
                            height = 44.dp,
                            elevated = false,
                            enabled = !showSettingsSheet,
                            rootTopLeft = rootTopLeft,
                            dropletController = dropletController,
                            onClick = { LyricsPlaybackStore.onPrevious?.invoke() }
                        ) {
                            SkipPrevIcon(size = 18.dp)
                        }

                        LiquidGlassCapsuleMediaControl(
                            width = 62.dp,
                            height = 46.dp,
                            elevated = true,
                            enabled = !showSettingsSheet,
                            rootTopLeft = rootTopLeft,
                            dropletController = dropletController,
                            onClick = {
                                val handler = LyricsPlaybackStore.onPlayPause
                                if (handler != null) {
                                    handler.invoke()
                                } else {
                                    BitChordListenerService.triggerPlayPreferred(context)
                                }
                            }
                        ) {
                            if (isPlaying) PauseIcon(size = 22.dp) else PlayIcon(size = 22.dp)
                        }

                        LiquidGlassCapsuleMediaControl(
                            width = 44.dp,
                            height = 44.dp,
                            elevated = false,
                            enabled = !showSettingsSheet,
                            rootTopLeft = rootTopLeft,
                            dropletController = dropletController,
                            onClick = { LyricsPlaybackStore.onNext?.invoke() }
                        ) {
                            SkipNextIcon(size = 18.dp)
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    LiquidGlassDetachedPill(
                        label = "+0.5s",
                        font = font,
                        width = 64.dp,
                        height = 58.dp,
                        enabled = !showSettingsSheet,
                        rootTopLeft = rootTopLeft,
                        dropletController = dropletController,
                        onRectMeasured = { syncPlusPillRect = it },
                        onClick = { LyricsPlaybackStore.syncOffsetMs.value += 500L }
                    )
                }
            }

            // POPOVER MENU HAMBURGER
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

                Column(
                    modifier = Modifier
                        .width(236.dp)
                        .onGloballyPositioned {
                            if (menuExpanded) {
                                overlayRadiusDp = 26.dp
                                overlayRect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                            }
                        }
                        .clip(RoundedCornerShape(26.dp))
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
                                overlayRect = Rect.Zero
                                dropletController.releaseAny()
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
                        .background(Color(0xFF0A261D).copy(alpha = 0.68f))
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
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
                    onSheetPositioned = { rect ->
                        if (showSettingsSheet) {
                            overlayRadiusDp = 36.dp
                            overlayRect = rect.translate(-rootTopLeft.x, -rootTopLeft.y)
                        }
                    },
                    onDismiss = {
                        dropletController.releaseAny()
                        showSettingsSheet = false
                    }
                )
            }
        }
    )
}

@Composable
private fun LiquidScrubberMiniPill(
    durationMs: Long,
    syncOffsetMs: Long,
    font: FontFamily,
    enabled: Boolean,
    rootTopLeft: Offset,
    dropletController: LiquidGlassDropletController,
    rawPosProvider: () -> Long,
    onPillMeasured: (Rect) -> Unit,
    onResetSync: () -> Unit
) {
    val density = LocalDensity.current
    val safeDuration = durationMs.coerceAtLeast(1L)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackBounds by remember { mutableStateOf(Rect.Zero) }

    val rawPosMs = rawPosProvider()
    val liveFraction = (rawPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val shownFraction = if (isDragging) dragFraction else liveFraction
    val shownTimeMs = if (isDragging) (dragFraction * safeDuration).toLong() else rawPosMs

    val dropletHalfW = with(density) { 18.dp.toPx() }
    val dropletHalfH = with(density) { 13.dp.toPx() }
    val dropletRadiusPx = with(density) { 13.dp.toPx() }

    fun updateScrubberDroplet(fraction: Float, active: Boolean) {
        if (!active || trackBounds.isEmpty) {
            dropletController.releaseAny()
            return
        }
        val cx = trackBounds.left + trackBounds.width * fraction.coerceIn(0f, 1f)
        val cy = trackBounds.center.y
        val thumbRect = Rect(
            left = cx - dropletHalfW,
            top = cy - dropletHalfH,
            right = cx + dropletHalfW,
            bottom = cy + dropletHalfH
        )
        dropletController.press(thumbRect, dropletRadiusPx)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .onGloballyPositioned {
                onPillMeasured(it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y))
            }
            .clip(RoundedCornerShape(50))
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
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onGloballyPositioned {
                    trackBounds = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                }
                .pointerInput(safeDuration, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        isDragging = true
                        dragFraction = (down.position.x / w).coerceIn(0f, 1f)
                        updateScrubberDroplet(dragFraction, true)

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
                            updateScrubberDroplet(dragFraction, true)
                        }

                        val targetSeekMs = (dragFraction * safeDuration).toLong().coerceIn(0L, safeDuration)
                        updateScrubberDroplet(dragFraction, false)
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
                .clickable(enabled = enabled, onClick = onResetSync)
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
// 9. Modale Impostazioni & Verifica Stato (con blocco 30/60/120 FPS + Choreographer)
// ------------------------------------------------------------
@Composable
private fun LiquidSettingsModal(
    font: FontFamily,
    rootTopLeft: Offset,
    dropletController: LiquidGlassDropletController,
    onSheetPositioned: (Rect) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val composeView = LocalView.current
    val density = LocalDensity.current
    val preferredPlayer by AppPreferences.preferredPlayer.collectAsState()
    val defaultOffsetMs by AppPreferences.defaultSyncOffsetMs.collectAsState()
    val targetFps by AppPreferences.targetFps.collectAsState()
    val activePkg by LyricsPlaybackStore.activePlayerPackage.collectAsState()
    val lastPkg by AppPreferences.lastActiveMusicPackage.collectAsState()

    val notifGranted = BitChordListenerService.isNotificationAccessGranted(context)
    val postNotifOk = BitChordListenerService.areNotificationsEnabled(context)
    val serviceAlive by BitChordListenerService.isServiceConnected.collectAsState()
    val (measuredFps, displayHz) = rememberFrameRateTelemetry()

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
                .onGloballyPositioned { onSheetPositioned(it.boundsInRoot()) }
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
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
                    onClick = onDismiss
                ) {
                    CloseIcon(size = 13.dp)
                }
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

            val chipRadiusPx = with(density) { 21.dp.toPx() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TargetFps.entries.forEach { option ->
                    val selected = option == targetFps
                    var chipRect by remember { mutableStateOf(Rect.Zero) }
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()

                    LaunchedEffect(pressed, chipRect) {
                        if (pressed && !chipRect.isEmpty) {
                            dropletController.press(chipRect, chipRadiusPx)
                        } else {
                            dropletController.releaseIfOwned(chipRect)
                        }
                    }

                    val chipScale by animateFloatAsState(
                        targetValue = if (pressed) 1.06f else 1f,
                        animationSpec = spring(dampingRatio = 0.52f, stiffness = 460f),
                        label = "fpsChipDrop"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                val old = chipRect
                                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                                chipRect = measured
                                dropletController.updateGeometryIfOwned(old, measured, chipRadiusPx)
                            }
                            .graphicsLayer {
                                scaleX = chipScale
                                scaleY = chipScale
                            }
                            .iosGlassButton(
                                shape = RoundedCornerShape(50),
                                elevated = selected,
                                pressed = pressed,
                                transparentForDroplet = true
                            )
                            .background(
                                if (selected && !pressed) Color(0xFF6366F1).copy(alpha = 0.46f)
                                else Color.Transparent
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
                                if (measuredFps > 0 && abs(measuredFps - targetFps.fps) <= 6) Color(0xFF4ADE80)
                                else Color(0xFF93C5FD)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Display: ${displayHz} Hz • Reali: ${if (measuredFps > 0) "$measuredFps FPS" else "…"}",
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
                    var playerRect by remember { mutableStateOf(Rect.Zero) }
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()

                    LaunchedEffect(pressed, playerRect) {
                        if (pressed && !playerRect.isEmpty) {
                            dropletController.press(playerRect, chipRadiusPx)
                        } else {
                            dropletController.releaseIfOwned(playerRect)
                        }
                    }

                    val chipScale by animateFloatAsState(
                        targetValue = if (pressed) 1.06f else 1f,
                        animationSpec = spring(dampingRatio = 0.52f, stiffness = 460f),
                        label = "playerChipDrop"
                    )

                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                val old = playerRect
                                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                                playerRect = measured
                                dropletController.updateGeometryIfOwned(old, measured, chipRadiusPx)
                            }
                            .graphicsLayer {
                                scaleX = chipScale
                                scaleY = chipScale
                            }
                            .iosGlassButton(
                                RoundedCornerShape(50),
                                elevated = selected,
                                pressed = pressed,
                                transparentForDroplet = true
                            )
                            .background(
                                if (selected && !pressed) Color(0xFF6366F1).copy(alpha = 0.46f)
                                else Color.Transparent
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
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
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
                    rootTopLeft = rootTopLeft,
                    dropletController = dropletController,
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
// 10. Pulsanti Interattivi con Goccia Liquida & Icone
// ------------------------------------------------------------
@Composable
private fun GlassPillTextButton(
    label: String,
    font: FontFamily,
    rootTopLeft: Offset = Offset.Zero,
    dropletController: LiquidGlassDropletController? = null,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    var localRect by remember { mutableStateOf(Rect.Zero) }
    val radiusPx = with(density) { 18.dp.toPx() }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed, localRect) {
        if (dropletController != null) {
            if (pressed && !localRect.isEmpty) {
                dropletController.press(localRect, radiusPx)
            } else {
                dropletController.releaseIfOwned(localRect)
            }
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.07f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 480f),
        label = "pillScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .onGloballyPositioned { coords ->
                val old = localRect
                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                localRect = measured
                dropletController?.updateGeometryIfOwned(old, measured, radiusPx)
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .iosGlassButton(
                RoundedCornerShape(50),
                elevated = pressed,
                pressed = pressed,
                transparentForDroplet = dropletController != null
            )
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
    rootTopLeft: Offset = Offset.Zero,
    dropletController: LiquidGlassDropletController? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    var localRect by remember { mutableStateOf(Rect.Zero) }
    val radiusPx = with(density) { (size / 2f).toPx() }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(enabled && pressed, localRect) {
        if (dropletController != null) {
            if (enabled && pressed && !localRect.isEmpty) {
                dropletController.press(localRect, radiusPx)
            } else {
                dropletController.releaseIfOwned(localRect)
            }
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 1.09f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 480f),
        label = "circleScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .onGloballyPositioned { coords ->
                val old = localRect
                val measured = coords.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                localRect = measured
                dropletController?.updateGeometryIfOwned(old, measured, radiusPx)
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .iosGlassButton(
                shape = CircleShape,
                elevated = elevated || (enabled && pressed),
                pressed = enabled && pressed,
                transparentForDroplet = dropletController != null
            )
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

private fun formatPlayerTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
