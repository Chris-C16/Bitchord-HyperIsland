#!/usr/bin/env bash
set -e

mkdir -p .github/workflows app/src/main/res/drawable app/src/main/java/com/bitchord/island

cat << 'INNER' > .github/workflows/build.yml
name: Build Debug APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build Debug APK
        run: |
          if [ -f "./gradlew" ]; then
            chmod +x ./gradlew
            ./gradlew assembleDebug
          else
            gradle assembleDebug
          fi

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: BitChordIsland-APK-${{ github.run_number }}
          path: "**/build/outputs/apk/debug/*.apk"
          if-no-files-found: error
INNER

cat << 'INNER' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:label="Lyrics"
        android:icon="@drawable/ic_lyrics_note"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".LyricsPlayerActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:showWhenLocked="true"
            android:turnScreenOn="true" />

        <service
            android:name=".BitChordListenerService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
    </application>
</manifest>
INNER

cat << 'INNER' > app/src/main/res/drawable/ic_lyrics_note.xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z" />
</vector>
INNER

cat << 'INNER' > app/src/main/java/com/bitchord/island/LyricsPlaybackStore.kt
package com.bitchord.island

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

object LyricsPlaybackStore {
    val title = MutableStateFlow("")
    val artist = MutableStateFlow("")
    val cover = MutableStateFlow<Bitmap?>(null)
    val palette = MutableStateFlow(CoverPalette())
    val lines = MutableStateFlow<List<LyricLine>>(emptyList())
    val isPlaying = MutableStateFlow(false)

    val basePositionMs = MutableStateFlow(0L)
    val baseRealtimeMs = MutableStateFlow(0L)
    val speed = MutableStateFlow(1f)
    val durationMs = MutableStateFlow(0L)
    val syncOffsetMs = MutableStateFlow(0L)

    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    fun rawPositionMs(): Long {
        val base = basePositionMs.value
        if (!isPlaying.value) return base.coerceAtLeast(0L)
        val elapsed = (SystemClock.elapsedRealtime() - baseRealtimeMs.value).coerceAtLeast(0L)
        return (base + (elapsed * speed.value).toLong()).coerceAtLeast(0L)
    }

    fun currentPositionMs(): Long {
        return (rawPositionMs() + syncOffsetMs.value).coerceAtLeast(0L)
    }
}
INNER

cat << 'INNER' > app/src/main/java/com/bitchord/island/LyricsRepository.kt
package com.bitchord.island

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class LyricWord(val text: String, val startMs: Long, val endMs: Long)
data class LyricLine(val startMs: Long, val endMs: Long, val fullText: String, val words: List<LyricWord>)
data class CoverPalette(
    val primary: Color = Color(0xFF282038),
    val secondary: Color = Color(0xFF14111C),
    val accent: Color = Color(0xFF564787)
)

