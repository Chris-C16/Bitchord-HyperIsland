package com.bitchord.island

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class LyricsPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LyricsPlayerScreen(onClose = { finish() })
            }
        }
    }
}

private fun findPlayerLineIndex(lines: List<LyricLine>, posMs: Long): Int {
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
private fun LyricsPlayerScreen(onClose: () -> Unit) {
    val title by LyricsPlaybackStore.title.collectAsState()
    val artist by LyricsPlaybackStore.artist.collectAsState()
    val cover by LyricsPlaybackStore.cover.collectAsState()
    val palette by LyricsPlaybackStore.palette.collectAsState()
    val lines by LyricsPlaybackStore.lines.collectAsState()
    val isPlaying by LyricsPlaybackStore.isPlaying.collectAsState()
    val durationMs by LyricsPlaybackStore.durationMs.collectAsState()
    val syncOffsetMs by LyricsPlaybackStore.syncOffsetMs.collectAsState()
    val basePositionMs by LyricsPlaybackStore.basePositionMs.collectAsState()

    val coverBitmap = remember(cover) {
        cover?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
    }
    val backgroundBrush = remember(palette) {
        Brush.verticalGradient(
            colors = listOf(palette.primary, palette.secondary, Color(0xFF0A090F))
        )
    }

    // Stato del clock letto solo dai micro-composable figli (evita di ricomporre tutta la UI)
    val currentPosState = remember { mutableLongStateOf(LyricsPlaybackStore.currentPositionMs()) }
    val rawPosState = remember { mutableLongStateOf(LyricsPlaybackStore.rawPositionMs()) }
    var activeIndex by remember(lines) { mutableIntStateOf(-1) }
    var forceNextLineSync by remember { mutableStateOf(true) }

    LaunchedEffect(syncOffsetMs) {
        forceNextLineSync = true
    }

    LaunchedEffect(isPlaying, syncOffsetMs, basePositionMs, lines) {
        while (true) {
            val pos = LyricsPlaybackStore.currentPositionMs()
            val raw = LyricsPlaybackStore.rawPositionMs()
            currentPosState.longValue = pos
            rawPosState.longValue = raw

            if (lines.isEmpty()) {
                activeIndex = -1
            } else {
                var idx = findPlayerLineIndex(lines, pos)
                // Isteresi anti-rimbalzo durante il play continuo
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

            // In pausa non serve polling continuo
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
            .background(backgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.ifBlank { "Nessun brano su BitChord" },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist.ifBlank { "Avvia una canzone su BitChord" },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onClose) {
                    Text("✕", color = Color.White, fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nessun testo sincronizzato trovato per questo brano.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    itemsIndexed(
                        items = lines,
                        key = { idx, line -> "${idx}_${line.startMs}" }
                    ) { idx, line ->
                        LyricLineItem(
                            line = line,
                            isActive = idx == activeIndex,
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

            Spacer(Modifier.height(12.dp))

            LyricsProgressSection(
                durationMs = durationMs,
                syncOffsetMs = syncOffsetMs,
                rawPosProvider = { rawPosState.longValue }
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { LyricsPlaybackStore.syncOffsetMs.value -= 500L }) {
                    Text("-0.5s", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
                TextButton(onClick = { LyricsPlaybackStore.onPrevious?.invoke() }) {
                    Text("⏮", color = Color.White, fontSize = 24.sp)
                }
                Button(
                    onClick = { LyricsPlaybackStore.onPlayPause?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(if (isPlaying) "⏸" else "▶", color = Color.White, fontSize = 24.sp)
                }
                TextButton(onClick = { LyricsPlaybackStore.onNext?.invoke() }) {
                    Text("⏭", color = Color.White, fontSize = 24.sp)
                }
                TextButton(onClick = { LyricsPlaybackStore.syncOffsetMs.value += 500L }) {
                    Text("+0.5s", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    currentPosProvider: () -> Long,
    onClick: () -> Unit
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.35f,
        label = "lineAlpha"
    )

    val rowModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer { alpha = animatedAlpha }
        .clickable(onClick = onClick)

    if (isActive && line.words.isNotEmpty()) {
        val posMs = currentPosProvider()
        val annotated = buildAnnotatedString {
            line.words.forEach { w ->
                val spoken = posMs >= w.startMs
                withStyle(
                    SpanStyle(
                        color = if (spoken) Color.White else Color.White.copy(alpha = 0.45f),
                        fontWeight = FontWeight.ExtraBold
                    )
                ) {
                    append(w.text)
                }
            }
        }
        Text(
            text = annotated,
            color = Color.White,
            fontSize = 25.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = rowModifier
        )
    } else {
        Text(
            text = line.fullText,
            color = Color.White,
            fontSize = if (isActive) 25.sp else 20.sp,
            lineHeight = if (isActive) 32.sp else 26.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
            modifier = rowModifier
        )
    }
}

@Composable
private fun LyricsProgressSection(
    durationMs: Long,
    syncOffsetMs: Long,
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
            .clip(RoundedCornerShape(2.dp)),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.25f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatTime(rawPosMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        if (syncOffsetMs != 0L) {
            val sign = if (syncOffsetMs > 0) "+" else ""
            Text(
                text = "Sync: $sign${syncOffsetMs / 1000f}s",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(formatTime(durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
