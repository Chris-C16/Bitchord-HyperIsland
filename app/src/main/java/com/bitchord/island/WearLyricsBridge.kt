package com.bitchord.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.min

class WearLyricsBridge(context: Context) : MessageClient.OnMessageReceivedListener {

    companion object {
        const val PATH_TRACK_DATA = "/bitchord/track_data"
        const val PATH_TRACK_DATA_MSG = "/bitchord/track_data_msg"
        const val PATH_PLAYBACK_STATE = "/bitchord/playback_state"
        const val PATH_CMD_PLAY_PAUSE = "/bitchord/cmd/play_pause"
        const val PATH_CMD_NEXT = "/bitchord/cmd/next"
        const val PATH_CMD_PREV = "/bitchord/cmd/prev"
        const val PATH_CMD_SEEK = "/bitchord/cmd/seek"
        const val PATH_CMD_REQUEST_SYNC = "/bitchord/cmd/request_sync"
        const val PATH_CMD_WATCH_INACTIVE = "/bitchord/cmd/watch_inactive"

        private const val WATCH_ACTIVE_TTL_MS = 4 * 60_000L
    }

    private val appContext = context.applicationContext
    private val dataClient: DataClient = Wearable.getDataClient(appContext)
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var publishTrackJob: Job? = null

    @Volatile private var watchActiveUntilMs: Long = 0L
    @Volatile private var lastTrackKey: String = ""
    @Volatile private var lastTrackJson: String = ""
    @Volatile private var lastCoverRef: Bitmap? = null
    @Volatile private var cachedArtBytes: ByteArray = ByteArray(0)
    @Volatile private var lastPlaybackKey: String = ""

    init {
        try {
            messageClient.addListener(this)
        } catch (_: Throwable) {
        }
    }

    private fun isWatchActive(): Boolean {
        return SystemClock.elapsedRealtime() < watchActiveUntilMs
    }

    fun release() {
        publishTrackJob?.cancel()
        scope.cancel()
        try {
            messageClient.removeListener(this)
        } catch (_: Throwable) {
        }
    }

    fun publishTrack(
        trackKey: String,
        title: String,
        artist: String,
        durationMs: Long,
        lines: List<LyricLine>,
        cover: Bitmap?,
        palette: CoverPalette
    ) {
        if (!isWatchActive() || title.isBlank()) return
        val linesSnapshot = lines.toList()
        val accentArgb = palette.accent.toArgb()

        publishTrackJob?.cancel()
        publishTrackJob = scope.launch {
            if (!isWatchActive()) return@launch
            val linesArray = JSONArray()
            for (line in linesSnapshot) {
                linesArray.put(
                    JSONObject().apply {
                        put("t", line.startMs)
                        put("s", line.fullText)
                    }
                )
            }

            val bgTopArgb = Color.rgb(
                (Color.red(accentArgb) * 0.24f).toInt().coerceIn(18, 64),
                (Color.green(accentArgb) * 0.24f).toInt().coerceIn(18, 64),
                (Color.blue(accentArgb) * 0.28f).toInt().coerceIn(24, 76)
            )
            val bgBottomArgb = Color.rgb(10, 11, 16)

            val artBytes = getCompressedCoverBytes(cover)
            val artBase64 = if (artBytes.isNotEmpty()) {
                Base64.encodeToString(artBytes, Base64.NO_WRAP)
            } else {
                ""
            }

            val payload = JSONObject().apply {
                put("trackKey", trackKey)
                put("title", title)
                put("artist", artist)
                put("durationMs", durationMs)
                put("accent", accentArgb)
                put("bgTop", bgTopArgb)
                put("bgBottom", bgBottomArgb)
                put("lines", linesArray)
                put("artB64", artBase64)
            }.toString()

            if (trackKey == lastTrackKey && payload == lastTrackJson) {
                return@launch
            }

            lastTrackKey = trackKey
            lastTrackJson = payload

            dispatchTrackPayload(payload, artBytes)
        }
    }

