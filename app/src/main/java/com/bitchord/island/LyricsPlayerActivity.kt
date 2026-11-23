package com.bitchord.island

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

object LyricsPlaybackStore {
    val title = mutableStateOf("")
    val artist = mutableStateOf("")
    val cover = mutableStateOf<Bitmap?>(null)
    val palette = mutableStateOf(CoverPalette())
    val lines = mutableStateOf<List<LyricLine>>(emptyList())
    val isPlaying = mutableStateOf(false)
    val basePositionMs = mutableStateOf(0L)
    val baseRealtimeMs = mutableStateOf(0L)
    val speed = mutableStateOf(1f)

    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    fun currentPositionMs(): Long {
        if (!isPlaying.value) return basePositionMs.value
        val delta = (SystemClock.elapsedRealtime() - baseRealtimeMs.value).coerceAtLeast(0L)
        return basePositionMs.value + (delta * speed.value).toLong()
    }
}

class LyricsPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BitChordLyricsScreen(onClose = { finish() })
        }
    }
}

@Composable
private fun BitChordLyricsScreen(onClose: () -> Unit) {
    val title by LyricsPlaybackStore.title
    val artist by LyricsPlaybackStore.artist
    val cover by LyricsPlaybackStore.cover
    val palette by LyricsPlaybackStore.palette
    val lines by LyricsPlaybackStore.lines
    val isPlaying by LyricsPlaybackStore.isPlaying

    var nowPosMs by remember { mutableLongStateOf(LyricsPlaybackStore.currentPositionMs()) }

    // Aggiornamento a ~60 FPS per riempimento word-by-word ultra fluido
    LaunchedEffect(isPlaying, lines) {
        while (true) {
            nowPosMs = LyricsPlaybackStore.currentPositionMs()
            delay(16L)
        }
    }

    val c1 by animateColorAsState(palette.primary, tween(800), label = "c1")
    val c2 by animateColorAsState(palette.secondary, tween(800), label = "c2")
    val c3 by animateColorAsState(palette.accent, tween(800), label = "c3")

    val shift by rememberInfiniteTransition(label = "mesh").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "shift"
    )

    val activeIndex = remember(lines, nowPosMs) {
        lines.indexOfLast { nowPosMs >= it.startMs }.coerceAtLeast(0)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(c1, c3.copy(alpha = 0.75f), c2),
                    start = Offset(0f, 500f * shift),
                    end = Offset(1100f, 1800f * (1f - shift))
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Barra superiore identica a BitChord: cover, titolo/artista e chiusura
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(11.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.ifBlank { "Nessun brano" },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable { onClose() }
                ) {
                    Text("✕", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Area testi sincronizzati con blur progressivo e karaoke word-by-word
            if (lines.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Caricamento testo sincronizzato...",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 36.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        val distance = abs(index - activeIndex)
                        val isCurrent = index == activeIndex
                        val blurRadius = when (distance) {
                            0 -> 0.dp
                            1 -> 1.3.dp
                            2 -> 2.6.dp
                            else -> 4.dp
                        }

                        val styledLine = buildAnnotatedString {
                            if (!isCurrent) {
                                withStyle(
                                    SpanStyle(
                                        color = Color.White.copy(alpha = if (index < activeIndex) 0.34f else 0.46f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) {
                                    append(line.fullText)
                                }
                            } else {
                                line.words.forEach { word ->
                                    val progress = ((nowPosMs - word.startMs).toFloat() /
                                            (word.endMs - word.startMs).coerceAtLeast(1L))
                                        .coerceIn(0f, 1f)
                                    val alpha = 0.40f + (0.60f * progress)
                                    withStyle(
                                        SpanStyle(
                                            color = Color.White.copy(alpha = alpha),
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    ) {
                                        append(word.text)
                                    }
                                }
                            }
                        }

                        Text(
                            text = styledLine,
                            fontSize = if (isCurrent) 24.sp else 20.sp,
                            lineHeight = if (isCurrent) 31.sp else 26.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .blur(blurRadius)
                                .clickable { LyricsPlaybackStore.onSeekTo?.invoke(line.startMs) }
                        )
                    }
                }
            }

            // Controlli rapidi in basso (Indietro / Play-Pausa / Avanti)
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp)
            ) {
                ControlPill("⏮") { LyricsPlaybackStore.onPrevious?.invoke() }
                ControlPill(if (isPlaying) "⏸" else "▶", large = true) { LyricsPlaybackStore.onPlayPause?.invoke() }
                ControlPill("⏭") { LyricsPlaybackStore.onNext?.invoke() }
            }
        }
    }
}

@Composable
private fun ControlPill(symbol: String, large: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (large) 58.dp else 46.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (large) 0.24f else 0.14f))
            .clickable { onClick() }
    ) {
        Text(text = symbol, color = Color.White, fontSize = if (large) 20.sp else 16.sp)
    }
}
