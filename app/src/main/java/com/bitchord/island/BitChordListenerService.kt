package com.bitchord.island

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.view.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class BitChordListenerService : NotificationListenerService() {

    companion object {
        private const val MIN_TICK_DELAY_MS = 40L
        private const val MAX_TICK_DELAY_MS = 450L
        private const val MIN_LINE_INTERVAL_MS = 100L
        private const val BACKWARD_JITTER_GUARD_MS = 1400L
        private const val FORWARD_JITTER_GUARD_MS = 220L
        private const val LINE_HYSTERESIS_MS = 1500L
        private const val HARD_REBIND_COOLDOWN_MS = 2500L

        @Volatile
        private var instance: BitChordListenerService? = null

        @Volatile
        private var lastHardRebindMs = 0L

        val isServiceConnected = MutableStateFlow(false)

        private val companionHandler = Handler(Looper.getMainLooper())

        fun isRunning(): Boolean = instance != null && isServiceConnected.value

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            return enabled.contains(context.packageName)
        }

        fun areNotificationsEnabled(context: Context): Boolean {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val enabled = nm?.areNotificationsEnabled() ?: true
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                enabled && granted
            } else {
                enabled
            }
        }

        fun forceHardRebind(context: Context) {
            val appCtx = context.applicationContext
            if (!isNotificationAccessGranted(appCtx)) return
            val now = SystemClock.elapsedRealtime()
            if (lastHardRebindMs != 0L && now - lastHardRebindMs < HARD_REBIND_COOLDOWN_MS) return
            lastHardRebindMs = now

            val component = ComponentName(appCtx, BitChordListenerService::class.java)
            val pm = appCtx.packageManager
            try {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Throwable) {
            }

            companionHandler.postDelayed({
                try {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Throwable) {
                }
                try {
                    requestRebind(component)
                } catch (_: Throwable) {
                }
            }, 180L)
        }

        fun ensureConnected(context: Context, forceRepublish: Boolean = false) {
            val appCtx = context.applicationContext
            AppPreferences.init(appCtx)
            if (!isNotificationAccessGranted(appCtx)) return

            val component = ComponentName(appCtx, BitChordListenerService::class.java)
            val pm = appCtx.packageManager
            try {
                if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (_: Throwable) {
            }

            val current = instance
            if (current != null && isServiceConnected.value) {
                if (forceRepublish) {
                    current.forceRepublishPill()
                } else {
                    current.refreshActiveSessions()
                }
            } else {
                try {
                    requestRebind(component)
                } catch (_: Throwable) {
                }
                companionHandler.postDelayed({
                    if (!isRunning()) {
                        forceHardRebind(appCtx)
                    }
                }, 450L)
            }
        }

        fun triggerPlayPreferred(context: Context) {
            AppPreferences.init(context)
            val current = instance
            if (current != null && isServiceConnected.value) {
                current.handlePlayPauseCommand()
            } else {
                ensureConnected(context, forceRepublish = true)
                launchOrResumePreferredApp(context)
            }
        }

        private fun launchOrResumePreferredApp(context: Context) {
            val pkg = AppPreferences.getLaunchTargetPackage(context) ?: return
            try {
                val now = SystemClock.uptimeMillis()
                val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage(pkg)
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
                }
                val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage(pkg)
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
                }
                context.sendBroadcast(down)
                context.sendBroadcast(up)
            } catch (_: Throwable) {
            }

            try {
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch != null) {
                    context.startActivity(launch)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifier: HyperIslandNotifier
    private lateinit var wearBridge: WearLyricsBridge
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
    private var currentCoverSig = 0L
    private var currentPalette = CoverPalette()
    private var currentLines: List<LyricLine> = emptyList()

    private var lastLineIndex = -1
    private var lastRenderKey = ""
    private var lastNotifyRealtimeMs = 0L
    private var userSeekGraceUntilMs = 0L

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
                    scheduleNextTick()
                }
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTicking) return
            syncLyrics(isPlaying = true, forceImmediate = false)
            scheduleNextTick()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        attachToPreferredSession(it)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val clockChanged = syncPlaybackClock(state, forceSync = false)
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing) {
                activeController?.packageName?.let {
                    AppPreferences.rememberLastActivePackage(this@BitChordListenerService, it)
                }
            }
            if (clockChanged) {
                wearBridge.publishPlayback(
                    trackKey = currentTrackKey,
                    isPlaying = playing,
                    positionMs = LyricsPlaybackStore.currentPositionMs(),
                    speed = LyricsPlaybackStore.speed.value
                )
            }
            if (playing) {
                if (currentTitle.isBlank()) {
                    handleMetadata(activeController?.metadata)
                }
                startTicker()
                syncLyrics(isPlaying = true, forceImmediate = clockChanged)
            } else {
                stopTicker()
                lastRenderKey = ""
                syncLyrics(isPlaying = false, forceImmediate = true)
            }
        }

        override fun onSessionDestroyed() {
            activeController?.unregisterCallback(this)
            activeController = null
            LyricsPlaybackStore.activePlayerPackage.value = ""
            stopTicker()
            LyricsPlaybackStore.isPlaying.value = false
            wearBridge.clearTrack()
            refreshActiveSessions()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppPreferences.init(this)
        notifier = HyperIslandNotifier(this)
        wearBridge = WearLyricsBridge(this)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        uiModeManager = getSystemService(UiModeManager::class.java)

        val carFilter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        registerReceiver(carModeReceiver, carFilter, RECEIVER_NOT_EXPORTED)

        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenOnReceiver, screenFilter, RECEIVER_NOT_EXPORTED)

        LyricsPlaybackStore.onPlayPause = { handlePlayPauseCommand() }
        LyricsPlaybackStore.onNext = {
            refreshActiveSessions()
            activeController?.transportControls?.skipToNext()
        }
        LyricsPlaybackStore.onPrevious = {
            refreshActiveSessions()
            activeController?.transportControls?.skipToPrevious()
        }
        LyricsPlaybackStore.onSeekTo = { posMs ->
            val targetPos = posMs.coerceAtLeast(0L)
            userSeekGraceUntilMs = SystemClock.elapsedRealtime() + 1600L
            LyricsPlaybackStore.basePositionMs.value = targetPos
            LyricsPlaybackStore.baseRealtimeMs.value = SystemClock.elapsedRealtime()
            lastLineIndex = -1
            lastRenderKey = ""
            activeController?.transportControls?.seekTo(targetPos)
            val playing = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
            wearBridge.publishPlayback(
                trackKey = currentTrackKey,
                isPlaying = playing,
                positionMs = targetPos,
                speed = LyricsPlaybackStore.speed.value,
                force = true
            )
            syncLyrics(isPlaying = playing, forceImmediate = true)
            if (playing) scheduleNextTick()
        }

        refreshActiveSessions()
    }

    private fun handlePlayPauseCommand() {
        refreshActiveSessions()
        val ctrl = activeController
        val targetPkg = AppPreferences.getLaunchTargetPackage(this)

        if (ctrl != null && (targetPkg == null || ctrl.packageName == targetPkg || ctrl.playbackState?.state == PlaybackState.STATE_PLAYING)) {
            if (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING) {
                ctrl.transportControls?.pause()
            } else {
                ctrl.transportControls?.play()
            }
        } else {
            launchOrResumePreferredApp(this)
        }
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
        isServiceConnected.value = true
        forceRepublishPill()
    }

    fun forceRepublishPill() {
        lastRenderKey = ""
        notifier.resetCache()
        refreshActiveSessions()
        val ctrl = activeController
        if (ctrl != null) {
            handleMetadata(ctrl.metadata)
            syncPlaybackClock(ctrl.playbackState, forceSync = true)
            val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) startTicker()
            syncLyrics(isPlaying = playing, forceImmediate = true)
        } else if (currentTitle.isNotBlank()) {
            val playing = LyricsPlaybackStore.isPlaying.value
            syncLyrics(isPlaying = playing, forceImmediate = true)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected.value = false
        isSessionsRegistered = false
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        forceHardRebind(applicationContext)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isNotificationAccessGranted(this)) {
            try {
                requestRebind(ComponentName(this, BitChordListenerService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    fun refreshActiveSessions() {
        val component = ComponentName(this, BitChordListenerService::class.java)
        try {
            if (!isSessionsRegistered) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
                isSessionsRegistered = true
            }
            attachToPreferredSession(sessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {
        }
    }

    private fun attachToPreferredSession(controllers: List<MediaController>?) {
        val candidates = controllers?.filter { it.packageName != packageName }.orEmpty()
        val preferred = AppPreferences.preferredPlayer.value
        val lastPkg = AppPreferences.lastActiveMusicPackage.value

        val playingCandidates = candidates.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }

        val target = if (preferred == PreferredPlayer.AUTO) {
            playingCandidates.firstOrNull { it.packageName == lastPkg }
                ?: playingCandidates.firstOrNull()
                ?: candidates.firstOrNull { lastPkg.isNotBlank() && it.packageName == lastPkg }
                ?: if (lastPkg.isBlank()) candidates.firstOrNull() else null
        } else {
            val matching = candidates.filter { preferred.matches(it.packageName) }
            matching.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: matching.firstOrNull()
                ?: playingCandidates.firstOrNull()
        }

        if (target != null && target.playbackState?.state == PlaybackState.STATE_PLAYING) {
            AppPreferences.rememberLastActivePackage(this, target.packageName)
        }

        LyricsPlaybackStore.activePlayerPackage.value = target?.packageName.orEmpty()

        if (target?.sessionToken == activeController?.sessionToken) {
            if (target != null) {
                handleMetadata(target.metadata)
                syncPlaybackClock(target.playbackState, forceSync = false)
                val playing = target.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) {
                    startTicker()
                    if (!isForegroundStarted) {
                        lastRenderKey = ""
                        notifier.resetCache()
                    }
                    syncLyrics(isPlaying = true, forceImmediate = !isForegroundStarted)
                }
            }
            return
        }

        activeController?.unregisterCallback(mediaCallback)
        activeController = target
        target?.registerCallback(mediaCallback)

        if (target != null) {
            lastRenderKey = ""
            lastLineIndex = -1
            handleMetadata(target.metadata)
            syncPlaybackClock(target.playbackState, forceSync = true)
            val playing = target.playbackState?.state == PlaybackState.STATE_PLAYING
            wearBridge.publishPlayback(
                trackKey = currentTrackKey,
                isPlaying = playing,
                positionMs = LyricsPlaybackStore.currentPositionMs(),
                speed = LyricsPlaybackStore.speed.value,
                force = true
            )
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
            wearBridge.clearTrack()
            hidePhoneNotificationOnly()
        }
    }

    private fun rawExtrapolatedPositionMs(now: Long): Long {
        val basePos = LyricsPlaybackStore.basePositionMs.value
        if (!LyricsPlaybackStore.isPlaying.value) return basePos
        val elapsed = (now - LyricsPlaybackStore.baseRealtimeMs.value).coerceAtLeast(0L)
        return basePos + (elapsed * LyricsPlaybackStore.speed.value).toLong()
    }

    private fun syncPlaybackClock(state: PlaybackState?, forceSync: Boolean): Boolean {
        state ?: return false
        val now = SystemClock.elapsedRealtime()
        val wasPlaying = LyricsPlaybackStore.isPlaying.value
        val playing = state.state == PlaybackState.STATE_PLAYING
        val incomingSpeed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
        val incomingRealtime = if (state.lastPositionUpdateTime in 1L..now) {
            state.lastPositionUpdateTime
        } else {
            now
        }
        val incomingPosNow = if (playing) {
            state.position + ((now - incomingRealtime) * incomingSpeed).toLong()
        } else {
            state.position
        }.coerceAtLeast(0L)

        val inUserSeekGrace = now <= userSeekGraceUntilMs

        if (!forceSync && !inUserSeekGrace && wasPlaying && playing) {
            val currentRawNow = rawExtrapolatedPositionMs(now)
            val delta = incomingPosNow - currentRawNow
            if (delta in -BACKWARD_JITTER_GUARD_MS..FORWARD_JITTER_GUARD_MS) {
                if (LyricsPlaybackStore.speed.value != incomingSpeed) {
                    LyricsPlaybackStore.basePositionMs.value = currentRawNow
                    LyricsPlaybackStore.baseRealtimeMs.value = now
                    LyricsPlaybackStore.speed.value = incomingSpeed
                    return true
                }
                return false
            }
            if (delta < -BACKWARD_JITTER_GUARD_MS) {
                lastLineIndex = -1
            }
        }

        LyricsPlaybackStore.isPlaying.value = playing
        LyricsPlaybackStore.basePositionMs.value = incomingPosNow
        LyricsPlaybackStore.baseRealtimeMs.value = now
        LyricsPlaybackStore.speed.value = incomingSpeed
        return true
    }

    private fun quickCoverSignature(bmp: Bitmap): Long {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return 0L
        val w = bmp.width
        val h = bmp.height
        var hash = (w.toLong() shl 32) xor h.toLong()
        val pts = arrayOf(
            w / 2 to h / 2,
            w / 4 to h / 4,
            (w * 3) / 4 to h / 4,
            w / 4 to (h * 3) / 4,
            (w * 3) / 4 to (h * 3) / 4
        )
        for ((x, y) in pts) {
            hash = hash * 31L + bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)).toLong()
        }
        return hash
    }

    private fun pushTrackToWear() {
        if (currentTitle.isBlank()) return
        wearBridge.publishTrack(
            trackKey = currentTrackKey,
            title = currentTitle,
            artist = currentArtist,
            durationMs = currentDurationMs,
            lines = currentLines,
            cover = currentCover,
            palette = currentPalette
        )
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().trim()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (title.isBlank()) return
        val key = "$title|$artist"
        val isNewTrack = key != currentTrackKey

        currentTitle = title
        currentArtist = artist
        if (durationMs > 0L) {
            currentDurationMs = durationMs
            LyricsPlaybackStore.durationMs.value = durationMs
        }

        LyricsPlaybackStore.title.value = title
        LyricsPlaybackStore.artist.value = artist

        val cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (cover != null && !cover.isRecycled) {
            val sig = quickCoverSignature(cover)
            if (isNewTrack || sig != currentCoverSig || currentCover == null) {
                currentCover = cover
                currentCoverSig = sig
                LyricsPlaybackStore.cover.value = cover
                paletteJob?.cancel()
                paletteJob = scope.launch {
                    val extracted = withContext(Dispatchers.Default) {
                        LyricsRepository.extractPalette(cover)
                    }
                    currentPalette = extracted
                    LyricsPlaybackStore.palette.value = extracted
                    pushTrackToWear()
                    lastRenderKey = ""
                    val stillPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                    syncLyrics(isPlaying = stillPlaying, forceImmediate = true)
                }
            }
        }

        if (isNewTrack || (currentLines.isEmpty() && fetchJob?.isActive != true)) {
            currentTrackKey = key
            currentLines = emptyList()
            lastLineIndex = -1
            LyricsPlaybackStore.lines.value = emptyList()
            LyricsPlaybackStore.syncOffsetMs.value = AppPreferences.defaultSyncOffsetMs.value
            lastRenderKey = ""
            if (isNewTrack) {
                syncPlaybackClock(activeController?.playbackState, forceSync = true)
            }
            pushTrackToWear()

            fetchJob?.cancel()
            fetchJob = scope.launch {
                val loaded = LyricsRepository.fetchSyncedLyrics(
                    currentTitle,
                    currentArtist,
                    currentDurationMs / 1000L
                )
                currentLines = loaded
                lastLineIndex = -1
                LyricsPlaybackStore.lines.value = loaded
                pushTrackToWear()
                lastRenderKey = ""
                syncPlaybackClock(activeController?.playbackState, forceSync = true)
                val stillPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                wearBridge.publishPlayback(
                    trackKey = currentTrackKey,
                    isPlaying = stillPlaying,
                    positionMs = LyricsPlaybackStore.currentPositionMs(),
                    speed = LyricsPlaybackStore.speed.value,
                    force = true
                )
                syncLyrics(isPlaying = stillPlaying, forceImmediate = true)
                if (stillPlaying) startTicker()
            }
        }
    }

    private fun startTicker() {
        isTicking = true
        scheduleNextTick()
    }

    private fun stopTicker() {
        isTicking = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun scheduleNextTick() {
        handler.removeCallbacks(tickRunnable)
        if (!isTicking) return
        val posMs = LyricsPlaybackStore.currentPositionMs()
        val nextIdx = findLineIndex(currentLines, posMs) + 1
        val delayMs = if (nextIdx in currentLines.indices) {
            val speed = LyricsPlaybackStore.speed.value.coerceAtLeast(0.25f)
            val remainingMs = ((currentLines[nextIdx].startMs - posMs) / speed).toLong()
            remainingMs.coerceIn(MIN_TICK_DELAY_MS, MAX_TICK_DELAY_MS)
        } else {
            MAX_TICK_DELAY_MS
        }
        handler.postDelayed(tickRunnable, delayMs)
    }

    private fun findLineIndex(lines: List<LyricLine>, posMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= posMs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }

    private fun syncLyrics(isPlaying: Boolean, forceImmediate: Boolean) {
        if (currentTitle.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        val posMs = LyricsPlaybackStore.currentPositionMs()
        var idx = if (currentLines.isEmpty()) -1 else findLineIndex(currentLines, posMs)

        if (
            isPlaying &&
            now > userSeekGraceUntilMs &&
            lastLineIndex in currentLines.indices &&
            idx < lastLineIndex
        ) {
            val lagBelowCurrentLine = currentLines[lastLineIndex].startMs - posMs
            if (lagBelowCurrentLine in 0L..LINE_HYSTERESIS_MS) {
                idx = lastLineIndex
            }
        }

        val currLine = if (idx in currentLines.indices) currentLines[idx] else null

        val renderKey = "$currentTrackKey|$idx|$isPlaying"
        if (renderKey == lastRenderKey) return

        if (!forceImmediate && isForegroundStarted && now - lastNotifyRealtimeMs < MIN_LINE_INTERVAL_MS) {
            return
        }

        lastLineIndex = idx
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
                notifier.markForegroundPosted(
                    title = currentTitle,
                    prevLyric = prev1,
                    currentLine = currLine,
                    nextLyric1 = next1,
                    cover = currentCover,
                    palette = currentPalette,
                    isPlaying = isPlaying
                )
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
        isServiceConnected.value = false
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
        wearBridge.release()
        AutoLyricsBridge.clear()
        hidePhoneNotificationOnly()
        scope.cancel()
        super.onDestroy()
    }
}
