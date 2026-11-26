package com.bitchord.island

import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class WearLyricsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearSyncRepository.start(this)

        setContent {
            MaterialTheme {
                WearLyricsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WearSyncRepository.requestSyncFromPhone()
    }
}

private fun findWearLineIndex(lines: List<WearLyricLine>, posMs: Long): Int {
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
private fun rememberComfortaaFamily(): FontFamily {
    val context = LocalContext.current
    val family by produceState<FontFamily>(initialValue = FontFamily.SansSerif) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "fonts")
                val file = File(dir, "Comfortaa.ttf")
                if (!file.exists() || file.length() < 10_000L) {
                    if (!dir.exists()) dir.mkdirs()
                    val urls = listOf(
                        "https://raw.githubusercontent.com/google/fonts/main/ofl/comfortaa/Comfortaa%5Bwght%5D.ttf",
                        "https://fonts.gstatic.com/s/comfortaa/v47/1Pt_g8LJRfWJmhDAuUsSQamb1W0lwk4S4Y_LPrQVIT9c2c8.ttf"
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
                                if (file.length() > 10_000L) break
                            } else {
                                conn.disconnect()
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (file.exists() && file.length() > 10_000L) {
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

@Composable
private fun WearLyricsScreen() {
    val trackState by WearSyncRepository.track.collectAsState()
    val isPlaying by WearSyncRepository.isPlaying.collectAsState()
    val phoneConnected by WearSyncRepository.phoneConnected.collectAsState()
    val comfortaa = rememberComfortaaFamily()

    var activeIndex by remember(trackState.trackKey) { mutableIntStateOf(-1) }
    var showControls by remember { mutableStateOf(false) }

    // Sfondo sfocato a costo GPU quasi zero: pre-ridimensiona una volta la cover a 18x18
    val softCover = remember(trackState.cover) {
        val bmp = trackState.cover
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            try {
                Bitmap.createScaledBitmap(bmp, 18, 18, true).asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    // Orologio locale con risveglio dinamico sul prossimo verso + isteresi anti-rimbalzo
    LaunchedEffect(trackState.trackKey, trackState.lines, isPlaying) {
        val lines = trackState.lines
        if (lines.isEmpty()) {
            activeIndex = -1
            return@LaunchedEffect
        }
        while (true) {
            val pos = WearSyncRepository.currentPositionMs()
            var idx = findWearLineIndex(lines, pos)

            if (isPlaying && activeIndex in lines.indices && idx < activeIndex) {
                val lagMs = lines[activeIndex].startMs - pos
                if (lagMs in 0L..1500L) {
                    idx = activeIndex
                }
            }

            if (idx != activeIndex) {
                activeIndex = idx
            }
            if (!isPlaying) break

            val nextIdx = idx + 1
            val waitMs = if (nextIdx in lines.indices) {
                val spd = WearSyncRepository.speed.value.coerceAtLeast(0.25f)
                ((lines[nextIdx].startMs - pos) / spd).toLong().coerceIn(40L, 350L)
            } else {
                350L
            }
            delay(waitMs)
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3500L)
            showControls = false
        }
    }

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    LaunchedEffect(activeIndex, trackState.trackKey) {
        if (activeIndex in trackState.lines.indices && !listState.isScrollInProgress) {
            listState.animateScrollToItem(index = activeIndex)
        }
    }

    val bgTop = Color(trackState.bgTopColor)
    val bgBottom = Color(trackState.bgBottomColor)
    val accent = Color(trackState.accentColor)
    val edgeFadeBrush = remember {
        Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.18f to Color.Black,
            0.82f to Color.Black,
            1.0f to Color.Transparent
        )
    }

    Scaffold(
        timeText = {
            TimeText()
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showControls = !showControls
                }
        ) {
            if (softCover != null) {
                Image(
                    bitmap = softCover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.72f)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.24f),
                                Color.Black.copy(alpha = 0.72f)
                            )
                        )
                    )
            )

            if (trackState.title.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "BitChord Lyrics",
                        fontFamily = comfortaa,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (phoneConnected) {
                            "Avvia un brano sul telefono"
                        } else {
                            "Collega il telefono via Bluetooth"
                        },
                        fontFamily = comfortaa,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center
                    )
                }
            } else if (trackState.lines.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = trackState.title,
                        fontFamily = comfortaa,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = trackState.artist,
                        fontFamily = comfortaa,
                        fontSize = 12.sp,
                        color = accent.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Testo sincronizzato non disponibile",
                        fontFamily = comfortaa,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                ScalingLazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = 48.dp,
                        bottom = 52.dp,
                        start = 18.dp,
                        end = 18.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(brush = edgeFadeBrush, blendMode = BlendMode.DstIn)
                        }
                ) {
                    itemsIndexed(
                        items = trackState.lines,
                        key = { index, item -> "${trackState.trackKey}_${index}_${item.startMs}" }
                    ) { index, line ->
                        val distance = if (activeIndex < 0) {
                            if (index == 0) 0 else 2
                        } else {
                            abs(index - activeIndex)
                        }
                        val isCurrent = distance == 0

                        val targetAlpha = when (distance) {
                            0 -> 1f
                            1 -> 0.54f
                            else -> 0.26f
                        }
                        val targetScale = if (isCurrent) 1.05f else 0.94f

                        val alpha by animateFloatAsState(
                            targetValue = targetAlpha,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            label = "lyricAlpha"
                        )
                        val scale by animateFloatAsState(
                            targetValue = targetScale,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            label = "lyricScale"
                        )

                        Text(
                            text = line.text,
                            fontFamily = comfortaa,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (isCurrent) 16.sp else 13.sp,
                            lineHeight = if (isCurrent) 20.sp else 17.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    this.alpha = alpha
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clickable {
                                    WearSyncRepository.sendSeekTo(line.startMs)
                                    activeIndex = index
                                }
                                .padding(vertical = 2.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp, start = 34.dp, end = 34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.38f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${trackState.title} • ${trackState.artist}",
                        fontFamily = comfortaa,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = showControls && trackState.title.isNotBlank(),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(220)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.56f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showControls = false
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassMediaButton(sizeDp = 42, onClick = {
                            showControls = true
                            WearSyncRepository.sendPrev()
                        }) {
                            PrevIcon()
                        }

                        GlassMediaButton(sizeDp = 56, onClick = {
                            showControls = true
                            WearSyncRepository.sendPlayPause()
                        }) {
                            if (isPlaying) PauseIcon() else PlayIcon()
                        }

                        GlassMediaButton(sizeDp = 42, onClick = {
                            showControls = true
                            WearSyncRepository.sendNext()
                        }) {
                            NextIcon()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassMediaButton(
    sizeDp: Int,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick)
    ) {
        content()
    }
}

@Composable
private fun PlayIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.16f)
            lineTo(size.width * 0.84f, size.height * 0.50f)
            lineTo(size.width * 0.28f, size.height * 0.84f)
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
private fun PauseIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val barW = size.width * 0.22f
        val barH = size.height * 0.64f
        val top = (size.height - barH) / 2f
        drawRoundRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.22f, top),
            size = androidx.compose.ui.geometry.Size(barW, barH)
        )
        drawRoundRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.56f, top),
            size = androidx.compose.ui.geometry.Size(barW, barH)
        )
    }
}

@Composable
private fun NextIcon() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.20f, size.height * 0.22f)
            lineTo(size.width * 0.66f, size.height * 0.50f)
            lineTo(size.width * 0.20f, size.height * 0.78f)
            close()
        }
        drawPath(path = path, color = Color.White)
        drawRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.22f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.56f)
        )
    }
}

@Composable
private fun PrevIcon() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.80f, size.height * 0.22f)
            lineTo(size.width * 0.34f, size.height * 0.50f)
            lineTo(size.width * 0.80f, size.height * 0.78f)
            close()
        }
        drawPath(path = path, color = Color.White)
        drawRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.16f, size.height * 0.22f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.56f)
        )
    }
}
