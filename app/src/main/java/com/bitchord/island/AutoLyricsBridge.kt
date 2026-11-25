package com.bitchord.island

import android.graphics.Bitmap

data class AutoLyricsState(
    val trackKey: String,
    val lineIndex: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val prev2: String,
    val prev1: String,
    val curr: String,
    val next1: String,
    val next2: String,
    val cover: Bitmap?,
    val palette: CoverPalette
)

object AutoLyricsBridge {

    @Volatile
    var currentState: AutoLyricsState? = null
        private set

    private var listener: ((AutoLyricsState?) -> Unit)? = null

    fun registerListener(cb: (AutoLyricsState?) -> Unit) {
        listener = cb
        currentState?.let(cb)
    }

    fun unregisterListener(cb: (AutoLyricsState?) -> Unit) {
        if (listener === cb) {
            listener = null
        }
    }

    fun publish(state: AutoLyricsState) {
        currentState = state
        listener?.invoke(state)
    }

    fun clear() {
        currentState = null
        listener?.invoke(null)
    }
}
