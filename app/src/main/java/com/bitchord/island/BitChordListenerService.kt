package com.bitchord.island

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.*

class BitChordListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifier: HyperIslandNotifier
    private lateinit var overlay: MiniLyricsOverlay
    private lateinit var sessionManager: MediaSessionManager

    private var activeController: MediaController? = null
    private var tickerJob: Job? = null
    private var fetchJob: Job? = null
    private var isForegroundStarted = false

    private var currentTrackKey = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentCover: Bitmap? = null
    private var currentPalette = CoverPalette()
    private var currentLines: List<LyricLine> = emptyList()
    private var lastIslandLine = ""

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HyperIslandNotifier.ACTION_TOGGLE_OVERLAY && Settings.canDrawOverlays(this@BitChordListenerService)) {
                if (overlay.uiState.value.isVisible) overlay.hide() else overlay.show()
            }
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { attachToBitChord(it) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = handleMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == PlaybackState.STATE_PLAYING) startTicker()
            else { tickerJob?.cancel(); pushIslandUpdate(isPlaying = false) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifier = HyperIslandNotifier(this)
        overlay = MiniLyricsOverlay(this)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        registerReceiver(toggleReceiver, IntentFilter(HyperIslandNotifier.ACTION_TOGGLE_OVERLAY), RECEIVER_NOT_EXPORTED)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, BitChordListenerService::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            attachToBitChord(sessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {}
    }

    private fun attachToBitChord(controllers: List<MediaController>?) {
        val target = controllers?.firstOrNull { it.packageName.contains("bitchord", ignoreCase = true) } ?: controllers?.firstOrNull()
        if (target?.sessionToken == activeController?.sessionToken) return
        activeController?.unregisterCallback(mediaCallback)
        activeController = target
        target?.registerCallback(mediaCallback)
        if (target != null) {
            handleMetadata(target.metadata)
            if (target.playbackState?.state == PlaybackState.STATE_PLAYING) startTicker()
        } else {
            tickerJob?.cancel()
            overlay.hide()
            if (isForegroundStarted) { stopForeground(STOP_FOREGROUND_REMOVE); isForegroundStarted = false }
            notifier.cancel()
        }
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (title.isBlank()) return
        val key = "$title|$artist"
        currentTitle = title
        currentArtist = artist
        if (cover != null) { currentCover = cover; currentPalette = LyricsRepository.extractPalette(cover) }
        if (key != currentTrackKey) {
            currentTrackKey = key
            currentLines = emptyList()
            lastIslandLine = ""
            pushIslandUpdate(isPlaying = true)
            fetchJob?.cancel()
            fetchJob = scope.launch {
                currentLines = LyricsRepository.fetchSyncedLyrics(title, artist, durationMs / 1000L)
                updateOverlayState(computeExactPositionMs())
            }
        }
    }

    private fun computeExactPositionMs(): Long {
        val state = activeController?.playbackState ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        return state.position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val posMs = computeExactPositionMs()
                val activeLine = currentLines.lastOrNull { posMs >= it.startMs }?.fullText.orEmpty()
                if (activeLine != lastIslandLine) { lastIslandLine = activeLine; pushIslandUpdate(isPlaying = true) }
                if (overlay.uiState.value.isVisible) updateOverlayState(posMs)
                delay(33L)
            }
        }
    }

    private fun pushIslandUpdate(isPlaying: Boolean) {
        if (currentTitle.isBlank()) return
        val notif = notifier.buildNotification(currentTitle, currentArtist, lastIslandLine, currentCover, isPlaying)
        if (!isForegroundStarted) {
            try {
                startForeground(HyperIslandNotifier.NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                isForegroundStarted = true
            } catch (_: Exception) {
                notifier.update(currentTitle, currentArtist, lastIslandLine, currentCover, isPlaying)
            }
        } else {
            notifier.update(currentTitle, currentArtist, lastIslandLine, currentCover, isPlaying)
        }
    }

    private fun updateOverlayState(posMs: Long) {
        overlay.uiState.value = overlay.uiState.value.copy(
            title = currentTitle, artist = currentArtist, cover = currentCover,
            palette = currentPalette, lines = currentLines, positionMs = posMs
        )
    }

    override fun onDestroy() {
        tickerJob?.cancel(); fetchJob?.cancel()
        activeController?.unregisterCallback(mediaCallback)
        unregisterReceiver(toggleReceiver)
        overlay.destroy(); scope.cancel()
        super.onDestroy()
    }
}
