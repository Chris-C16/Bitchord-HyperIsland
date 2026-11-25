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
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.*

class BitChordListenerService : NotificationListenerService() {

    companion object {
        private const val TICK_INTERVAL_MS = 120L
        private const val MIN_LINE_INTERVAL_MS = 120L

        @Volatile
        private var instance: BitChordListenerService? = null

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            return enabled.contains(context.packageName)
        }

        fun ensureConnected(context: Context) {
            if (!isNotificationAccessGranted(context)) return
            val current = instance
            if (current != null) {
                current.refreshActiveSessions()
            } else {
                try {
                    requestRebind(ComponentName(context, BitChordListenerService::class.java))
                } catch (_: Throwable) {
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifier: HyperIslandNotifier
    private lateinit var sessionManager: MediaSessionManager
    private lateinit var uiModeManager: UiModeManager

    private var activeController: MediaController? = null
    private var fetchJob: Job? = null
    private var paletteJob: Job? = null
    private var isForegroundStarted = false
    private var isTicking = false
    private var isSessionsRegistered = false

    private var currentTrackKey = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentDurationMs = 0L
    private var currentCover: Bitmap? = null
    private var currentPalette = CoverPalette()
    private var currentLines: List<LyricLine> = emptyList()

    private var lastRenderKey = ""
    private var lastNotifyRealtimeMs = 0L

    private val carModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isCarModeActive()) {
                hidePhoneNotificationOnly()
            }
            lastRenderKey = ""
            syncLyrics(
                isPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING,
                forceImmediate = true
            )
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                refreshActiveSessions()
                val playing = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) {
                    lastRenderKey = ""
                    syncLyrics(isPlaying = true, forceImmediate = true)
                }
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
            lastRenderKey = ""
            if (playing) {
                handleMetadata(activeController?.metadata)
                startTicker()
                syncLyrics(isPlaying = true, forceImmediate = true)
            } else {
                stopTicker()
                syncLyrics(isPlaying = false, forceImmediate = true)
            }
        }

        override fun onSessionDestroyed() {
            activeController?.unregisterCallback(this)
            activeController = null
            stopTicker()
            LyricsPlaybackStore.isPlaying.value = false
            refreshActiveSessions()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifier = HyperIslandNotifier(this)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        uiModeManager = getSystemService(UiModeManager::class.java)

        val carFilter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        registerReceiver(carModeReceiver, carFilter, RECEIVER_NOT_EXPORTED)

        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenOnReceiver, screenFilter, RECEIVER_NOT_EXPORTED)

        LyricsPlaybackStore.onPlayPause = {
            refreshActiveSessions()
            val ctrl = activeController
            if (ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                ctrl.transportControls?.pause()
            } else {
                ctrl?.transportControls?.play()
            }
        }
        LyricsPlaybackStore.onNext = {
            refreshActiveSessions()
            activeController?.transportControls?.skipToNext()
        }
        LyricsPlaybackStore.onPrevious = {
            refreshActiveSessions()
            activeController?.transportControls?.skipToPrevious()
        }
        LyricsPlaybackStore.onSeekTo = { posMs ->
            activeController?.transportControls?.seekTo(posMs)
        }

