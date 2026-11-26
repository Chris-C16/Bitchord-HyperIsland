package com.bitchord.island

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
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
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------
// AGSL Optical Liquid Glass Shader (Android 13+ / HyperOS 3)
// ------------------------------------------------------------
private const val LIQUID_GLASS_AGSL = """
uniform shader composable;
uniform float4 rect0;
uniform float radius0;
uniform float4 rect1;
uniform float radius1;
uniform float4 rect2;
uniform float radius2;
uniform float density;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float3 glassSdf(float2 fragCoord, float4 rect, float radius) {
    if (rect.z <= rect.x + 2.0 || rect.w <= rect.y + 2.0) {
        return float3(10000.0, 0.0, 0.0);
    }
    float2 center = 0.5 * (rect.xy + rect.zw);
    float2 halfSize = 0.5 * (rect.zw - rect.xy);
    float r = min(radius, min(halfSize.x, halfSize.y));
    float2 p = fragCoord - center;
    float d = sdRoundBox(p, halfSize, r);

    float eps = 1.6;
    float gx = sdRoundBox(p + float2(eps, 0.0), halfSize, r) - sdRoundBox(p - float2(eps, 0.0), halfSize, r);
    float gy = sdRoundBox(p + float2(0.0, eps), halfSize, r) - sdRoundBox(p - float2(0.0, eps), halfSize, r);
    float len = length(float2(gx, gy));
    float2 n = len > 0.0001 ? float2(gx, gy) / len : float2(0.0, -1.0);
    return float3(d, n.x, n.y);
}

half4 sampleGlassBlur(float2 pos, float blurR) {
    half4 c = composable.eval(pos) * half(0.28);
    float2 o1 = float2(blurR, 0.0);
    float2 o2 = float2(0.0, blurR);
    float2 o3 = float2(blurR * 0.707, blurR * 0.707);
    float2 o4 = float2(blurR * 0.707, -blurR * 0.707);
    c += composable.eval(pos + o1) * half(0.09);
    c += composable.eval(pos - o1) * half(0.09);
    c += composable.eval(pos + o2) * half(0.09);
    c += composable.eval(pos - o2) * half(0.09);
    c += composable.eval(pos + o3) * half(0.09);
    c += composable.eval(pos - o3) * half(0.09);
    c += composable.eval(pos + o4) * half(0.09);
    c += composable.eval(pos - o4) * half(0.09);
    return c;
}

half4 main(float2 fragCoord) {
    half4 baseColor = composable.eval(fragCoord);

    float3 g0 = glassSdf(fragCoord, rect0, radius0);
    float3 g1 = glassSdf(fragCoord, rect1, radius1);
    float3 g2 = glassSdf(fragCoord, rect2, radius2);

    float3 best = g0;
    if (g1.x < best.x) best = g1;
    if (g2.x < best.x) best = g2;
    if (g2.x <= 1.5) best = g2;

    float d = best.x;
    float2 n = best.yz;

    if (d > 22.0 * density) {
        return baseColor;
    }

    if (d > 1.0) {
        float sh = exp(-d / (8.0 * density)) * 0.36;
        return half4(baseColor.rgb * half(1.0 - sh), baseColor.a);
    }

    float distIn = max(-d, 0.0);
    float bevel = 26.0 * density;
    float edgeT = clamp(1.0 - distIn / bevel, 0.0, 1.0);
    float curve = edgeT * edgeT * edgeT;

    float refractMag = curve * 30.0 * density;
    float2 refractedPos = fragCoord - n * refractMag;

    float chromaMag = curve * 5.0 * density;
    float blurR = (2.4 + 2.8 * (1.0 - edgeT)) * density;

    half rChan = sampleGlassBlur(refractedPos + n * chromaMag, blurR).r;
    half gChan = sampleGlassBlur(refractedPos, blurR).g;
    half bChan = sampleGlassBlur(refractedPos - n * chromaMag, blurR).b;
    half3 glassRgb = half3(rChan, gChan, bChan);

    half luma = dot(glassRgb, half3(0.299, 0.587, 0.114));
    glassRgb = mix(half3(luma), glassRgb, half(1.26));
    glassRgb = glassRgb * half(1.08) + half3(0.045);

    float2 lightDir = normalize(float2(-0.58, -0.81));
    float specPrimary = pow(max(dot(n, lightDir), 0.0), 2.1);
    float specSecondary = pow(max(dot(n, -lightDir), 0.0), 2.5) * 0.72;
    float rimLine = smoothstep(2.8 * density, 0.0, distIn);
    float innerCaustic = smoothstep(14.0 * density, 0.0, distIn) * 0.24;

    half specular = half(
        rimLine * (0.34 + 0.66 * (specPrimary + specSecondary)) +
        innerCaustic * (0.30 + 0.70 * specPrimary)
    );
    glassRgb = clamp(glassRgb + half3(specular), half3(0.0), half3(1.0));

    float insideMask = smoothstep(1.0, -1.0, d);
    half3 finalRgb = mix(baseColor.rgb, glassRgb, half(insideMask));
    return half4(finalRgb, baseColor.a);
}
"""