    private fun dispatchTrackPayload(payload: String, artBytes: ByteArray) {
        if (!isWatchActive()) return
        try {
            val req = PutDataMapRequest.create(PATH_TRACK_DATA).apply {
                dataMap.putString("payload", payload)
                dataMap.putByteArray("art", artBytes)
                dataMap.putLong("ts", SystemClock.elapsedRealtime())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(req)
        } catch (_: Throwable) {
        }

        sendMessageToAllNodes(PATH_TRACK_DATA_MSG, payload.toByteArray(Charsets.UTF_8))
    }

    fun publishPlayback(
        trackKey: String,
        isPlaying: Boolean,
        positionMs: Long,
        speed: Float,
        force: Boolean = false
    ) {
        if (!isWatchActive()) return
        val stateKey = "$trackKey|$isPlaying|${positionMs / 400L}|$speed"
        if (!force && stateKey == lastPlaybackKey) return
        lastPlaybackKey = stateKey

        val bytes = JSONObject().apply {
            put("trackKey", trackKey)
            put("isPlaying", isPlaying)
            put("positionMs", positionMs.coerceAtLeast(0L))
            put("speed", if (speed > 0f) speed.toDouble() else 1.0)
        }.toString().toByteArray(Charsets.UTF_8)

        sendMessageToAllNodes(PATH_PLAYBACK_STATE, bytes)
    }

    fun clearTrack() {
        lastTrackKey = ""
        lastTrackJson = ""
        lastPlaybackKey = ""
        if (isWatchActive()) {
            publishPlayback(trackKey = "", isPlaying = false, positionMs = 0L, speed = 1f, force = true)
        }
    }

    private fun sendMessageToAllNodes(path: String, data: ByteArray) {
        try {
            Wearable.getNodeClient(appContext).connectedNodes.addOnSuccessListener { nodes ->
                for (node in nodes) {
                    messageClient.sendMessage(node.id, path, data)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun getCompressedCoverBytes(cover: Bitmap?): ByteArray {
        if (cover == null || cover.isRecycled || cover.width <= 0 || cover.height <= 0) {
            lastCoverRef = null
            cachedArtBytes = ByteArray(0)
            return cachedArtBytes
        }
        if (cover === lastCoverRef && cachedArtBytes.isNotEmpty()) {
            return cachedArtBytes
        }

        val size = 80
        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(scaled)
            val minDim = min(cover.width, cover.height)
            val left = (cover.width - minDim) / 2
            val top = (cover.height - minDim) / 2
            val src = Rect(left, top, left + minDim, top + minDim)
            val dst = Rect(0, 0, size, size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(cover, src, dst, paint)

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            val bytes = out.toByteArray()
            lastCoverRef = cover
            cachedArtBytes = bytes
            bytes
        } catch (_: Throwable) {
            ByteArray(0)
        } finally {
            scaled.recycle()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_CMD_WATCH_INACTIVE -> {
                watchActiveUntilMs = 0L
                publishTrackJob?.cancel()
            }
            PATH_CMD_PLAY_PAUSE -> {
                watchActiveUntilMs = SystemClock.elapsedRealtime() + WATCH_ACTIVE_TTL_MS
                mainHandler.post { LyricsPlaybackStore.onPlayPause?.invoke() }
            }
            PATH_CMD_NEXT -> {
                watchActiveUntilMs = SystemClock.elapsedRealtime() + WATCH_ACTIVE_TTL_MS
                mainHandler.post { LyricsPlaybackStore.onNext?.invoke() }
            }
            PATH_CMD_PREV -> {
                watchActiveUntilMs = SystemClock.elapsedRealtime() + WATCH_ACTIVE_TTL_MS
                mainHandler.post { LyricsPlaybackStore.onPrevious?.invoke() }
            }
            PATH_CMD_SEEK -> {
                watchActiveUntilMs = SystemClock.elapsedRealtime() + WATCH_ACTIVE_TTL_MS
                val posMs = String(event.data, Charsets.UTF_8).toLongOrNull() ?: return
                mainHandler.post { LyricsPlaybackStore.onSeekTo?.invoke(posMs.coerceAtLeast(0L)) }
            }
            PATH_CMD_REQUEST_SYNC -> {
                watchActiveUntilMs = SystemClock.elapsedRealtime() + WATCH_ACTIVE_TTL_MS
                lastPlaybackKey = ""
                mainHandler.post {
                    val title = LyricsPlaybackStore.title.value
                    val artist = LyricsPlaybackStore.artist.value
                    val currentKey = "$title|$artist"
                    if (title.isNotBlank()) {
                        if (currentKey == lastTrackKey && lastTrackJson.isNotEmpty()) {
                            dispatchTrackPayload(lastTrackJson, cachedArtBytes)
                        } else {
                            publishTrack(
                                trackKey = currentKey,
                                title = title,
                                artist = artist,
                                durationMs = LyricsPlaybackStore.durationMs.value,
                                lines = LyricsPlaybackStore.lines.value,
                                cover = LyricsPlaybackStore.cover.value,
                                palette = LyricsPlaybackStore.palette.value
                            )
                        }
                    }
                    publishPlayback(
                        trackKey = currentKey,
                        isPlaying = LyricsPlaybackStore.isPlaying.value,
                        positionMs = LyricsPlaybackStore.currentPositionMs(),
                        speed = LyricsPlaybackStore.speed.value,
                        force = true
                    )
                }
            }
        }
    }
}
