package com.bitchord.island

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

object LyricsPlaybackStore {
    const val DEFAULT_SYNC_OFFSET_MS = 600L

    val title = MutableStateFlow("")
    val artist = MutableStateFlow("")
    val cover = MutableStateFlow<Bitmap?>(null)
    val palette = MutableStateFlow(CoverPalette())
    val lines = MutableStateFlow<List<LyricLine>>(emptyList())
    val isPlaying = MutableStateFlow(false)
    val durationMs = MutableStateFlow(0L)
    val basePositionMs = MutableStateFlow(0L)
    val baseRealtimeMs = MutableStateFlow(0L)
    val speed = MutableStateFlow(1f)
    val syncOffsetMs = MutableStateFlow(DEFAULT_SYNC_OFFSET_MS)
    val activePlayerPackage = MutableStateFlow("")

    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    fun rawPositionMs(): Long {
        val base = basePositionMs.value
        if (!isPlaying.value) return base.coerceAtLeast(0L)
        val elapsed = (SystemClock.elapsedRealtime() - baseRealtimeMs.value).coerceAtLeast(0L)
        return (base + (elapsed * speed.value).toLong()).coerceAtLeast(0L)
    }

    fun currentPositionMs(): Long {
        return (rawPositionMs() + syncOffsetMs.value).coerceAtLeast(0L)
    }
}
