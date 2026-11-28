package com.bitchord.island

import android.app.Notification
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlin.math.ceil
import kotlin.math.min

class BitChordListenerService : NotificationListenerService() {

    companion object {
        private const val MIN_TICK_DELAY_MS = 40L
        private const val MIN_LINE_INTERVAL_MS = 90L
        private const val BACKWARD_JITTER_GUARD_MS = 1400L
        private const val FORWARD_JITTER_GUARD_MS = 220L
        private const val LINE_HYSTERESIS_MS = 1500L
        private const val HARD_REBIND_COOLDOWN_MS = 2500L

        private val REGEX_CODEC_TAG = Regex("""\b(flac|alac|wav|mqa|dsd\d*|opus|aac|mp3)\b""", RegexOption.IGNORE_CASE)
        private val REGEX_BIT_DEPTH = Regex("""\b(\d{2})\s*-?\s*bit\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE = Regex("""\b(\d{2,3}(?:\.\d)?)\s*k?hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE_RAW_HZ = Regex("""\b(384000|352800|192000|176400|96000|88200|48000|44100)\s*hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_HIRES_TAG = Regex("""\bhi[\s_-]?res\b""", RegexOption.IGNORE_CASE)
        private val REGEX_LOSSLESS_TAG = Regex("""\blossless\b""", RegexOption.IGNORE_CASE)

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

        fun onBitChordQualityDetected(context: Context, trackKey: String) {
            companionHandler.post {
                val inst = instance ?: return@post
                val activeKey = LyricsPlaybackStore.buildTrackQualityKey(inst.currentTitle, inst.currentArtist)
                if (activeKey == trackKey) {
                    val prevCodec = inst.currentCodecBadge
                    val prevSpec = inst.currentSpecBadge
                    inst.refreshSourceQuality(inst.activeController?.metadata, inst.activeController?.extras)
                    if (inst.currentCodecBadge != prevCodec || inst.currentSpecBadge != prevSpec) {
                        inst.lastRenderKey = ""
                        val playing = inst.activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                        inst.syncLyrics(isPlaying = playing, forceImmediate = true)
                    }
                }
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
    private lateinit var powerManager: PowerManager

    private var activeController: MediaController? = null
    private var fetchJob: Job? = null
    private var paletteJob: Job? = null
    private var isForegroundStarted = false
    private var isTicking = false
    private var isSessionsRegistered = false
    private var isScreenInteractive = true

    private var currentTrackKey = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentDurationMs = 0L
    private var currentCover: Bitmap? = null
    private var currentCoverSig = 0L
    private var currentPalette = CoverPalette()
    private var currentLines: List<LyricLine> = emptyList()
    private var currentPlayerPackage = ""
    private var currentNotificationCodec: String? = null
    private var currentNotificationRateKhz: String? = null
    private var currentNotificationBitDepth: String? = null
    private var currentNotificationHiRes = false
    private var currentCodecBadge = "Hi-Fi"
    private var currentSpecBadge = "Lossless"

    private var lastLineIndex = -1
    private var lastRenderKey = ""
    private var lastAutoStateKey = ""
    private var lastNotifyRealtimeMs = 0L
    private var userSeekGraceUntilMs = 0L
    private var ignoredProgrammaticSyncMs: Long? = null

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
            if (activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                scheduleNextTick()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    refreshActiveSessions()
                    val playing = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                    lastRenderKey = ""
                    syncLyrics(isPlaying = playing, forceImmediate = true)
                    if (playing) {
                        scheduleNextTick()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    if (!isCarModeActive()) {
                        handler.removeCallbacks(tickRunnable)
                    }
                }
            }
        }
    }

    private val tickRunnable = Runnable {
        if (!isTicking) return@Runnable
        syncLyrics(isPlaying = true, forceImmediate = false)
        scheduleNextTick()
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
            currentPlayerPackage = ""
            currentNotificationCodec = null
            currentNotificationRateKhz = null
            currentNotificationBitDepth = null
            currentNotificationHiRes = false
            currentCodecBadge = "Hi-Fi"
            currentSpecBadge = "Lossless"
            LyricsPlaybackStore.activePlayerPackage.value = ""
            LyricsPlaybackStore.audioCodecBadge.value = ""
            LyricsPlaybackStore.audioSpecBadge.value = ""
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
        powerManager = getSystemService(PowerManager::class.java)
        isScreenInteractive = try {
            powerManager.isInteractive
        } catch (_: Throwable) {
            true
        }

        val carFilter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        registerReceiver(carModeReceiver, carFilter, RECEIVER_NOT_EXPORTED)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)

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

        scope.launch {
            LyricsPlaybackStore.syncOffsetMs.drop(1).collect { newOffset ->
                if (ignoredProgrammaticSyncMs == newOffset) {
                    ignoredProgrammaticSyncMs = null
                    return@collect
                }
                ignoredProgrammaticSyncMs = null
                lastRenderKey = ""
                val playing = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                syncLyrics(isPlaying = playing, forceImmediate = true)
                if (playing) {
                    scheduleNextTick()
                }
            }
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

        val switchingSession = target?.sessionToken != activeController?.sessionToken
        if (switchingSession) {
            currentNotificationCodec = null
            currentNotificationRateKhz = null
            currentNotificationBitDepth = null
            currentNotificationHiRes = false
        }

        currentPlayerPackage = target?.packageName.orEmpty()
        LyricsPlaybackStore.activePlayerPackage.value = currentPlayerPackage
        if (target != null) {
            refreshSourceQuality(target.metadata, target.extras)
        }

        if (!switchingSession) {
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
            currentPlayerPackage = ""
            currentNotificationCodec = null
            currentNotificationRateKhz = null
            currentNotificationBitDepth = null
            currentNotificationHiRes = false
            currentCodecBadge = "Hi-Fi"
            currentSpecBadge = "Lossless"
            LyricsPlaybackStore.audioCodecBadge.value = ""
            LyricsPlaybackStore.audioSpecBadge.value = ""
            LyricsPlaybackStore.isPlaying.value = false
            AutoLyricsBridge.clear()
            wearBridge.clearTrack()
            hidePhoneNotificationOnly()
        }
    }

    private data class ParsedAudioInfo(
        val codec: String? = null,
        val rateKhzRaw: String? = null,
        val bitDepth: String? = null,
        val isHiRes: Boolean = false
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val pkg = currentPlayerPackage
        if (pkg.isBlank() || sbn.packageName != pkg) return

        val notifInfo = extractAudioInfoFromNotification(sbn)
        val changed = notifInfo.codec != currentNotificationCodec ||
            notifInfo.rateKhzRaw != currentNotificationRateKhz ||
            notifInfo.bitDepth != currentNotificationBitDepth ||
            notifInfo.isHiRes != currentNotificationHiRes
        if (changed) {
            currentNotificationCodec = notifInfo.codec
            currentNotificationRateKhz = notifInfo.rateKhzRaw
            currentNotificationBitDepth = notifInfo.bitDepth
            currentNotificationHiRes = notifInfo.isHiRes
            val prevCodec = currentCodecBadge
            val prevSpec = currentSpecBadge
            refreshSourceQuality(activeController?.metadata, activeController?.extras)
            if (currentCodecBadge != prevCodec || currentSpecBadge != prevSpec) {
                lastRenderKey = ""
                val playing = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                syncLyrics(isPlaying = playing, forceImmediate = true)
            }
        }
    }

    private fun normalizeRateKhz(numStr: String, hasKiloPrefix: Boolean): String? {
        if (!hasKiloPrefix) {
            return when (numStr) {
                "44100" -> "44.1"
                "48000" -> "48"
                "88200" -> "88.2"
                "96000" -> "96"
                "176400" -> "176.4"
                "192000" -> "192"
                "352800" -> "352.8"
                "384000" -> "384"
                else -> null
            }
        }
        val num = numStr.toFloatOrNull() ?: return null
        if (num !in 22f..768f) return null
        return when {
            numStr.startsWith("44.") -> "44.1"
            numStr.startsWith("88.") -> "88.2"
            numStr.startsWith("176.") -> "176.4"
            numStr.startsWith("352.") -> "352.8"
            else -> num.toInt().toString()
        }
    }

    private fun parseAudioInfoFromFields(candidates: List<String>): ParsedAudioInfo {
        var codec: String? = null
        var rateKhzRaw: String? = null
        var bitDepth: String? = null
        var isHiRes = false

        for (text in candidates) {
            if (text.isBlank()) continue
            if (codec == null) {
                REGEX_CODEC_TAG.find(text)?.let { m ->
                    codec = m.groupValues[1].uppercase().take(7)
                }
            }
            if (bitDepth == null) {
                REGEX_BIT_DEPTH.find(text)?.let { m ->
                    val bits = m.groupValues[1].toIntOrNull()
                    if (bits in setOf(16, 24, 32, 64)) {
                        bitDepth = m.groupValues[1]
                    }
                }
            }
            if (rateKhzRaw == null) {
                REGEX_SAMPLE_RATE_RAW_HZ.find(text)?.let { m ->
                    rateKhzRaw = normalizeRateKhz(m.groupValues[1], hasKiloPrefix = false)
                }
            }
            if (rateKhzRaw == null) {
                REGEX_SAMPLE_RATE.find(text)?.let { m ->
                    val matchedLower = m.value.lowercase()
                    if (matchedLower.contains('k')) {
                        rateKhzRaw = normalizeRateKhz(m.groupValues[1], hasKiloPrefix = true)
                    }
                }
            }
            if (!isHiRes && REGEX_HIRES_TAG.containsMatchIn(text)) {
                isHiRes = true
            }
        }

        if (codec == null) {
            for (text in candidates) {
                if (text.isNotBlank() && REGEX_LOSSLESS_TAG.containsMatchIn(text)) {
                    codec = "FLAC"
                    break
                }
            }
        }

        return ParsedAudioInfo(
            codec = codec,
            rateKhzRaw = rateKhzRaw,
            bitDepth = bitDepth,
            isHiRes = isHiRes
        )
    }

    private fun formatSpecBadge(rateKhzRaw: String?, bitDepth: String?): String? {
        val cleanRate = rateKhzRaw?.let { if (it.endsWith(".0")) it.removeSuffix(".0") else it }?.takeIf { it.isNotBlank() }
        val cleanBit = bitDepth?.takeIf { it.isNotBlank() }
        return when {
            cleanRate != null && cleanBit != null -> {
                val full = "${cleanRate}k ${cleanBit}Bit"
                if (full.length <= 10) {
                    full
                } else {
                    val intRate = cleanRate.substringBefore('.')
                    val compact = "${intRate}k ${cleanBit}Bit"
                    if (compact.length <= 10) compact else "${intRate}k ${cleanBit}b"
                }
            }
            cleanBit != null -> "${cleanBit}Bit"
            cleanRate != null -> {
                val solo = "${cleanRate}kHz"
                if (solo.length <= 10) solo else "${cleanRate.substringBefore('.')}kHz"
            }
            else -> null
        }
    }

    private fun collectBundleQualityStrings(bundle: Bundle?, out: MutableList<String>) {
        bundle ?: return
        try {
            for (key in bundle.keySet()) {
                val keyLower = key.lowercase()
                if (keyLower.contains("bitmap") || keyLower.contains("icon") || keyLower.contains("art")) continue
                val cs = try {
                    bundle.getCharSequence(key)
                } catch (_: Throwable) {
                    null
                }
                if (!cs.isNullOrBlank()) {
                    out += cs.toString()
                    continue
                }
                if (keyLower.contains("sample") || keyLower.contains("rate") || keyLower.contains("freq")) {
                    val v = try { bundle.getLong(key, -1L) } catch (_: Throwable) { -1L }
                    when (v) {
                        44100L -> out += "44.1kHz"
                        48000L -> out += "48kHz"
                        88200L -> out += "88.2kHz"
                        96000L -> out += "96kHz"
                        176400L -> out += "176.4kHz"
                        192000L -> out += "192kHz"
                        352800L -> out += "352.8kHz"
                        384000L -> out += "384kHz"
                    }
                } else if (keyLower.contains("bit") && keyLower.contains("depth")) {
                    val v = try { bundle.getLong(key, -1L) } catch (_: Throwable) { -1L }
                    if (v in listOf(16L, 24L, 32L)) {
                        out += "${v}-bit"
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun extractAudioInfoFromNotification(sbn: StatusBarNotification): ParsedAudioInfo {
        return try {
            val extras = sbn.notification?.extras ?: return ParsedAudioInfo()
            val fields = mutableListOf<String>()
            listOf(
                Notification.EXTRA_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT,
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TITLE_BIG
            ).forEach { extraKey ->
                extras.getCharSequence(extraKey)?.toString()?.takeIf { it.isNotBlank() }?.let {
                    fields += it
                }
            }
            collectBundleQualityStrings(extras, fields)
            parseAudioInfoFromFields(fields)
        } catch (_: Throwable) {
            ParsedAudioInfo()
        }
    }

    private fun refreshSourceQuality(metadata: MediaMetadata?, controllerExtras: Bundle? = activeController?.extras) {
        val pkgLower = currentPlayerPackage.lowercase()
        val isBitChord = pkgLower.contains("bitchord")
        val metadataFields = mutableListOf<String>()

        if (metadata != null) {
            try {
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf { it.isNotBlank() }?.let {
                    metadataFields += it
                }
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)?.takeIf { it.isNotBlank() }?.let {
                    metadataFields += it
                }
                for (key in metadata.keySet()) {
                    val keyLower = key.lowercase()
                    if (
                        keyLower.contains("bitmap") ||
                        keyLower.contains("art") ||
                        keyLower.contains("icon") ||
                        keyLower.contains("rating") ||
                        key == MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE ||
                        key == MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION
                    ) {
                        continue
                    }
                    val strVal = try {
                        metadata.getText(key)?.toString() ?: metadata.getString(key)
                    } catch (_: Throwable) {
                        null
                    }
                    if (!strVal.isNullOrBlank()) {
                        metadataFields += strVal
                        continue
                    }
                    if (keyLower.contains("sample") || keyLower.contains("rate") || keyLower.contains("freq")) {
                        val v = try { metadata.getLong(key) } catch (_: Throwable) { 0L }
                        when (v) {
                            44100L -> metadataFields += "44.1kHz"
                            48000L -> metadataFields += "48kHz"
                            88200L -> metadataFields += "88.2kHz"
                            96000L -> metadataFields += "96kHz"
                            176400L -> metadataFields += "176.4kHz"
                            192000L -> metadataFields += "192kHz"
                            352800L -> metadataFields += "352.8kHz"
                            384000L -> metadataFields += "384kHz"
                        }
                    } else if (keyLower.contains("bit") && keyLower.contains("depth")) {
                        val v = try { metadata.getLong(key) } catch (_: Throwable) { 0L }
                        if (v in listOf(16L, 24L, 32L)) {
                            metadataFields += "${v}-bit"
                        }
                    }
                }
                collectBundleQualityStrings(metadata.description?.extras, metadataFields)
            } catch (_: Throwable) {
            }
        }
        collectBundleQualityStrings(controllerExtras, metadataFields)

        if (
            currentNotificationCodec == null &&
            currentNotificationRateKhz == null &&
            currentNotificationBitDepth == null &&
            !currentNotificationHiRes &&
            currentPlayerPackage.isNotBlank()
        ) {
            val notifInfo = try {
                activeNotifications
                    ?.firstOrNull { it.packageName == currentPlayerPackage }
                    ?.let { extractAudioInfoFromNotification(it) }
            } catch (_: Throwable) {
                null
            }
            if (notifInfo != null) {
                currentNotificationCodec = notifInfo.codec
                currentNotificationRateKhz = notifInfo.rateKhzRaw
                currentNotificationBitDepth = notifInfo.bitDepth
                currentNotificationHiRes = notifInfo.isHiRes
            }
        }

        val parsedMeta = parseAudioInfoFromFields(metadataFields)
        val detectedCodec = parsedMeta.codec ?: currentNotificationCodec
        val detectedRate = parsedMeta.rateKhzRaw ?: currentNotificationRateKhz
        val detectedBit = parsedMeta.bitDepth ?: currentNotificationBitDepth
        val hasHiResTag = parsedMeta.isHiRes || currentNotificationHiRes

        val metadataSpec = formatSpecBadge(detectedRate, detectedBit)
        val trackQualityKey = LyricsPlaybackStore.buildTrackQualityKey(currentTitle, currentArtist)

        if (isBitChord) {
            if (metadataSpec != null) {
                val resolvedCodec = detectedCodec ?: "FLAC"
                currentCodecBadge = resolvedCodec
                currentSpecBadge = metadataSpec
                if (trackQualityKey.isNotBlank()) {
                    AppPreferences.putCachedBitChordQuality(this, trackQualityKey, resolvedCodec, metadataSpec)
                }
            } else {
                val cached = AppPreferences.getCachedBitChordQuality(this, trackQualityKey)
                if (cached != null) {
                    currentCodecBadge = detectedCodec ?: cached.first
                    currentSpecBadge = cached.second
                } else {
                    currentCodecBadge = detectedCodec ?: "FLAC"
                    currentSpecBadge = if (hasHiResTag) "Hi-Res" else "Lossless"
                }
            }
        } else {
            val (defaultCodec, defaultSpec) = when {
                pkgLower.contains("spotify") -> "Spotify" to "320k"
                pkgLower.contains("youtube.music") -> "YT Music" to "HQ"
                pkgLower.contains("apple.android.music") -> "ALAC" to "Lossless"
                pkgLower.contains("tidal") -> "TIDAL" to "Lossless"
                pkgLower.contains("amazon.mp3") -> "Amazon" to "HD"
                pkgLower.contains("deezer") -> "Deezer" to "Hi-Fi"
                pkgLower.contains("soundcloud") -> "SCloud" to "HQ"
                pkgLower.contains("qobuz") -> "Qobuz" to "Hi-Res"
                else -> "Hi-Fi" to "Lossless"
            }
            currentCodecBadge = detectedCodec ?: defaultCodec
            currentSpecBadge = metadataSpec ?: if (hasHiResTag) "Hi-Res" else defaultSpec
        }

        LyricsPlaybackStore.audioCodecBadge.value = currentCodecBadge
        LyricsPlaybackStore.audioSpecBadge.value = currentSpecBadge
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
        } else if (isNewTrack) {
            currentDurationMs = 0L
            LyricsPlaybackStore.durationMs.value = 0L
        }

        LyricsPlaybackStore.title.value = title
        LyricsPlaybackStore.artist.value = artist
        if (isNewTrack) {
            currentNotificationCodec = null
            currentNotificationRateKhz = null
            currentNotificationBitDepth = null
            currentNotificationHiRes = false
        }
        refreshSourceQuality(metadata, activeController?.extras)

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
            val defOffset = AppPreferences.defaultSyncOffsetMs.value
            if (LyricsPlaybackStore.syncOffsetMs.value != defOffset) {
                ignoredProgrammaticSyncMs = defOffset
                LyricsPlaybackStore.syncOffsetMs.value = defOffset
            } else {
                ignoredProgrammaticSyncMs = null
            }
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

    private fun deltaToNextRingStepMs(rawPosMs: Long, durMs: Long): Long {
        if (durMs <= 0L) return Long.MAX_VALUE
        val curStep = notifier.computeProgressStep(rawPosMs, durMs)
        if (curStep >= 10) return Long.MAX_VALUE
        val nextBoundaryMs = ceil(((curStep + 0.501) / 10.0) * durMs.toDouble()).toLong() + 30L
        return if (nextBoundaryMs > rawPosMs) (nextBoundaryMs - rawPosMs) else 40L
    }

    private fun scheduleNextTick() {
        handler.removeCallbacks(tickRunnable)
        if (!isTicking || !LyricsPlaybackStore.isPlaying.value) return
        val screenOn = try {
            powerManager.isInteractive
        } catch (_: Throwable) {
            isScreenInteractive
        }
        if (!screenOn && !isCarModeActive()) return

        val speed = LyricsPlaybackStore.speed.value.coerceAtLeast(0.25f)
        val lyricPosMs = LyricsPlaybackStore.currentPositionMs()
        val nextIdx = if (currentLines.isNotEmpty()) findLineIndex(currentLines, lyricPosMs) + 1 else -1
        val lyricDelayMs = if (nextIdx in currentLines.indices) {
            val delta = ((currentLines[nextIdx].startMs - lyricPosMs) / speed).toLong()
            delta.coerceAtLeast(MIN_TICK_DELAY_MS)
        } else {
            Long.MAX_VALUE
        }

        val rawPosMs = LyricsPlaybackStore.rawPositionMs().coerceAtLeast(0L)
        val durMs = currentDurationMs
        val cleanArtist = notifier.getCleanArtist(currentArtist)
        val cleanTitle = notifier.getCleanTitle(currentTitle)
        val artistPages = notifier.splitIntoWordPages(cleanArtist)
        val titlePages = notifier.splitIntoWordPages(cleanTitle)

        val wingDelayMs: Long = when {
            // Fase 1 (0s .. 5.5s): split per parole intere; eventuale cambio coppia a 2.5s solo se titolo > 20 car. e >= 3 pagine
            rawPosMs < 5_500L -> {
                val deltaPhaseEndMs = 5_500L - rawPosMs + 25L
                val deltaGiantFlipMs = if (cleanTitle.length > 20 && titlePages.size >= 3 && rawPosMs < 2_500L) {
                    2_500L - rawPosMs + 25L
                } else {
                    Long.MAX_VALUE
                }
                val rawWing = min(deltaPhaseEndMs, deltaGiantFlipMs)
                (rawWing / speed).toLong().coerceAtLeast(MIN_TICK_DELAY_MS)
            }
            // Fase 2 (5.5s .. 14s): scatto pagina ogni 2.0s SOLO se almeno un lato supera 1 pagina + step anello
            rawPosMs < 14_000L -> {
                val deltaPhaseEndMs = 14_000L - rawPosMs + 25L
                val needsPhase2Paging = artistPages.size > 1 || titlePages.size > 1
                val deltaPageMs = if (needsPhase2Paging) {
                    val inPhaseMs = (rawPosMs - 5_500L).coerceAtLeast(0L)
                    val nextPagePos = 5_500L + ((inPhaseMs / 2_000L) + 1L) * 2_000L
                    if (nextPagePos < 14_000L) (nextPagePos - rawPosMs + 25L) else Long.MAX_VALUE
                } else {
                    Long.MAX_VALUE
                }
                val deltaRingMs = deltaToNextRingStepMs(rawPosMs, durMs)
                val rawWing = minOf(deltaPhaseEndMs, deltaPageMs, deltaRingMs)
                (rawWing / speed).toLong().coerceAtLeast(MIN_TICK_DELAY_MS)
            }
            // Fase 3 (14s .. 19s): doppia ala qualità audio statica + step anello + transizione a Fase 4
            rawPosMs < 19_000L -> {
                val deltaPhaseEndMs = 19_000L - rawPosMs + 25L
                val deltaRingMs = deltaToNextRingStepMs(rawPosMs, durMs)
                val rawWing = min(deltaPhaseEndMs, deltaRingMs)
                (rawWing / speed).toLong().coerceAtLeast(MIN_TICK_DELAY_MS)
            }
            // Fase 4 (>= 19s): risveglio SOLO al cambio step 10% dell'anello (o nuova riga testi espansa)
            else -> {
                val deltaRingMs = deltaToNextRingStepMs(rawPosMs, durMs)
                if (deltaRingMs < Long.MAX_VALUE) {
                    (deltaRingMs / speed).toLong().coerceAtLeast(MIN_TICK_DELAY_MS)
                } else {
                    Long.MAX_VALUE
                }
            }
        }

        val nextDelayMs = min(lyricDelayMs, wingDelayMs)
        if (nextDelayMs < Long.MAX_VALUE) {
            handler.postDelayed(tickRunnable, nextDelayMs.coerceAtLeast(MIN_TICK_DELAY_MS))
        }
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
        val lyricPosMs = LyricsPlaybackStore.currentPositionMs()
        val rawPosMs = LyricsPlaybackStore.rawPositionMs().coerceAtLeast(0L)

        var idx = if (currentLines.isEmpty()) -1 else findLineIndex(currentLines, lyricPosMs)

        if (
            isPlaying &&
            now > userSeekGraceUntilMs &&
            lastLineIndex in currentLines.indices &&
            idx < lastLineIndex
        ) {
            val lagBelowCurrentLine = currentLines[lastLineIndex].startMs - lyricPosMs
            if (lagBelowCurrentLine in 0L..LINE_HYSTERESIS_MS) {
                idx = lastLineIndex
            }
        }

        val currLine = if (idx in currentLines.indices) currentLines[idx] else null
        val wingState = notifier.computeDualWingState(
            title = currentTitle,
            artist = currentArtist,
            isPlaying = isPlaying,
            posMs = rawPosMs,
            durationMs = currentDurationMs,
            audioCodecBadge = currentCodecBadge,
            audioSpecBadge = currentSpecBadge
        )
        val albumArtHash = (currentCoverSig xor (currentCoverSig ushr 32)).toInt()

        val renderKey = "$currentTrackKey|$idx|$isPlaying|" +
            "${wingState.leftText}|${wingState.leftIconKey}|" +
            "${wingState.rightText}|${wingState.rightIconKey}|" +
            "${wingState.progressStep}|$currentCodecBadge|$currentSpecBadge|$albumArtHash"
        if (!forceImmediate && renderKey == lastRenderKey) return

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

        val autoKey = "$currentTrackKey|$idx|$isPlaying"
        if (autoKey != lastAutoStateKey) {
            lastAutoStateKey = autoKey
            AutoLyricsBridge.publish(
                AutoLyricsState(
                    trackKey = currentTrackKey,
                    lineIndex = idx,
                    title = currentTitle,
                    artist = currentArtist,
                    durationMs = currentDurationMs,
                    positionMs = lyricPosMs,
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
        }

        if (isCarModeActive()) {
            hidePhoneNotificationOnly()
            return
        }

        val hasSyncedLyrics = currentLines.isNotEmpty()

        if (!isForegroundStarted) {
            val notif = notifier.buildNotification(
                title = currentTitle,
                artist = currentArtist,
                prevLyric = prev1,
                currentLine = currLine,
                nextLyric1 = next1,
                cover = currentCover,
                albumArtHash = albumArtHash,
                palette = currentPalette,
                isPlaying = isPlaying,
                posMs = rawPosMs,
                durationMs = currentDurationMs,
                audioCodecBadge = currentCodecBadge,
                audioSpecBadge = currentSpecBadge,
                hasSyncedLyrics = hasSyncedLyrics
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
                    artist = currentArtist,
                    prevLyric = prev1,
                    currentLine = currLine,
                    nextLyric1 = next1,
                    cover = currentCover,
                    albumArtHash = albumArtHash,
                    palette = currentPalette,
                    isPlaying = isPlaying,
                    posMs = rawPosMs,
                    durationMs = currentDurationMs,
                    audioCodecBadge = currentCodecBadge,
                    audioSpecBadge = currentSpecBadge,
                    hasSyncedLyrics = hasSyncedLyrics
                )
            } catch (_: Exception) {
                notifier.update(
                    title = currentTitle,
                    artist = currentArtist,
                    prevLyric = prev1,
                    currentLine = currLine,
                    nextLyric1 = next1,
                    cover = currentCover,
                    albumArtHash = albumArtHash,
                    palette = currentPalette,
                    isPlaying = isPlaying,
                    posMs = rawPosMs,
                    durationMs = currentDurationMs,
                    audioCodecBadge = currentCodecBadge,
                    audioSpecBadge = currentSpecBadge,
                    hasSyncedLyrics = hasSyncedLyrics
                )
            }
        } else {
            notifier.update(
                title = currentTitle,
                artist = currentArtist,
                prevLyric = prev1,
                currentLine = currLine,
                nextLyric1 = next1,
                cover = currentCover,
                albumArtHash = albumArtHash,
                palette = currentPalette,
                isPlaying = isPlaying,
                posMs = rawPosMs,
                durationMs = currentDurationMs,
                audioCodecBadge = currentCodecBadge,
                audioSpecBadge = currentSpecBadge,
                hasSyncedLyrics = hasSyncedLyrics
            )
        }
    }

    override fun onDestroy() {
        isServiceConnected.value = false
        if (instance === this) instance = null
        stopTicker()
        handler.removeCallbacksAndMessages(null)
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
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        wearBridge.release()
        AutoLyricsBridge.clear()
        hidePhoneNotificationOnly()
        scope.cancel()
        super.onDestroy()
    }
}
