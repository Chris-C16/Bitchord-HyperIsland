package com.bitchord.island

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService

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

    private val stateListener: (AutoLyricsState?) -> Unit = { state ->
        updateSession(state)
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            if (!LyricsPlaybackStore.isPlaying.value) {
                LyricsPlaybackStore.onPlayPause?.invoke()
            }
            updateSession(AutoLyricsBridge.currentState)
        }

        override fun onPause() {
            if (LyricsPlaybackStore.isPlaying.value) {
                LyricsPlaybackStore.onPlayPause?.invoke()
            }
            updateSession(AutoLyricsBridge.currentState)
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
            updateSession(AutoLyricsBridge.currentState)
        }
    }

    override fun onCreate() {
        super.onCreate()
        renderer = AutoLyricsCanvasRenderer(this) {
            updateSession(AutoLyricsBridge.currentState)
        }

        mediaSession = MediaSession(this, "BitChordAutoLyrics").apply {
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        AutoLyricsBridge.registerListener(stateListener)

        if (AutoLyricsBridge.currentState == null) {
            publishIdleState()
        }
    }

    private fun publishIdleState() {
        val idleState = AutoLyricsState(
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
        updateSession(idleState)
    }

    private fun updateSession(state: AutoLyricsState?) {
        if (state == null) {
            publishIdleState()
            return
        }

        val dynamicArt = renderer.render(state)
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "${state.trackKey}|${state.lineIndex}")
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifBlank { "BitChord Lyrics" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist.ifBlank { "BitChord" })
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.coerceAtLeast(0L))
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, dynamicArt)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, dynamicArt)
            .build()

        val pbState = PlaybackState.Builder()
            .setActions(SUPPORTED_ACTIONS)
            .setState(
                if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionMs.coerceAtLeast(0L),
                if (state.isPlaying) 1f else 0f
            )
            .build()

        mediaSession.isActive = true
        mediaSession.setMetadata(metadata)
        mediaSession.setPlaybackState(pbState)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
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
        AutoLyricsBridge.unregisterListener(stateListener)
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}
