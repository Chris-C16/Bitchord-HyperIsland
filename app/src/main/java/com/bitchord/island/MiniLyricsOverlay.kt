package com.bitchord.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs

data class OverlayUiState(
    val isVisible: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val cover: Bitmap? = null,
    val palette: CoverPalette = CoverPalette(),
    val lines: List<LyricLine> = emptyList(),
    val positionMs: Long = 0L
)

class MiniLyricsOverlay(private val context: Context) : LifecycleOwner, SavedStateRegistryOwner {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private var composeView: ComposeView? = null
    val uiState = mutableStateOf(OverlayUiState())

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun show() {
        if (composeView != null) {
            uiState.value = uiState.value.copy(isVisible = true)
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 115
        }
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@MiniLyricsOverlay)
            setViewTreeSavedStateRegistryOwner(this@MiniLyricsOverlay)
            setContent {
                val state by uiState
                if (state.isVisible) BitChordMiniLyricsCard(state = state, onDismiss = { hide() })
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager.addView(view, params)
        composeView = view
        uiState.value = uiState.value.copy(isVisible = true)
    }

    fun hide() { uiState.value = uiState.value.copy(isVisible = false) }

    fun destroy() {
        composeView?.let { windowManager.removeView(it); composeView = null }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

@Composable
private fun BitChordMiniLyricsCard(state: OverlayUiState, onDismiss: () -> Unit) {
    val primaryColor by animateColorAsState(state.palette.primary, tween(700), label = "c1")
    val secondaryColor by animateColorAsState(state.palette.secondary, tween(700), label = "c2")
    val accentColor by animateColorAsState(state.palette.accent, tween(700), label = "c3")

    val shift by rememberInfiniteTransition(label = "bgMesh").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "meshShift"
    )

    val activeIndex = remember(state.lines, state.positionMs) {
        state.lines.indexOfLast { state.positionMs >= it.startMs }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -18f) onDismiss() }
            }
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(primaryColor.copy(0.94f), accentColor.copy(0.65f), secondaryColor.copy(0.96f)),
                    start = Offset(0f, 300f * shift),
                    end = Offset(900f, 600f * (1f - shift))
                )
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (state.cover != null) {
                    Image(
                        bitmap = state.cover.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.title.ifBlank { "BitChord" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.artist, color = Color.White.copy(0.65f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(modifier = Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(0.35f)))
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (state.lines.isEmpty()) {
                Text("Caricamento testo sincronizzato...", color = Color.White.copy(0.6f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxWidth().height(106.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(state.lines) { index, line ->
                        val distance = abs(index - activeIndex)
                        val isCurrent = index == activeIndex
                        val blurRadius = when (distance) { 0 -> 0.dp; 1 -> 1.4.dp; else -> 3.dp }
                        val styledLine = buildAnnotatedString {
                            if (!isCurrent) {
                                withStyle(SpanStyle(color = Color.White.copy(if (index < activeIndex) 0.35f else 0.48f), fontWeight = FontWeight.Medium)) {
                                    append(line.fullText)
                                }
                            } else {
                                line.words.forEach { word ->
                                    val progress = ((state.positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                                    withStyle(SpanStyle(color = Color.White.copy(0.42f + 0.58f * progress), fontWeight = FontWeight.Bold)) {
                                        append(word.text)
                                    }
                                }
                            }
                        }
                        Text(styledLine, fontSize = if (isCurrent) 13.5.sp else 12.sp, lineHeight = if (isCurrent) 17.sp else 15.sp, modifier = Modifier.fillMaxWidth().blur(blurRadius))
                    }
                }
            }
        }
    }
}