private fun buildLiquidGlassRenderEffect(
    rect0: Rect,
    radius0Px: Float,
    rect1: Rect,
    radius1Px: Float,
    rect2: Rect,
    radius2Px: Float,
    densityPx: Float
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return try {
        val shader = RuntimeShader(LIQUID_GLASS_AGSL).apply {
            setFloatUniform("rect0", rect0.left, rect0.top, rect0.right, rect0.bottom)
            setFloatUniform("radius0", radius0Px)
            setFloatUniform("rect1", rect1.left, rect1.top, rect1.right, rect1.bottom)
            setFloatUniform("radius1", radius1Px)
            setFloatUniform("rect2", rect2.left, rect2.top, rect2.right, rect2.bottom)
            setFloatUniform("radius2", radius2Px)
            setFloatUniform("density", densityPx)
        }
        RenderEffect.createRuntimeShaderEffect(shader, "composable").asComposeRenderEffect()
    } catch (_: Throwable) {
        null
    }
}

// ------------------------------------------------------------
// Font stile Apple SF Pro Display (sf_pro.ttf locale o Inter)
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
// Modificatore Crystal Glass per pulsanti interni e card
// ------------------------------------------------------------
fun Modifier.crystalGlassButton(
    shape: Shape = CircleShape,
    highlightBoost: Float = 0.22f
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = highlightBoost),
            0.45f to Color.White.copy(alpha = 0.05f),
            1.0f to Color.White.copy(alpha = highlightBoost * 0.55f)
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.65f),
            0.45f to Color.White.copy(alpha = 0.14f),
            1.0f to Color.White.copy(alpha = 0.45f)
        ),
        shape = shape
    )

// ------------------------------------------------------------
// Sfondo Vivido per Rifrazione Liquid Glass
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
            RenderEffect.createBlurEffect(65f, 65f, Shader.TileMode.CLAMP).asComposeRenderEffect()
        } else {
            null
        }
    }

    val fallbackBrush = remember(palette) {
        Brush.verticalGradient(
            colors = listOf(
                palette.accent.copy(alpha = 0.72f),
                palette.primary,
                Color(0xFF0A0A14)
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
                        scaleX = 1.25f
                        scaleY = 1.25f
                        renderEffect = blurEffect
                    }
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF6366F1).copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width * 0.22f, size.height * 0.18f),
                        radius = size.width * 0.75f
                    ),
                    center = Offset(size.width * 0.22f, size.height * 0.18f),
                    radius = size.width * 0.75f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFFEC4899).copy(alpha = 0.42f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.78f),
                        radius = size.width * 0.75f
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.78f),
                    radius = size.width * 0.75f
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.22f),
                        0.5f to Color.Black.copy(alpha = 0.34f),
                        1.0f to Color.Black.copy(alpha = 0.48f)
                    )
                )
        )
    }
}

