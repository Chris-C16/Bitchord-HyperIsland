package com.bitchord.island

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.media.MediaBrowserService
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AutoLyricsMediaService : MediaBrowserService() {

    companion object {
        private const val ROOT_ID = "bitchord_auto_lyrics_root"
        private const val ITEM_ID_LIVE = "bitchord_auto_lyrics_live"
        private const val SUPPORTED_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
    }

    private lateinit var mediaSession: MediaSession
    private lateinit var renderer: AutoLyricsCanvasRenderer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AutoLyricsRenderThread").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    private val renderGeneration = AtomicLong(0L)
    private val fontVersion = AtomicInteger(0)

    @Volatile
    private var isClientBound = false

    @Volatile
    private var isDestroyed = false

    private var lastVisualKey: String = ""
    private var lastRenderedArt: Bitmap? = null
    private var lastMetadataKey: String = ""

    private val stateListener: (AutoLyricsState?) -> Unit = { state ->
        scheduleUpdate(state)
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            if (!LyricsPlaybackStore.isPlaying.value) {
                LyricsPlaybackStore.onPlayPause?.invoke()
            }
            scheduleUpdate(AutoLyricsBridge.currentState)
        }

        override fun onPause() {
            if (LyricsPlaybackStore.isPlaying.value) {
                LyricsPlaybackStore.onPlayPause?.invoke()
            }
            scheduleUpdate(AutoLyricsBridge.currentState)
        }

        override fun onSkipToNext() {
            LyricsPlaybackStore.onNext?.invoke()
        }

        override fun onSkipToPrevious() {
            LyricsPlaybackStore.onPrevious?.invoke()
        }

        override fun onSeekTo(pos: Long) {
            LyricsPlaybackStore.onSeekTo?.invoke(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (!LyricsPlaybackStore.isPlaying.value) {
                LyricsPlaybackStore.onPlayPause?.invoke()
            }
            scheduleUpdate(AutoLyricsBridge.currentState)
        }
    }

    override fun onCreate() {
        super.onCreate()
        renderer = AutoLyricsCanvasRenderer(this) {
            fontVersion.incrementAndGet()
            scheduleUpdate(AutoLyricsBridge.currentState, forceMetadata = true)
        }

        mediaSession = MediaSession(this, "BitChordAutoLyrics").apply {
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        AutoLyricsBridge.registerListener(stateListener)
    }

    override fun onBind(intent: Intent?): IBinder? {
        isClientBound = true
        scheduleUpdate(AutoLyricsBridge.currentState, forceMetadata = true)
        return super.onBind(intent)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isClientBound = true
        scheduleUpdate(AutoLyricsBridge.currentState, forceMetadata = true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isClientBound = false
        renderGeneration.incrementAndGet()
        lastRenderedArt = null
        lastVisualKey = ""
        lastMetadataKey = ""
        renderer.clearCache()
        return true
    }

    private fun idleState(): AutoLyricsState {
        return AutoLyricsState(
            trackKey = "idle",
            lineIndex = -1,
            title = "BitChord Lyrics",
            artist = "Avvia un brano su BitChord",
            durationMs = 0L,
            positionMs = 0L,
            isPlaying = false,
            prev2 = "",
            prev1 = "",
            curr = "In attesa della musica…",
            next1 = "",
            next2 = "",
            cover = null,
            palette = CoverPalette()
        )
    }

    private fun buildVisualKey(state: AutoLyricsState): String {
        val blankPlayFlag = if (state.curr.isBlank()) state.isPlaying else true
        val coverId = System.identityHashCode(state.cover)
        val accent = state.palette.accent.toArgb()
        return "${state.trackKey}|${state.lineIndex}|${state.prev1}|${state.curr}|${state.next1}|$blankPlayFlag|$coverId|$accent|${fontVersion.get()}"
    }

    private fun scheduleUpdate(rawState: AutoLyricsState?, forceMetadata: Boolean = false) {
        if (isDestroyed || !isClientBound) return

        val state = rawState ?: idleState()
        val visualKey = buildVisualKey(state)
        val metadataKey = "$visualKey|${state.title}|${state.artist}|${state.durationMs}"

        // Aggiorna subito play/pausa e barra di avanzamento senza aspettare il render
        val pbState = buildPlaybackState(state)
        mainHandler.post {
            if (!isDestroyed && isClientBound) {
                mediaSession.isActive = true
                mediaSession.setPlaybackState(pbState)
            }
        }

        // Se testo, copertina e metadati sono identici, non ridisegna nulla
        val cachedArt = lastRenderedArt
        if (!forceMetadata && metadataKey == lastMetadataKey && cachedArt != null) {
            return
        }

        // Se solo titolo/durata cambiano ma l'immagine dei versi è già pronta, riusa il bitmap
        if (!forceMetadata && visualKey == lastVisualKey && cachedArt != null) {
            val metadata = buildMetadata(state, cachedArt)
            lastMetadataKey = metadataKey
            mainHandler.post {
                if (!isDestroyed && isClientBound) {
                    mediaSession.setMetadata(metadata)
                }
            }
            return
        }

        val gen = renderGeneration.incrementAndGet()
        if (renderExecutor.isShutdown) return

        renderExecutor.execute {
            if (isDestroyed || !isClientBound || gen != renderGeneration.get()) return@execute

            val dynamicArt = renderer.render(state)
            if (isDestroyed || !isClientBound || gen != renderGeneration.get()) return@execute

            val metadata = buildMetadata(state, dynamicArt)
            mainHandler.post {
                if (isDestroyed || !isClientBound || gen != renderGeneration.get()) return@post
                lastVisualKey = visualKey
                lastMetadataKey = metadataKey
                lastRenderedArt = dynamicArt
                mediaSession.isActive = true
                mediaSession.setMetadata(metadata)
            }
        }
    }

    private fun buildMetadata(state: AutoLyricsState, art: Bitmap): MediaMetadata {
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "${state.trackKey}|${state.lineIndex}")
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifBlank { "BitChord Lyrics" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist.ifBlank { "BitChord" })
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.coerceAtLeast(0L))
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            .build()
    }

    private fun buildPlaybackState(state: AutoLyricsState): PlaybackState {
        return PlaybackState.Builder()
            .setActions(SUPPORTED_ACTIONS)
            .setState(
                if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionMs.coerceAtLeast(0L),
                if (state.isPlaying) 1f else 0f
            )
            .build()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        isClientBound = true
        scheduleUpdate(AutoLyricsBridge.currentState, forceMetadata = true)
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        val state = AutoLyricsBridge.currentState
        val title = state?.title?.ifBlank { "Testi Live • BitChord" } ?: "Testi Live • BitChord"
        val subtitle = state?.artist?.ifBlank { "Tocca per aprire la vista testi" }
            ?: "Tocca per aprire la vista testi"

        val desc = MediaDescription.Builder()
            .setMediaId(ITEM_ID_LIVE)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()

        val item = MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_PLAYABLE)
        result.sendResult(mutableListOf(item))
    }

    override fun onDestroy() {
        isDestroyed = true
        isClientBound = false
        renderGeneration.incrementAndGet()
        AutoLyricsBridge.unregisterListener(stateListener)
        renderExecutor.shutdownNow()
        renderer.clearCache()
        lastRenderedArt = null
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}
