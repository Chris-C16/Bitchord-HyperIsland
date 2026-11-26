package com.bitchord.island

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.sin

// ------------------------------------------------------------
// Font: Plus Jakarta Sans (simile a Apple SF Pro / SF Rounded)
// ------------------------------------------------------------
@Composable
fun rememberDynamicFont(): FontFamily {
    val context = LocalContext.current
    val family by produceState<FontFamily>(initialValue = FontFamily.SansSerif) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "fonts")
                val file = File(dir, "PlusJakartaSans.ttf")
                if (!file.exists() || file.length() < 15_000L) {
                    if (!dir.exists()) dir.mkdirs()
                    val urls = listOf(
                        "https://raw.githubusercontent.com/google/fonts/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf",
                        "https://raw.githubusercontent.com/google/fonts/main/ofl/outfit/Outfit%5Bwght%5D.ttf"
                    )
                    for (urlStr in urls) {
                        try {
                            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 5000
                                readTimeout = 5000
                                instanceFollowRedirects = true
                            }
                            if (conn.responseCode in 200..299) {
                                conn.inputStream.use { input ->
                                    file.outputStream().use { output -> input.copyTo(output) }
                                }
                                conn.disconnect()
                                if (file.length() > 15_000L) break
                            } else {
                                conn.disconnect()
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (file.exists() && file.length() > 15_000L) {
                    FontFamily(Typeface.createFromFile(file))
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
// Modificatore Apple Liquid Glass (rifrazione + bordo speculare)
// ------------------------------------------------------------
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Color = Color(0xFF14141F),
    tintAlpha: Float = 0.42f,
    highlightAlpha: Float = 0.22f,
    borderAlpha: Float = 0.42f
): Modifier = this
    .clip(shape)
    .background(tint.copy(alpha = tintAlpha))
    .background(
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = highlightAlpha),
            0.35f to Color.White.copy(alpha = highlightAlpha * 0.30f),
            1.0f to Color.White.copy(alpha = highlightAlpha * 0.48f)
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            0.0f to Color.White.copy(alpha = borderAlpha),
            0.48f to Color.White.copy(alpha = borderAlpha * 0.16f),
            1.0f to Color.White.copy(alpha = borderAlpha * 0.56f)
        ),
        shape = shape
    )

// ------------------------------------------------------------
// Sfondo Liquid Ambient (copertina pre-scalata 24x24 a 0 lag)
// ------------------------------------------------------------
@Composable
fun LiquidAmbientBackground(
    cover: Bitmap?,
    palette: CoverPalette
) {
    val softCover = remember(cover) {
        if (cover != null && !cover.isRecycled && cover.width > 0 && cover.height > 0) {
            try {
                Bitmap.createScaledBitmap(cover, 24, 24, true).asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    val baseBrush = remember(palette) {
        Brush.verticalGradient(
            colors = listOf(
                palette.primary.copy(alpha = 0.92f),
                palette.secondary.copy(alpha = 0.96f),
                Color(0xFF07070C)
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(baseBrush)) {
        if (softCover != null) {
            Image(
                bitmap = softCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.55f }
            )
        }

        // Glow superiore morbido che riflette dietro le superfici in vetro
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            palette.accent.copy(alpha = 0.24f),
                            Color(0xFF07070C).copy(alpha = 0.62f),
                            Color(0xFF050509).copy(alpha = 0.88f)
                        ),
                        radius = 1200f
                    )
                )
        )
    }
}

// ------------------------------------------------------------
// Nuova Icona Liquid Glass Animata (Splash Screen)
// ------------------------------------------------------------
@Composable
fun DynamicLyricsSplashScreen(
    font: FontFamily,
    onFinished: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entered = true
        delay(1350L)
        onFinished()
    }

    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.72f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "splashScale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "splashAlpha"
    )

    val infinite = rememberInfiniteTransition(label = "splashWave")
    val wavePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    val shimmer by infinite.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF141126), Color(0xFF0B0A14), Color(0xFF06060A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Alone luminoso dietro l'icona
        Box(
            modifier = Modifier
                .size(240.dp)
                .graphicsLayer { alpha = contentAlpha * 0.7f }
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF7C3AED).copy(alpha = 0.42f),
                                Color(0xFF06B6D4).copy(alpha = 0.16f),
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
                    .liquidGlass(
                        shape = RoundedCornerShape(30.dp),
                        tint = Color(0xFF161529),
                        tintAlpha = 0.62f,
                        highlightAlpha = 0.30f,
                        borderAlpha = 0.58f
                    )
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Riflesso diagonale Liquid Glass
                    val sheenX = size.width * shimmer
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            start = Offset(sheenX - size.width * 0.45f, 0f),
                            end = Offset(sheenX + size.width * 0.25f, size.height)
                        )
                    )

                    // 5 barre onda sonora fluide
                    val barColors = listOf(
                        Color(0xFF38BDF8),
                        Color(0xFF818CF8),
                        Color(0xFFF472B6),
                        Color(0xFFA78BFA),
                        Color(0xFF22D3EE)
                    )
                    val baseHeights = floatArrayOf(0.24f, 0.46f, 0.60f, 0.44f, 0.24f)
                    val barW = size.width * 0.088f
                    val gap = size.width * 0.062f
                    val totalW = barW * 5 + gap * 4
                    val startX = (size.width - totalW) / 2f
                    val cy = size.height / 2f

                    for (i in 0 until 5) {
                        val pulse = 0.80f + 0.28f * sin(wavePhase + i * 0.85f)
                        val h = (size.height * baseHeights[i] * pulse).coerceIn(barW, size.height * 0.68f)
                        val x = startX + i * (barW + gap)
                        drawRoundRect(
                            color = barColors[i],
                            topLeft = Offset(x, cy - h / 2f),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = "DynamicLyrics",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = (-0.4).sp,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Live Island • Sync Lyrics",
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
    }
}