// ------------------------------------------------------------
// Splash Animato Liquid Glass con Nuova Icona Bolla Testi
// ------------------------------------------------------------
@Composable
fun DynamicLyricsSplashScreen(
    font: FontFamily,
    onFinished: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entered = true
        delay(1250L)
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
        animationSpec = tween(380, easing = FastOutSlowInEasing),
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
            animation = tween(1250, easing = FastOutSlowInEasing),
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
                    .crystalGlassButton(
                        shape = RoundedCornerShape(30.dp),
                        highlightBoost = 0.28f
                    )
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
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
// Schermata Permessi Liquid Glass (Notifiche + Info App + Accesso)
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
    val appInfoDone by AppPreferences.appInfoStepDone.collectAsState()
    var postNotifOk by remember {
        mutableStateOf(BitChordListenerService.areNotificationsEnabled(context))
    }
    var listenerGranted by remember {
        mutableStateOf(BitChordListenerService.isNotificationAccessGranted(context))
    }
    var awaitingAppInfoReturn by remember { mutableStateOf(false) }

    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var card1Rect by remember { mutableStateOf(Rect.Zero) }
    var card2Rect by remember { mutableStateOf(Rect.Zero) }
    var card3Rect by remember { mutableStateOf(Rect.Zero) }

    val glassEffect = remember(card1Rect, card2Rect, card3Rect, density.density) {
        buildLiquidGlassRenderEffect(
            rect0 = card1Rect,
            radius0Px = with(density) { 28.dp.toPx() },
            rect1 = card2Rect,
            radius1Px = with(density) { 28.dp.toPx() },
            rect2 = card3Rect,
            radius2Px = with(density) { 28.dp.toPx() },
            densityPx = density.density
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootTopLeft = it.boundsInRoot().topLeft }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { renderEffect = glassEffect }
        ) {
            LiquidAmbientBackground(cover = null, palette = CoverPalette())
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Configura DynamicLyrics",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = (-0.6).sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Attiva i permessi qui sotto per far comparire subito la pillola.",
                fontFamily = font,
                fontSize = 14.sp,
                letterSpacing = (-0.2).sp,
                color = Color.White.copy(alpha = 0.74f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(26.dp))

            PermissionGlassCard(
                stepNumber = "1",
                title = "Notifiche & Pillola",
                subtitle = "Consenti notifiche e aggiornamenti live per mostrare la pillola.",
                buttonLabel = if (postNotifOk) "Attivo" else "Consenti",
                completed = postNotifOk,
                font = font,
                modifier = Modifier.onGloballyPositioned {
                    card1Rect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                },
                onClick = { onRequestNotifications() }
            )

            Spacer(Modifier.height(14.dp))

            PermissionGlassCard(
                stepNumber = "2",
                title = "Info App",
                subtitle = "Consenti impostazioni con restrizioni e nessuna limitazione batteria.",
                buttonLabel = if (appInfoDone) "Riapri" else "Apri",
                completed = appInfoDone,
                font = font,
                modifier = Modifier.onGloballyPositioned {
                    card2Rect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                },
                onClick = {
                    awaitingAppInfoReturn = true
                    openAppInfoSettings(context)
                }
            )

            Spacer(Modifier.height(14.dp))

            PermissionGlassCard(
                stepNumber = "3",
                title = "Accesso alle notifiche",
                subtitle = "Legge il brano in riproduzione e sincronizza i testi in tempo reale.",
                buttonLabel = if (listenerGranted) "Attivo" else "Attiva",
                completed = listenerGranted,
                font = font,
                modifier = Modifier.onGloballyPositioned {
                    card3Rect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                },
                onClick = { openNotificationListenerSettings(context) }
            )
        }
    }
}

@Composable
private fun PermissionGlassCard(
    stepNumber: String,
    title: String,
    subtitle: String,
    buttonLabel: String,
    completed: Boolean,
    font: FontFamily,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .crystalGlassButton(CircleShape, highlightBoost = 0.24f)
        ) {
            if (completed) {
                CheckIcon(color = Color(0xFF4ADE80), size = 18.dp)
            } else {
                Text(
                    text = stepNumber,
                    fontFamily = font,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
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
                color = Color.White
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontFamily = font,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color.White.copy(alpha = 0.76f)
            )
        }

        Spacer(Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .crystalGlassButton(RoundedCornerShape(50), highlightBoost = 0.26f)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = buttonLabel,
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = if (completed) Color(0xFF86EFAC) else Color.White
            )
        }
    }
}

