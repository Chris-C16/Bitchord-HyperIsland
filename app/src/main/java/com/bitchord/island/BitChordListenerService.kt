package com.bitchord.island

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.*

class BitChordListenerService : NotificationListenerService() {

    companion object {
        private const val TICK_INTERVAL_MS = 100L
        private const val MIN_NOTIFY_INTERVAL_MS = 220L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifier: HyperIslandNotifier
    private lateinit var sessionManager: MediaSessionManager
    private lateinit var uiModeManager: UiModeManager

    private var activeController: MediaController? = null
    private var fetchJob: Job? = null
    private var isForegroundStarted = false
    private var isTicking = false

    private var currentTrackKey = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentDurationMs = 0L
    private var currentCover: Bitmap? = null
    private var currentPalette = CoverPalette()
    private var currentLines: List<LyricLine> = emptyList()

    private var lastRenderKey = ""
    private var lastNotifiedTrackKey = ""
    private var lastNotifiedIdx = Int.MIN_VALUE
    private var lastNotifiedPlaying = false
    private var lastNotifyRealtimeMs = 0L
    private var animStep = 0

    private val carModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isCarModeActive()) {
                suspendForCarMode()
            } else {
                lastRenderKey = ""
                syncLyrics(
                    isPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING,
                    forceImmediate = true
                )
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTicking) return
            syncLyrics(isPlaying = true, forceImmediate = false)
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        attachToBitChord(it)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncPlaybackClock(state)
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing) {
                handleMetadata(activeController?.metadata)
                startTicker()
                syncLyrics(isPlaying = true, forceImmediate = true)
            } else {
                stopTicker()
                lastRenderKey = ""
                syncLyrics(isPlaying = false, forceImmediate = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifier = HyperIslandNotifier(this)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        uiModeManager = getSystemService(UiModeManager::class.java)

        val carFilter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        registerReceiver(carModeReceiver, carFilter, RECEIVER_NOT_EXPORTED)

        LyricsPlaybackStore.onPlayPause = {
            val ctrl = activeController
            if (ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                ctrl.transportControls?.pause()
            } else {
                ctrl?.transportControls?.play()
            }
        }
        LyricsPlaybackStore.onNext = { activeController?.transportControls?.skipToNext() }
        LyricsPlaybackStore.onPrevious = { activeController?.transportControls?.skipToPrevious() }
        LyricsPlaybackStore.onSeekTo = { posMs -> activeController?.transportControls?.seekTo(posMs) }
    }

    private fun isCarModeActive(): Boolean {
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    private fun suspendForCarMode() {
        notifier.cancel()
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, BitChordListenerService::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            attachToBitChord(sessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {
        }
    }

    private fun attachToBitChord(controllers: List<MediaController>?) {
        val candidates = controllers?.filter { it.packageName != packageName }.orEmpty()
        val target = candidates.firstOrNull { it.packageName.contains("bitchord", ignoreCase = true) }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()

        if (target?.sessionToken == activeController?.sessionToken) {
            if (target != null) {
                handleMetadata(target.metadata)
                syncPlaybackClock(target.playbackState)
            }
            return
        }

        activeController?.unregisterCallback(mediaCallback)
        activeController = target
        target?.registerCallback(mediaCallback)

        if (target != null) {
            handleMetadata(target.metadata)
            syncPlaybackClock(target.playbackState)
            if (target.playbackState?.state == PlaybackState.STATE_PLAYING) {
                startTicker()
            }
        } else {
            stopTicker()
            LyricsPlaybackStore.isPlaying.value = false
            suspendForCarMode()
        }
    }

    private fun syncPlaybackClock(state: PlaybackState?) {
        state ?: return
        val playing = state.state == PlaybackState.STATE_PLAYING
        LyricsPlaybackStore.isPlaying.value = playing
        LyricsPlaybackStore.basePositionMs.value = state.position
        LyricsPlaybackStore.baseRealtimeMs.value =
            if (state.lastPositionUpdateTime > 0L) state.lastPositionUpdateTime else SystemClock.elapsedRealtime()
        LyricsPlaybackStore.speed.value = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().trim()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (title.isBlank()) return
        val key = "$title|$artist"
        currentTitle = title
        currentArtist = artist
        if (durationMs > 0L) {
            currentDurationMs = durationMs
            LyricsPlaybackStore.durationMs.value = durationMs
        }

        LyricsPlaybackStore.title.value = title
        LyricsPlaybackStore.artist.value = artist

        if (cover != null) {
            currentCover = cover
            currentPalette = LyricsRepository.extractPalette(cover)
            LyricsPlaybackStore.cover.value = cover
            LyricsPlaybackStore.palette.value = currentPalette
        }

        if (key != currentTrackKey || (currentLines.isEmpty() && fetchJob?.isActive != true)) {
            currentTrackKey = key
            currentLines = emptyList()
            LyricsPlaybackStore.lines.value = emptyList()
            LyricsPlaybackStore.syncOffsetMs.value = 0L
            lastRenderKey = ""
            lastNotifiedIdx = Int.MIN_VALUE

            val playingNow = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
            syncLyrics(isPlaying = playingNow, forceImmediate = true)

            fetchJob?.cancel()
            fetchJob = scope.launch {
                delay(200L)
                val loaded = LyricsRepository.fetchSyncedLyrics(
                    currentTitle,
                    currentArtist,
                    currentDurationMs / 1000L
                )
                currentLines = loaded
                LyricsPlaybackStore.lines.value = loaded
                lastRenderKey = ""
                lastNotifiedIdx = Int.MIN_VALUE
                syncPlaybackClock(activeController?.playbackState)
                val stillPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                syncLyrics(isPlaying = stillPlaying, forceImmediate = true)
                if (stillPlaying) startTicker()
            }
        }
    }

    private fun startTicker() {
        if (isTicking) return
        isTicking = true
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun stopTicker() {
        isTicking = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun syncLyrics(isPlaying: Boolean, forceImmediate: Boolean) {
        if (currentTitle.isBlank()) return

        if (isCarModeActive()) {
            suspendForCarMode()
            return
        }

        val posMs = LyricsPlaybackStore.currentPositionMs()
        val idx = if (currentLines.isEmpty()) -1 else currentLines.indexOfLast { posMs >= it.startMs }
        val currLine = if (idx >= 0) currentLines[idx] else null
        val nextStartMs = if (idx >= 0 && idx + 1 < currentLines.size) currentLines[idx + 1].startMs else null

        val chunk = notifier.computePillChunk(currLine, nextStartMs, posMs, currentTitle, isPlaying)
        val renderKey = "$currentTrackKey|$idx|${chunk.index}|${chunk.isInstrumental}|$isPlaying"
        if (renderKey == lastRenderKey) return

        val now = SystemClock.elapsedRealtime()
        val isCriticalTransition = forceImmediate ||
            !isForegroundStarted ||
            currentTrackKey != lastNotifiedTrackKey ||
            idx != lastNotifiedIdx ||
            isPlaying != lastNotifiedPlaying

        if (!isCriticalTransition && now - lastNotifyRealtimeMs < MIN_NOTIFY_INTERVAL_MS) {
            return
        }

        lastRenderKey = renderKey
        lastNotifiedTrackKey = currentTrackKey
        lastNotifiedIdx = idx
        lastNotifiedPlaying = isPlaying
        lastNotifyRealtimeMs = now
        if (isPlaying && !chunk.isInstrumental) animStep++

        val prev = if (idx > 0) currentLines[idx - 1].fullText else ""
        val next1 = if (idx >= 0 && idx + 1 < currentLines.size) {
            currentLines[idx + 1].fullText
        } else if (idx == -1 && currentLines.isNotEmpty()) {
            currentLines[0].fullText
        } else {
            ""
        }

        if (!isForegroundStarted) {
            val notif = notifier.buildNotification(
                title = currentTitle,
                artist = currentArtist,
                prevLyric = prev,
                currentLine = currLine,
                nextLineStartMs = nextStartMs,
                nextLyric1 = next1,
                palette = currentPalette,
                songPositionMs = posMs,
                songDurationMs = currentDurationMs,
                isPlaying = isPlaying,
                animStep = animStep
            )
            try {
                startForeground(
                    HyperIslandNotifier.NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                isForegroundStarted = true
            } catch (_: Exception) {
                notifier.update(
                    title = currentTitle,
                    artist = currentArtist,
                    prevLyric = prev,
                    currentLine = currLine,
                    nextLineStartMs = nextStartMs,
                    nextLyric1 = next1,
                    palette = currentPalette,
                    songPositionMs = posMs,
                    songDurationMs = currentDurationMs,
                    isPlaying = isPlaying,
                    animStep = animStep
                )
            }
        } else {
            notifier.update(
                title = currentTitle,
                artist = currentArtist,
                prevLyric = prev,
                currentLine = currLine,
                nextLineStartMs = nextStartMs,
                nextLyric1 = next1,
                palette = currentPalette,
                songPositionMs = posMs,
                songDurationMs = currentDurationMs,
                isPlaying = isPlaying,
                animStep = animStep
            )
        }
    }

    override fun onDestroy() {
        stopTicker()
        fetchJob?.cancel()
        activeController?.unregisterCallback(mediaCallback)
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(carModeReceiver)
        } catch (_: Exception) {
        }
        suspendForCarMode()
        scope.cancel()
        super.onDestroy()
    }
}