// ------------------------------------------------------------
// Schermata Permessi Liquid Glass (SOLO Info App + Notifiche)
// ------------------------------------------------------------
@Composable
fun LiquidPermissionsScreen(
    font: FontFamily,
    onAllReady: () -> Unit
) {
    val context = LocalContext.current
    val appInfoDone by AppPreferences.appInfoStepDone.collectAsState()
    var notifGranted by remember {
        mutableStateOf(BitChordListenerService.isNotificationAccessGranted(context))
    }
    var awaitingAppInfoReturn by remember { mutableStateOf(false) }

    // Ricontrolla automaticamente i permessi al ritorno dalle impostazioni di sistema
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (awaitingAppInfoReturn) {
                    awaitingAppInfoReturn = false
                    AppPreferences.markAppInfoDone(context, true)
                }
                val granted = BitChordListenerService.isNotificationAccessGranted(context)
                notifGranted = granted
                if (granted) {
                    BitChordListenerService.ensureConnected(context)
                    onAllReady()
                }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1533), Color(0xFF0E0C1A), Color(0xFF07070C))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Configura DynamicLyrics",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Solo 2 passaggi rapidi per attivare i testi in tempo reale.",
                fontFamily = font,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.68f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            // STEP 1: Info App (impostazioni con restrizioni / batteria HyperOS)
            PermissionGlassCard(
                stepNumber = "1",
                title = "Info App",
                subtitle = "Consenti impostazioni con restrizioni e nessuna limitazione batteria.",
                buttonLabel = if (appInfoDone) "Riapri" else "Apri Info App",
                completed = appInfoDone,
                font = font,
                onClick = {
                    awaitingAppInfoReturn = true
                    openAppInfoSettings(context)
                }
            )

            Spacer(Modifier.height(14.dp))

            // STEP 2: Accesso alle notifiche
            PermissionGlassCard(
                stepNumber = "2",
                title = "Accesso alle notifiche",
                subtitle = "Necessario per leggere il brano in riproduzione e sincronizzare i testi.",
                buttonLabel = if (notifGranted) "Attivo" else "Attiva ora",
                completed = notifGranted,
                font = font,
                onClick = {
                    openNotificationListenerSettings(context)
                }
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
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = RoundedCornerShape(26.dp),
                tintAlpha = 0.48f,
                highlightAlpha = 0.22f,
                borderAlpha = if (completed) 0.55f else 0.36f
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (completed) Color(0xFF34D399).copy(alpha = 0.22f)
                    else Color.White.copy(alpha = 0.14f)
                )
                .border(
                    1.dp,
                    if (completed) Color(0xFF34D399).copy(alpha = 0.7f)
                    else Color.White.copy(alpha = 0.32f),
                    CircleShape
                )
        ) {
            if (completed) {
                CheckIcon(color = Color(0xFF34D399), size = 18.dp)
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
                color = Color.White
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontFamily = font,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color.White.copy(alpha = 0.68f)
            )
        }

        Spacer(Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .liquidGlass(
                    shape = RoundedCornerShape(50),
                    tint = if (completed) Color(0xFF0D3B2E) else Color(0xFF282442),
                    tintAlpha = 0.65f,
                    highlightAlpha = 0.24f,
                    borderAlpha = 0.45f
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = buttonLabel,
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = if (completed) Color(0xFF6EE7B7) else Color.White
            )
        }
    }
}

