package com.bitchord.island

import android.content.ComponentName
import android.content.pm.ServiceInfo
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifier: HyperIslandNotifier
    private lateinit var sessionManager: MediaSessionManager

    private var activeController: MediaController? = null
    private var fetchJob: Job? = null
    private var isForegroundStarted = false
    private var isTicking = false

    private var currentTrackKey = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentCover: Bitmap? = null
    private var currentPalette = CoverPalette()
    private var currentLines: List<LyricLine> = emptyList()
    private var lastLineIndex = -2

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTicking) return
            syncLyricsToIsland(isPlaying = true)
            handler.postDelayed(this, 180L)
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
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing) {
                startTicker()
            } else {
                stopTicker()
                lastLineIndex = -2
                syncLyricsToIsland(isPlaying = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifier = HyperIslandNotifier(this)
        sessionManager = getSystemService(MediaSessionManager::class.java)
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
        val target = controllers?.firstOrNull {
            it.packageName.contains("bitchord", ignoreCase = true)
        } ?: controllers?.firstOrNull()

        if (target?.sessionToken == activeController?.sessionToken) return

        activeController?.unregisterCallback(mediaCallback)
        activeController = target
        target?.registerCallback(mediaCallback)

        if (target != null) {
            handleMetadata(target.metadata)
            if (target.playbackState?.state == PlaybackState.STATE_PLAYING) {
                startTicker()
            }
        } else {
            stopTicker()
            if (isForegroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundStarted = false
            }
            notifier.cancel()
        }
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (title.isBlank()) return
        val key = "$title|$artist"
        currentTitle = title
        currentArtist = artist
        if (cover != null) {
            currentCover = cover
            currentPalette = LyricsRepository.extractPalette(cover)
        }

        if (key != currentTrackKey) {
            currentTrackKey = key
            currentLines = emptyList()
            lastLineIndex = -2
            syncLyricsToIsland(isPlaying = true)

            fetchJob?.cancel()
            fetchJob = scope.launch {
                currentLines = LyricsRepository.fetchSyncedLyrics(title, artist, durationMs / 1000L)
                lastLineIndex = -2
                syncLyricsToIsland(isPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING)
            }
        }
    }

    private fun computeExactPositionMs(): Long {
        val state = activeController?.playbackState ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val delta = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
        return state.position + (delta * speed).toLong()
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

    private fun syncLyricsToIsland(isPlaying: Boolean) {
        if (currentTitle.isBlank()) return
        val posMs = computeExactPositionMs()
        val idx = if (currentLines.isEmpty()) -1 else currentLines.indexOfLast { posMs >= it.startMs }

        // Aggiorna la HyperIsland solo quando cambia effettivamente il verso
        if (idx == lastLineIndex) return
        lastLineIndex = idx

        val prev = if (idx > 0) currentLines[idx - 1].fullText else ""
        val curr = if (idx >= 0) currentLines[idx].fullText else ""
        val next = if (idx >= 0 && idx + 1 < currentLines.size) currentLines[idx + 1].fullText else ""

        if (!isForegroundStarted) {
            val notif = notifier.buildNotification(
                currentTitle, currentArtist, prev, curr, next, currentCover, currentPalette, isPlaying
            )
            try {
                startForeground(
                    HyperIslandNotifier.NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                isForegroundStarted = true
            } catch (_: Exception) {
                notifier.update(currentTitle, currentArtist, prev, curr, next, currentCover, currentPalette, isPlaying)
            }
        } else {
            notifier.update(currentTitle, currentArtist, prev, curr, next, currentCover, currentPalette, isPlaying)
        }
    }

    override fun onDestroy() {
        stopTicker()
        fetchJob?.cancel()
        activeController?.unregisterCallback(mediaCallback)
        scope.cancel()
        super.onDestroy()
    }
}