object LyricsRepository {
    private val client = OkHttpClient()
    private val lineRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
    private val wordTagRegex = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<]*)""")

    suspend fun fetchSyncedLyrics(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "").trim()
            val cleanArtist = artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim().orEmpty()

            val direct = queryGetEndpoint(title, artist, durationSec)
            if (direct.isNotEmpty()) return@withContext direct

            if (cleanTitle != title || cleanArtist != artist) {
                val cleaned = queryGetEndpoint(cleanTitle, cleanArtist, durationSec)
                if (cleaned.isNotEmpty()) return@withContext cleaned
            }

            querySearchEndpoint(cleanTitle, cleanArtist)
        }

    private fun queryGetEndpoint(title: String, artist: String, durationSec: Long): List<LyricLine> {
        return try {
            val qTitle = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$qTitle&artist_name=$qArtist" +
                    if (durationSec > 0) "&duration=$durationSec" else ""
            val req = Request.Builder().url(url).header("User-Agent", "BitChordIsland/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val synced = JSONObject(body).optString("syncedLyrics", "")
                if (synced.isBlank()) emptyList() else parseLrc(synced)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun querySearchEndpoint(title: String, artist: String): List<LyricLine> {
        return try {
            val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://lrclib.net/api/search?q=$q")
                .header("User-Agent", "BitChordIsland/2.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: return emptyList())
                for (i in 0 until arr.length()) {
                    val synced = arr.getJSONObject(i).optString("syncedLyrics", "")
                    if (synced.isNotBlank()) return parseLrc(synced)
                }
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseLrc(rawLrc: String): List<LyricLine> {
        val rawEntries = mutableListOf<Pair<Long, String>>()
        rawLrc.lines().forEach { line ->
            val match = lineRegex.find(line.trim()) ?: return@forEach
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msPart = match.groupValues[3]
            val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
            val content = match.groupValues[4].trim()
            if (content.isNotEmpty()) rawEntries.add((min * 60_000 + sec * 1_000 + ms) to content)
        }
        val sorted = rawEntries.sortedBy { it.first }
        return sorted.mapIndexed { idx, (startMs, content) ->
            val nextStart = sorted.getOrNull(idx + 1)?.first ?: (startMs + 4_500L)
            val endMs = nextStart.coerceAtLeast(startMs + 800L)
            val wordMatches = wordTagRegex.findAll(content).toList()
            if (wordMatches.isNotEmpty()) {
                val words = wordMatches.mapIndexed { wIdx, m ->
                    val wStart = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 +
                            (if (m.groupValues[3].length == 2) m.groupValues[3].toLong() * 10 else m.groupValues[3].toLong())
                    val wEnd = wordMatches.getOrNull(wIdx + 1)?.let { n ->
                        n.groupValues[1].toLong() * 60_000 + n.groupValues[2].toLong() * 1_000 +
                                (if (n.groupValues[3].length == 2) n.groupValues[3].toLong() * 10 else n.groupValues[3].toLong())
                    } ?: endMs
                    LyricWord(m.groupValues[4], wStart, wEnd.coerceAtLeast(wStart + 120L))
                }
                LyricLine(startMs, endMs, words.joinToString("") { it.text }.trim(), words)
            } else {
                val tokens = content.split(" ").filter { it.isNotEmpty() }
                val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
                var cursor = startMs
                val duration = (endMs - startMs).coerceAtLeast(500L)
                val words = tokens.mapIndexed { tIdx, token ->
                    val slice = (duration * token.length) / totalChars
                    val wStart = cursor
                    val wEnd = if (tIdx == tokens.lastIndex) endMs else (cursor + slice)
                    cursor = wEnd
                    LyricWord(if (tIdx == tokens.lastIndex) token else "$token ", wStart, wEnd)
                }
                LyricLine(startMs, endMs, content, words)
            }
        }
    }

    fun extractPalette(bitmap: Bitmap?): CoverPalette {
        if (bitmap == null) return CoverPalette()
        val p = Palette.from(bitmap).generate()
        return CoverPalette(
            primary = Color(p.getDarkVibrantColor(0xFF282038.toInt())),
            secondary = Color(p.getDarkMutedColor(0xFF14111C.toInt())),
            accent = Color(p.getVibrantColor(0xFF564787.toInt()))
        )
    }
}
INNER

cat << 'INNER' > app/src/main/java/com/bitchord/island/HyperIslandNotifier.kt
package com.bitchord.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb

class HyperIslandNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "bitchord_lyrics_live_pill"
        const val NOTIFICATION_ID = 3601
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HyperIsland Lyrics (Live)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Seconda pillola HyperIsland dedicata ai testi"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun createLyricsPendingIntent(): PendingIntent {
        val openLyricsIntent = Intent(context, LyricsPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            10,
            openLyricsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatMultiParagraphLyrics(
        currentLine: LyricLine?,
        nextLyric1: String,
        nextLyric2: String,
        title: String,
        artist: String
    ): Pair<String, String> {
        val active = currentLine?.fullText?.trim().orEmpty()
        if (active.isBlank()) {
            return title to "$artist\nIn attesa del testo sincronizzato..."
        }

        val p1 = nextLyric1.trim()
        val p2 = nextLyric2.trim()

        val bottomParagraphs = when {
            p1.isNotBlank() && p2.isNotBlank() -> "$p1\n$p2"
            p1.isNotBlank() -> "$p1\n♪ $title"
            else -> "$title — $artist"
        }

        return active to bottomParagraphs
    }

    private fun buildStaticMiniPillText(currentLine: LyricLine?, fallbackTitle: String): String {
        val full = currentLine?.fullText?.trim().orEmpty()
        val source = full.ifBlank { fallbackTitle.trim() }
        return trimWord(source, 15)
    }

    private fun trimWord(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val sub = text.substring(0, maxLen)
        val sp = sub.lastIndexOf(' ')
        return if (sp > maxLen / 2) sub.substring(0, sp) else sub
    }

    fun buildNotification(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        nextLyric2: String,
        palette: CoverPalette,
        songPositionMs: Long,
        isPlaying: Boolean
    ): Notification {
        val (topLine, bottomParagraphs) = formatMultiParagraphLyrics(
            currentLine, nextLyric1, nextLyric2, title, artist
        )
        val miniPillText = buildStaticMiniPillText(currentLine, title)

        val lineProgress = if (currentLine != null) {
            val dur = (currentLine.endMs - currentLine.startMs).coerceAtLeast(300L)
            (((songPositionMs - currentLine.startMs).toFloat() / dur) * 100f).toInt().coerceIn(0, 100)
        } else 0

        val accentColor = palette.accent.toArgb()
        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_note)
            .setContentTitle(topLine)
            .setContentText(bottomParagraphs)
            .setSubText("$title • $artist")
            .setColor(accentColor)
            .setColorized(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(createLyricsPendingIntent())
            .addExtras(extras)

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, miniPillText)

                val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()
                progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(progressStyle, lineProgress)
                progressStyleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
                    .invoke(progressStyle, true)

                Notification.Builder::class.java
                    .getMethod("setStyle", Notification.Style::class.java)
                    .invoke(builder, progressStyle)
            } catch (_: Throwable) {
                builder.setProgress(100, lineProgress, false)
            }
        } else {
            builder.setProgress(100, lineProgress, false)
        }

        return builder.build()
    }

    fun update(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        nextLyric2: String,
        palette: CoverPalette,
        songPositionMs: Long,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, artist, currentLine, nextLyric1, nextLyric2, palette, songPositionMs, isPlaying)
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)
}
INNER

cat << 'INNER' > app/src/main/java/com/bitchord/island/BitChordListenerService.kt
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
        val target = controllers?.firstOrNull {
            it.packageName.contains("bitchord", ignoreCase = true)
        }

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
            return
        }

        val posMs = LyricsPlaybackStore.currentPositionMs()
        val idx = if (currentLines.isEmpty()) -1 else currentLines.indexOfLast { posMs >= it.startMs }
        val currLine = if (idx >= 0) currentLines[idx] else null

        val step = if (currLine != null) {
            val dur = (currLine.endMs - currLine.startMs).coerceAtLeast(300L)
            (((posMs - currLine.startMs).toFloat() / dur) * 8f).toInt().coerceIn(0, 8)
        } else 0

        val renderKey = "$currentTrackKey|$idx|$step|$isPlaying"
        if (renderKey == lastRenderKey && isPlaying) return
        lastRenderKey = renderKey

        val next1 = if (idx >= 0 && idx + 1 < currentLines.size) currentLines[idx + 1].fullText else ""
        val next2 = if (idx >= 0 && idx + 2 < currentLines.size) currentLines[idx + 2].fullText else ""

        if (!isForegroundStarted) {
            val notif = notifier.buildNotification(
                currentTitle, currentArtist, currLine, next1, next2, currentPalette, posMs, isPlaying
            )
            try {
                startForeground(
                    HyperIslandNotifier.NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                isForegroundStarted = true
            } catch (_: Exception) {
                notifier.update(currentTitle, currentArtist, currLine, next1, next2, currentPalette, posMs, isPlaying)
            }
        } else {
            notifier.update(currentTitle, currentArtist, currLine, next1, next2, currentPalette, posMs, isPlaying)
        }
    }

    override fun onDestroy() {
        stopTicker()
        fetchJob?.cancel()
        activeController?.unregisterCallback(mediaCallback)
        unregisterReceiver(carModeReceiver)
        suspendForCarMode()
        scope.cancel()
        super.onDestroy()
    }
}
INNER

cat << 'INNER' > app/src/main/java/com/bitchord/island/LyricsPlayerActivity.kt
package com.bitchord.island

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class LyricsPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LyricsPlayerScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun LyricsPlayerScreen(onClose: () -> Unit) {
    val title by LyricsPlaybackStore.title.collectAsState()
    val artist by LyricsPlaybackStore.artist.collectAsState()
    val cover by LyricsPlaybackStore.cover.collectAsState()
    val palette by LyricsPlaybackStore.palette.collectAsState()
    val lines by LyricsPlaybackStore.lines.collectAsState()
    val isPlaying by LyricsPlaybackStore.isPlaying.collectAsState()
    val durationMs by LyricsPlaybackStore.durationMs.collectAsState()
    val syncOffsetMs by LyricsPlaybackStore.syncOffsetMs.collectAsState()

    var currentPosMs by remember { mutableLongStateOf(LyricsPlaybackStore.currentPositionMs()) }
    var rawPosMs by remember { mutableLongStateOf(LyricsPlaybackStore.rawPositionMs()) }

    LaunchedEffect(isPlaying, syncOffsetMs) {
        while (true) {
            currentPosMs = LyricsPlaybackStore.currentPositionMs()
            rawPosMs = LyricsPlaybackStore.rawPositionMs()
            delay(50L)
        }
    }

    val activeIndex = remember(lines, currentPosMs) {
        if (lines.isEmpty()) -1 else lines.indexOfLast { currentPosMs >= it.startMs }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(palette.primary, palette.secondary, Color(0xFF0A090F))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.ifBlank { "Nessun brano su BitChord" },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist.ifBlank { "Avvia una canzone su BitChord" },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onClose) {
                    Text("✕", color = Color.White, fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nessun testo sincronizzato trovato per questo brano.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    itemsIndexed(lines) { idx, line ->
                        val isActive = idx == activeIndex
                        val targetAlpha by animateFloatAsState(
                            targetValue = if (isActive) 1f else 0.35f,
                            label = "lineAlpha"
                        )
                        val annotated = buildAnnotatedString {
                            if (isActive && line.words.isNotEmpty()) {
                                line.words.forEach { w ->
                                    val spoken = currentPosMs >= w.startMs
                                    withStyle(
                                        SpanStyle(
                                            color = if (spoken) Color.White else Color.White.copy(alpha = 0.45f),
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    ) {
                                        append(w.text)
                                    }
                                }
                            } else {
                                append(line.fullText)
                            }
                        }

                        Text(
                            text = annotated,
                            color = Color.White,
                            fontSize = if (isActive) 25.sp else 20.sp,
                            lineHeight = if (isActive) 32.sp else 26.sp,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(targetAlpha)
                                .clickable {
                                    LyricsPlaybackStore.onSeekTo?.invoke((line.startMs - syncOffsetMs).coerceAtLeast(0L))
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val safeDuration = durationMs.coerceAtLeast(1L)
            val progress = (rawPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(rawPosMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                if (syncOffsetMs != 0L) {
                    val sign = if (syncOffsetMs > 0) "+" else ""
                    Text(
                        text = "Sync: $sign${syncOffsetMs / 1000f}s",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(formatTime(durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { LyricsPlaybackStore.syncOffsetMs.value -= 500L }) {
                    Text("-0.5s", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
                TextButton(onClick = { LyricsPlaybackStore.onPrevious?.invoke() }) {
                    Text("⏮", color = Color.White, fontSize = 24.sp)
                }
                Button(
                    onClick = { LyricsPlaybackStore.onPlayPause?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(if (isPlaying) "⏸" else "▶", color = Color.White, fontSize = 24.sp)
                }
                TextButton(onClick = { LyricsPlaybackStore.onNext?.invoke() }) {
                    Text("⏭", color = Color.White, fontSize = 24.sp)
                }
                TextButton(onClick = { LyricsPlaybackStore.syncOffsetMs.value += 500L }) {
                    Text("+0.5s", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
INNER

cat << 'INNER' > app/src/main/java/com/bitchord/island/MainActivity.kt
package com.bitchord.island

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SetupScreen(
                    onNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onLiveUpdates = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                        startActivity(intent)
                    },
                    onAppInfo = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    },
                    onOpenPlayer = {
                        startActivity(Intent(this, LyricsPlayerActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    onNotificationAccess: () -> Unit,
    onLiveUpdates: () -> Unit,
    onAppInfo: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0D14))
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "HyperIsland Lyrics",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Attiva i permessi qui sotto, poi avvia un brano su BitChord.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))

        val btnColors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282342))
        val shape = RoundedCornerShape(14.dp)

        Button(onClick = onNotificationAccess, modifier = Modifier.fillMaxWidth().height(54.dp), colors = btnColors, shape = shape) {
            Text("1. Accesso alle Notifiche (legge BitChord)", color = Color.White)
        }
        Button(onClick = onLiveUpdates, modifier = Modifier.fillMaxWidth().height(54.dp), colors = btnColors, shape = shape) {
            Text("2. Notifiche e Live Updates (Android 16)", color = Color.White)
        }
        Button(onClick = onAppInfo, modifier = Modifier.fillMaxWidth().height(54.dp), colors = btnColors, shape = shape) {
            Text("3. Info App (Impostazioni con restrizioni)", color = Color.White)
        }
        OutlinedButton(onClick = onOpenPlayer, modifier = Modifier.fillMaxWidth().height(54.dp), shape = shape) {
            Text("Apri Player Lyrics", color = Color.White)
        }
    }
}
INNER

git add .
git commit -m "Rewrite complete HyperIsland Live Update app (1A, 2B, 3A, 4A)"
git push origin main
