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

    private val carModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isCarModeActive()) {
                suspendForCarMode()
            } else {
                lastRenderKey = ""
                syncLyrics(isPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING)
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTicking) return
            syncLyrics(isPlaying = true)
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
            syncPlaybackClock(state)
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing) {
                handleMetadata(activeController?.metadata)
                startTicker()
            } else {
                stopTicker()
                lastRenderKey = ""
                syncLyrics(isPlaying = false)
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
                ctrl.transportControls.pause()
            } else {
                ctrl?.transportControls.play()
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
        val target = controllers?.filter { it.packageName != packageName }?.firstOrNull()

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
        val key = "$title\vert{}$artist"
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

            val playingNow = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
            syncLyrics(isPlaying = playingNow)

            fetchJob?.cancel()
            fetchJob = scope.launch {
                delay(250L)
                val loaded = LyricsRepository.fetchSyncedLyrics(currentTitle, currentArtist, currentDurationMs / 1000L)
                currentLines = loaded
                LyricsPlaybackStore.lines.value = loaded
                lastRenderKey = ""
                syncPlaybackClock(activeController?.playbackState)
                syncLyrics(isPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING)
                if (activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    startTicker()
                }
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

    private fun syncLyrics(isPlaying: Boolean) {
        if (currentTitle.isBlank()) return

        if (isCarModeActive()) {
            suspendForCarMode()