// ------------------------------------------------------------
// Player Lyrics Liquid Glass Unificato + Hamburger Menu
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

    var menuExpanded by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }
    var serviceHealthy by remember {
        mutableStateOf(
            BitChordListenerService.isNotificationAccessGranted(context) &&
                BitChordListenerService.isRunning()
        )
    }

    // Aggiorna lo stato reale del servizio quando si torna in app
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                BitChordListenerService.ensureConnected(context)
                serviceHealthy = BitChordListenerService.isNotificationAccessGranted(context) &&
                    BitChordListenerService.isRunning()
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

    // Stato clock isolato (evita ricomposizioni della schermata intera)
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

    Box(modifier = Modifier.fillMaxSize()) {
        LiquidAmbientBackground(cover = cover, palette = palette)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 1. TOP BAR LIQUID GLASS (Cover + Titolo + Menu Hamburger a destra)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = RoundedCornerShape(28.dp),
                        tintAlpha = 0.40f,
                        highlightAlpha = 0.24f,
                        borderAlpha = 0.44f
                    )
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
                            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.12f))
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
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist.ifBlank { "Pronto per ${preferredPlayer.label}" },
                        fontFamily = font,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
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
                            .liquidGlass(
                                shape = CircleShape,
                                tintAlpha = 0.35f,
                                highlightAlpha = 0.20f,
                                borderAlpha = 0.36f
                            )
                            .clickable(onClick = onClose)
                    ) {
                        CloseIcon(size = 15.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Pulsante Hamburger in alto a destra
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .liquidGlass(
                            shape = CircleShape,
                            tintAlpha = if (menuExpanded) 0.62f else 0.38f,
                            highlightAlpha = 0.26f,
                            borderAlpha = 0.48f
                        )
                        .clickable { menuExpanded = !menuExpanded }
                ) {
                    HamburgerIcon(size = 18.dp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // 2. AREA TESTI LIQUID GLASS
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (lines.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .liquidGlass(
                                shape = RoundedCornerShape(30.dp),
                                tintAlpha = 0.36f,
                                highlightAlpha = 0.18f,
                                borderAlpha = 0.36f
                            )
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        Text(
                            text = if (title.isBlank()) {
                                "Nessun brano in riproduzione"
                            } else {
                                "Nessun testo sincronizzato"
                            },
                            fontFamily = font,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (title.isBlank()) {
                                "Premi Play qui sotto per avviare ${preferredPlayer.label}"
                            } else {
                                "Questo brano non ha un testo sincronizzato disponibile."
                            },
                            fontFamily = font,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.68f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 24.dp, bottom = 36.dp, start = 6.dp, end = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
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

            Spacer(Modifier.height(12.dp))

            // 3. DOCK CONTROLLI LIQUID GLASS (Progress + Offset + Media)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = RoundedCornerShape(30.dp),
                        tintAlpha = 0.44f,
                        highlightAlpha = 0.22f,
                        borderAlpha = 0.42f
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
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
        }

        // 4. POPOVER LIQUID GLASS DAL MENU HAMBURGER (2 OPZIONI: CHECK VERDE + IMPOSTAZIONI)
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
                initialScale = 0.86f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f)
            ),
            exit = fadeOut(tween(140)) + scaleOut(
                targetScale = 0.88f,
                transformOrigin = TransformOrigin(1f, 0f)
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 74.dp, end = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(230.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(24.dp),
                        tint = Color(0xFF141422),
                        tintAlpha = 0.80f,
                        highlightAlpha = 0.24f,
                        borderAlpha = 0.50f
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // OPZIONE 1: Check Verde conferma servizio attivo
                val granted = BitChordListenerService.isNotificationAccessGranted(context)
                val activeNow = granted && BitChordListenerService.isRunning()
                serviceHealthy = activeNow

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (activeNow) Color(0xFF34D399).copy(alpha = 0.14f)
                            else Color(0xFFFBBF24).copy(alpha = 0.14f)
                        )
                        .clickable {
                            menuExpanded = false
                            val ok = BitChordListenerService.isNotificationAccessGranted(context)
                            if (ok) {
                                BitChordListenerService.ensureConnected(context)
                                serviceHealthy = true
                                statusBannerText = "Servizio attivo • Player: ${preferredPlayer.label}"
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
                                if (activeNow) Color(0xFF34D399).copy(alpha = 0.24f)
                                else Color(0xFFFBBF24).copy(alpha = 0.24f)
                            )
                    ) {
                        CheckIcon(
                            color = if (activeNow) Color(0xFF34D399) else Color(0xFFFBBF24),
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
                            color = Color.White
                        )
                        Text(
                            text = if (activeNow) "Tocca per verificare" else "Tocca per attivare",
                            fontFamily = font,
                            fontSize = 11.sp,
                            color = if (activeNow) Color(0xFF6EE7B7) else Color(0xFFFDE68A)
                        )
                    }
                }

                // OPZIONE 2: Impostazioni & Verifica sistema
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
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
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
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
                            color = Color.White
                        )
                        Text(
                            text = "Player, offset e stato",
                            fontFamily = font,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }

        // 5. TOAST LIQUID GLASS DI CONFERMA SERVIZIO
        AnimatedVisibility(
            visible = statusBannerText != null,
            enter = fadeIn(tween(180)) + slideInVertically { -it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 76.dp, start = 24.dp, end = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .liquidGlass(
                        shape = RoundedCornerShape(50),
                        tint = Color(0xFF0E2B22),
                        tintAlpha = 0.85f,
                        highlightAlpha = 0.24f,
                        borderAlpha = 0.55f
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                CheckIcon(color = Color(0xFF34D399), size = 16.dp)
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

        // 6. PANNELLO IMPOSTAZIONI & DIAGNOSTICA LIQUID GLASS
        AnimatedVisibility(
            visible = showSettingsSheet,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            LiquidSettingsModal(
                font = font,
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
        targetValue = if (isActive) 1.02f else 0.97f,
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
                        color = if (spoken) Color.White else Color.White.copy(alpha = 0.42f),
                        fontWeight = FontWeight.ExtraBold
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
            fontSize = 25.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = rowModifier
        )
    } else {
        Text(
            text = line.fullText,
            fontFamily = font,
            color = Color.White,
            fontSize = if (isActive) 25.sp else 20.sp,
            lineHeight = if (isActive) 33.sp else 27.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
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
        trackColor = Color.White.copy(alpha = 0.22f)
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
            color = Color.White.copy(alpha = 0.70f),
            fontSize = 11.sp
        )
        val sign = if (syncOffsetMs >= 0L) "+" else ""
        Text(
            text = String.format(Locale.US, "Sync %s%.1fs", sign, syncOffsetMs / 1000f),
            fontFamily = font,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = formatPlayerTime(durationMs),
            fontFamily = font,
            color = Color.White.copy(alpha = 0.70f),
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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val preferredPlayer by AppPreferences.preferredPlayer.collectAsState()
    val defaultOffsetMs by AppPreferences.defaultSyncOffsetMs.collectAsState()
    val activePkg by LyricsPlaybackStore.activePlayerPackage.collectAsState()

    val notifGranted = BitChordListenerService.isNotificationAccessGranted(context)
    val serviceAlive = BitChordListenerService.isRunning()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
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
                .liquidGlass(
                    shape = RoundedCornerShape(32.dp),
                    tint = Color(0xFF131320),
                    tintAlpha = 0.88f,
                    highlightAlpha = 0.24f,
                    borderAlpha = 0.52f
                )
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
                    fontSize = 19.sp,
                    color = Color.White
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .liquidGlass(shape = CircleShape, tintAlpha = 0.45f)
                        .clickable(onClick = onDismiss)
                ) {
                    CloseIcon(size = 14.dp)
                }
            }

            Spacer(Modifier.height(18.dp))

            // SEZIONE 1: VERIFICA STATO SISTEMA
            Text(
                text = "VERIFICA SISTEMA",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.52f)
            )
            Spacer(Modifier.height(10.dp))

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
                status = if (serviceAlive) "In esecuzione" else "Riaggancia",
                ok = serviceAlive,
                font = font,
                onClick = { BitChordListenerService.ensureConnected(context) }
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
                label = "Sessione rilevata",
                status = activePkg.ifBlank { "Nessuna sessione attiva" },
                ok = activePkg.isNotBlank(),
                font = font,
                onClick = { BitChordListenerService.ensureConnected(context) }
            )

            Spacer(Modifier.height(22.dp))

            // SEZIONE 2: SELETTORE PLAYER PREDEFINITO
            Text(
                text = "PLAYER PREDEFINITO (TASTO PLAY)",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.52f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Scegli quale app musicale ascoltare e avviare dalla pillola.",
                fontFamily = font,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.64f)
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
                            .liquidGlass(
                                shape = RoundedCornerShape(50),
                                tint = if (selected) Color(0xFF4F46E5) else Color(0xFF1D1D2E),
                                tintAlpha = if (selected) 0.78f else 0.50f,
                                highlightAlpha = if (selected) 0.32f else 0.16f,
                                borderAlpha = if (selected) 0.75f else 0.30f
                            )
                            .clickable {
                                AppPreferences.setPreferredPlayer(context, player)
                                BitChordListenerService.ensureConnected(context)
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

            // SEZIONE 3: OFFSET PREDEFINITO (+0.6s default)
            Text(
                text = "OFFSET PREDEFINITO",
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.52f)
            )
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = RoundedCornerShape(20.dp),
                        tintAlpha = 0.45f,
                        highlightAlpha = 0.16f,
                        borderAlpha = 0.34f
                    )
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
            .liquidGlass(
                shape = RoundedCornerShape(18.dp),
                tintAlpha = 0.40f,
                highlightAlpha = 0.16f,
                borderAlpha = if (ok) 0.42f else 0.28f
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    if (ok) Color(0xFF34D399).copy(alpha = 0.22f)
                    else Color(0xFFFBBF24).copy(alpha = 0.20f)
                )
        ) {
            CheckIcon(
                color = if (ok) Color(0xFF34D399) else Color(0xFFFBBF24),
                size = 14.dp
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontFamily = font,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = status,
            fontFamily = font,
            fontSize = 12.sp,
            color = if (ok) Color(0xFF6EE7B7) else Color(0xFFFDE68A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ------------------------------------------------------------
// Componenti Pulsanti & Icone Vettoriali Liquid Glass
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
            .liquidGlass(
                shape = RoundedCornerShape(50),
                tintAlpha = 0.42f,
                highlightAlpha = 0.22f,
                borderAlpha = 0.38f
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontFamily = font,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
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
            .liquidGlass(
                shape = CircleShape,
                tint = if (elevated) Color(0xFF28263D) else Color(0xFF181826),
                tintAlpha = if (elevated) 0.56f else 0.40f,
                highlightAlpha = if (elevated) 0.32f else 0.20f,
                borderAlpha = if (elevated) 0.58f else 0.38f
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
            val x1 = cx + (outerR * 0.72f * kotlin.math.cos(angle)).toFloat()
            val y1 = cy + (outerR * 0.72f * sin(angle)).toFloat()
            val x2 = cx + (outerR * kotlin.math.cos(angle)).toFloat()
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