// ------------------------------------------------------------
// Player Lyrics Liquid Glass + Hamburger Menu
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

    var menuExpanded by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }

    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var topBarRect by remember { mutableStateOf(Rect.Zero) }
    var bottomDockRect by remember { mutableStateOf(Rect.Zero) }
    var overlayRect by remember { mutableStateOf(Rect.Zero) }
    var overlayRadiusDp by remember { mutableStateOf(26.dp) }

    LaunchedEffect(menuExpanded, showSettingsSheet) {
        if (!menuExpanded && !showSettingsSheet) {
            overlayRect = Rect.Zero
        }
    }

    val activeOverlayRect = if (menuExpanded || showSettingsSheet) overlayRect else Rect.Zero
    val activeBottomDockRect = if (showSettingsSheet) Rect.Zero else bottomDockRect

    val liquidRenderEffect = remember(
        topBarRect,
        activeBottomDockRect,
        activeOverlayRect,
        overlayRadiusDp,
        density.density
    ) {
        buildLiquidGlassRenderEffect(
            rect0 = topBarRect,
            radius0Px = with(density) { 30.dp.toPx() },
            rect1 = activeBottomDockRect,
            radius1Px = with(density) { 32.dp.toPx() },
            rect2 = activeOverlayRect,
            radius2Px = with(density) { overlayRadiusDp.toPx() },
            densityPx = density.density
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

    LaunchedEffect(isPlaying, syncOffsetMs, basePositionMs, lines) {
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
            delay(if (hasWordSync) 60L else 200L)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootTopLeft = it.boundsInRoot().topLeft }
    ) {
        // STRATO 1 (SOTTO IL VETRO): Sfondo + Testi full-bleed con rifrazione AGSL
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
                        .padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = if (title.isBlank()) "Nessun brano attivo" else "Nessun testo sincronizzato",
                        fontFamily = font,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = (-0.5).sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (title.isBlank()) {
                            "Premi Play qui sotto per avviare la musica"
                        } else {
                            "Nessun testo sincronizzato trovato per questo brano"
                        },
                        fontFamily = font,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        letterSpacing = (-0.2).sp,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 132.dp,
                        bottom = 178.dp,
                        start = 22.dp,
                        end = 22.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
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

        // STRATO 2: TOP BAR FLUTTUANTE LIQUID GLASS
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .onGloballyPositioned {
                    topBarRect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                }
                .clip(RoundedCornerShape(30.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .crystalGlassButton(RoundedCornerShape(14.dp), highlightBoost = 0.24f)
                ) {
                    MiniWaveIcon(size = 22.dp)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { "DynamicLyrics" },
                    fontFamily = font,
                    color = Color.White,
                    fontSize = 16.sp,
                    letterSpacing = (-0.4).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist.ifBlank { "Player: ${preferredPlayer.label}" },
                    fontFamily = font,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    letterSpacing = (-0.2).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showCloseButton && onClose != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .crystalGlassButton(CircleShape, highlightBoost = 0.20f)
                        .clickable(onClick = onClose)
                ) {
                    CloseIcon(size = 14.dp)
                }
                Spacer(Modifier.width(8.dp))
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .crystalGlassButton(
                        CircleShape,
                        highlightBoost = if (menuExpanded) 0.34f else 0.22f
                    )
                    .clickable { menuExpanded = !menuExpanded }
            ) {
                HamburgerIcon(size = 18.dp)
            }
        }

        // DOCK CONTROLLI FLUTTUANTE LIQUID GLASS (nascosto quando Impostazioni è aperto)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .graphicsLayer { alpha = if (showSettingsSheet) 0f else 1f }
                .onGloballyPositioned {
                    bottomDockRect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                }
                .clip(RoundedCornerShape(32.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            LiquidProgressSection(
                durationMs = durationMs,
                syncOffsetMs = syncOffsetMs,
                font = font,
                rawPosProvider = { rawPosState.longValue }
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassPillTextButton(
                    label = "-0.5s",
                    font = font,
                    onClick = { LyricsPlaybackStore.syncOffsetMs.value -= 500L }
                )

                GlassCircleIconButton(
                    size = 44.dp,
                    onClick = { LyricsPlaybackStore.onPrevious?.invoke() }
                ) {
                    SkipPrevIcon(size = 18.dp)
                }

                GlassCircleIconButton(
                    size = 58.dp,
                    elevated = true,
                    onClick = {
                        val handler = LyricsPlaybackStore.onPlayPause
                        if (handler != null) {
                            handler.invoke()
                        } else {
                            BitChordListenerService.triggerPlayPreferred(context)
                        }
                    }
                ) {
                    if (isPlaying) PauseIcon(size = 24.dp) else PlayIcon(size = 24.dp)
                }

                GlassCircleIconButton(
                    size = 44.dp,
                    onClick = { LyricsPlaybackStore.onNext?.invoke() }
                ) {
                    SkipNextIcon(size = 18.dp)
                }

                GlassPillTextButton(
                    label = "+0.5s",
                    font = font,
                    onClick = { LyricsPlaybackStore.syncOffsetMs.value += 500L }
                )
            }
        }

        // STRATO 3: POPOVER MENU HAMBURGER (Check Verde + Impostazioni)
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
            enter = fadeIn(tween(160)) + scaleIn(
                initialScale = 0.88f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f)
            ),
            exit = fadeOut(tween(140)) + scaleOut(
                targetScale = 0.90f,
                transformOrigin = TransformOrigin(1f, 0f)
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 82.dp, end = 16.dp)
        ) {
            val granted = BitChordListenerService.isNotificationAccessGranted(context)
            val activeNow = granted && BitChordListenerService.isRunning()

            Column(
                modifier = Modifier
                    .width(232.dp)
                    .onGloballyPositioned {
                        if (menuExpanded) {
                            overlayRadiusDp = 26.dp
                            overlayRect = it.boundsInRoot().translate(-rootTopLeft.x, -rootTopLeft.y)
                        }
                    }
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.Black.copy(alpha = 0.24f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (activeNow) Color(0xFF22C55E).copy(alpha = 0.16f)
                            else Color(0xFFF59E0B).copy(alpha = 0.18f)
                        )
                        .clickable {
                            menuExpanded = false
                            val ok = BitChordListenerService.isNotificationAccessGranted(context)
                            if (ok) {
                                BitChordListenerService.ensureConnected(context, forceRepublish = true)
                                statusBannerText = "Servizio attivo • Tutto pronto"
                            } else {
                                openNotificationListenerSettings(context)
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
                            .crystalGlassButton(CircleShape, highlightBoost = 0.22f)
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
                            text = "Player, offset e stato",
                            fontFamily = font,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.72f)
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
                .padding(top = 84.dp, start = 24.dp, end = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .crystalGlassButton(RoundedCornerShape(50), highlightBoost = 0.26f)
                    .background(Color(0xFF0A261D).copy(alpha = 0.72f))
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
                onSheetPositioned = { rect ->
                    if (showSettingsSheet) {
                        overlayRadiusDp = 34.dp
                        overlayRect = rect.translate(-rootTopLeft.x, -rootTopLeft.y)
                    }
                },
                onDismiss = { showSettingsSheet = false }
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

@Composable
private fun LiquidProgressSection(
    durationMs: Long,
    syncOffsetMs: Long,
    font: FontFamily,
    rawPosProvider: () -> Long
) {
    val rawPosMs = rawPosProvider()
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progress = (rawPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50)),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.28f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatPlayerTime(rawPosMs),
            fontFamily = font,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 11.sp
        )
        val sign = if (syncOffsetMs >= 0L) "+" else ""
        Text(
            text = String.format(Locale.US, "Sync %s%.1fs", sign, syncOffsetMs / 1000f),
            fontFamily = font,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = formatPlayerTime(durationMs),
            fontFamily = font,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 11.sp
        )
    }
}

// ------------------------------------------------------------
// Modale Impostazioni & Verifica Stato Tutto Attivo
// ------------------------------------------------------------
@Composable
private fun LiquidSettingsModal(
    font: FontFamily,
    onSheetPositioned: (Rect) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val preferredPlayer by AppPreferences.preferredPlayer.collectAsState()
    val defaultOffsetMs by AppPreferences.defaultSyncOffsetMs.collectAsState()
    val activePkg by LyricsPlaybackStore.activePlayerPackage.collectAsState()
    val lastPkg by AppPreferences.lastActiveMusicPackage.collectAsState()

    val notifGranted = BitChordListenerService.isNotificationAccessGranted(context)
    val postNotifOk = BitChordListenerService.areNotificationsEnabled(context)
    val serviceAlive = BitChordListenerService.isRunning()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
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
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .onGloballyPositioned { onSheetPositioned(it.boundsInRoot()) }
                .clip(RoundedCornerShape(34.dp))
                .background(Color.Black.copy(alpha = 0.36f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .crystalGlassButton(CircleShape, highlightBoost = 0.24f)
                        .clickable(onClick = onDismiss)
                ) {
                    CloseIcon(size = 14.dp)
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "VERIFICA SISTEMA",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.60f)
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
                onClick = { BitChordListenerService.ensureConnected(context, forceRepublish = true) }
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

            Text(
                text = "PLAYER PREDEFINITO (TASTO PLAY)",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.60f)
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
                    Box(
                        modifier = Modifier
                            .crystalGlassButton(
                                RoundedCornerShape(50),
                                highlightBoost = if (selected) 0.42f else 0.16f
                            )
                            .background(
                                if (selected) Color(0xFF6366F1).copy(alpha = 0.45f)
                                else Color.Transparent
                            )
                            .clickable {
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

            Text(
                text = "OFFSET PREDEFINITO",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.60f)
            )
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .crystalGlassButton(RoundedCornerShape(22.dp), highlightBoost = 0.16f)
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
            .crystalGlassButton(RoundedCornerShape(18.dp), highlightBoost = 0.16f)
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
// Pulsanti & Icone Vettoriali Crystal Glass
// ------------------------------------------------------------
@Composable
private fun GlassPillTextButton(
    label: String,
    font: FontFamily,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .crystalGlassButton(RoundedCornerShape(50), highlightBoost = 0.22f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontFamily = font,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = (-0.2).sp,
            color = Color.White
        )
    }
}

@Composable
private fun GlassCircleIconButton(
    size: Dp,
    elevated: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .crystalGlassButton(
                shape = CircleShape,
                highlightBoost = if (elevated) 0.34f else 0.20f
            )
            .clickable(onClick = onClick)
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