        refreshActiveSessions()
    }

    private fun isCarModeActive(): Boolean {
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    private fun hidePhoneNotificationOnly() {
        notifier.cancel()
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshActiveSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isSessionsRegistered = false
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        try {
            requestRebind(ComponentName(this, BitChordListenerService::class.java))
        } catch (_: Throwable) {
        }
    }

    fun refreshActiveSessions() {
        val component = ComponentName(this, BitChordListenerService::class.java)
        try {
            if (!isSessionsRegistered) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
                isSessionsRegistered = true
            }
            attachToBitChord(sessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {
        }
    }

    private fun attachToBitChord(controllers: List<MediaController>?) {
        val candidates = controllers?.filter { it.packageName != packageName }.orEmpty()
        val target = candidates.firstOrNull {
            it.packageName.contains("bitchord", ignoreCase = true) &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
            ?: candidates.firstOrNull { it.packageName.contains("bitchord", ignoreCase = true) }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()

        if (target?.sessionToken == activeController?.sessionToken) {
            if (target != null) {
                handleMetadata(target.metadata)
                syncPlaybackClock(target.playbackState)
                val playing = target.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) {
                    startTicker()
                    syncLyrics(isPlaying = true, forceImmediate = false)
                }
            }
            return
        }

        activeController?.unregisterCallback(mediaCallback)
        activeController = target
        target?.registerCallback(mediaCallback)

        if (target != null) {
            lastRenderKey = ""
            handleMetadata(target.metadata)
            syncPlaybackClock(target.playbackState)
            val playing = target.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) {
                startTicker()
            } else {
                stopTicker()
            }
            syncLyrics(isPlaying = playing, forceImmediate = true)
        } else {
            stopTicker()
            LyricsPlaybackStore.isPlaying.value = false
            AutoLyricsBridge.clear()
            hidePhoneNotificationOnly()
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

        if (cover != null && cover !== currentCover) {
            currentCover = cover
            LyricsPlaybackStore.cover.value = cover
            paletteJob?.cancel()
            paletteJob = scope.launch {
                val extracted = withContext(Dispatchers.Default) {
                    LyricsRepository.extractPalette(cover)
                }
                currentPalette = extracted
                LyricsPlaybackStore.palette.value = extracted
                lastRenderKey = ""
                val stillPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                syncLyrics(isPlaying = stillPlaying, forceImmediate = true)
            }
        }

        if (key != currentTrackKey || (currentLines.isEmpty() && fetchJob?.isActive != true)) {
            currentTrackKey = key
            currentLines = emptyList()
            LyricsPlaybackStore.lines.value = emptyList()
            LyricsPlaybackStore.syncOffsetMs.value = 0L
            lastRenderKey = ""

            fetchJob?.cancel()
            fetchJob = scope.launch {
                val loaded = LyricsRepository.fetchSyncedLyrics(
                    currentTitle,
                    currentArtist,
                    currentDurationMs / 1000L
                )
                currentLines = loaded
                LyricsPlaybackStore.lines.value = loaded
                lastRenderKey = ""
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

        val posMs = LyricsPlaybackStore.currentPositionMs()
        val idx = if (currentLines.isEmpty()) -1 else currentLines.indexOfLast { posMs >= it.startMs }
        val currLine = if (idx >= 0) currentLines[idx] else null

        val renderKey = "$currentTrackKey|$idx|$isPlaying"
        if (renderKey == lastRenderKey) return

        val now = SystemClock.elapsedRealtime()
        if (!forceImmediate && isForegroundStarted && now - lastNotifyRealtimeMs < MIN_LINE_INTERVAL_MS) {
            return
        }

        lastRenderKey = renderKey
        lastNotifyRealtimeMs = now

        val prev2 = if (idx > 1) currentLines[idx - 2].fullText else ""
        val prev1 = if (idx > 0) currentLines[idx - 1].fullText else ""
        val currText = currLine?.fullText.orEmpty()
        val next1 = when {
            idx >= 0 && idx + 1 < currentLines.size -> currentLines[idx + 1].fullText
            idx == -1 && currentLines.isNotEmpty() -> currentLines[0].fullText
            else -> ""
        }
        val next2 = when {
            idx >= 0 && idx + 2 < currentLines.size -> currentLines[idx + 2].fullText
            idx == -1 && currentLines.size > 1 -> currentLines[1].fullText
            else -> ""
        }

        AutoLyricsBridge.publish(
            AutoLyricsState(
                trackKey = currentTrackKey,
                lineIndex = idx,
                title = currentTitle,
                artist = currentArtist,
                durationMs = currentDurationMs,
                positionMs = posMs,
                isPlaying = isPlaying,
                prev2 = prev2,
                prev1 = prev1,
                curr = currText,
                next1 = next1,
                next2 = next2,
                cover = currentCover,
                palette = currentPalette
            )
        )

        if (isCarModeActive()) {
            hidePhoneNotificationOnly()
            return
        }

        if (!isForegroundStarted) {
            val notif = notifier.buildNotification(
                title = currentTitle,
                prevLyric = prev1,
                currentLine = currLine,
                nextLyric1 = next1,
                cover = currentCover,
                palette = currentPalette,
                isPlaying = isPlaying
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
                    prevLyric = prev1,
                    currentLine = currLine,
                    nextLyric1 = next1,
                    cover = currentCover,
                    palette = currentPalette,
                    isPlaying = isPlaying
                )
            }
        } else {
            notifier.update(
                title = currentTitle,
                prevLyric = prev1,
                currentLine = currLine,
                nextLyric1 = next1,
                cover = currentCover,
                palette = currentPalette,
                isPlaying = isPlaying
            )
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopTicker()
        fetchJob?.cancel()
        paletteJob?.cancel()
        activeController?.unregisterCallback(mediaCallback)
        isSessionsRegistered = false
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(carModeReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(screenOnReceiver)
        } catch (_: Exception) {
        }
        AutoLyricsBridge.clear()
        hidePhoneNotificationOnly()
        scope.cancel()
        super.onDestroy()
    }
}
