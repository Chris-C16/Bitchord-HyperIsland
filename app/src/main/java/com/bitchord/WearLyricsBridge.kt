package com.bitchord.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.min

class WearLyricsBridge(context: Context) : MessageClient.OnMessageReceivedListener {

    companion object {
        const val PATH_TRACK_DATA = "/bitchord/track_data"
        const val PATH_PLAYBACK_STATE = "/bitchord/playback_state"
        const val PATH_CMD_PLAY_PAUSE = "/bitchord/cmd/play_pause"
        const val PATH_CMD_NEXT = "/bitchord/cmd/next"
        const val PATH_CMD_PREV = "/bitchord/cmd/prev"
        const val PATH_CMD_SEEK = "/bitchord/cmd/seek"
        const val PATH_CMD_REQUEST_SYNC = "/bitchord/cmd/request_sync"
    }

    private val appContext = context.applicationContext
    private val dataClient: DataClient = Wearable.getDataClient(appContext)
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)

    private var lastTrackKey: String = ""
    private var lastTrackJson: String = ""
    private var lastCoverRef: Bitmap? = null
    private var cachedArtBytes: ByteArray = ByteArray(0)
    private var lastPlaybackKey: String = ""

    init {
        try {
            messageClient.addListener(this)
        } catch (_: Throwable) {
        }
    }

    fun release() {
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
        if (title.isBlank()) return

        val linesArray = JSONArray()
        for (line in lines) {
            linesArray.put(
                JSONObject().apply {
                    put("t", line.startMs)
                    put("s", line.fullText)
                }
            )
        }

        val payload = JSONObject().apply {
            put("trackKey", trackKey)
            put("title", title)
            put("artist", artist)
            put("durationMs", durationMs)
            put("accent", palette.accent.toArgb())
            put("bgTop", palette.top.toArgb())
            put("bgBottom", palette.bottom.toArgb())
            put("lines", linesArray)
        }.toString()

        val artBytes = getCompressedCoverBytes(cover)
        if (trackKey == lastTrackKey && payload == lastTrackJson && artBytes.contentEquals(cachedArtBytes)) {
            return
        }

        lastTrackKey = trackKey
        lastTrackJson = payload

        try {
            val req = PutDataMapRequest.create(PATH_TRACK_DATA).apply {
                dataMap.putString("payload", payload)
                dataMap.putByteArray("art", artBytes)
                dataMap.putLong("ts", SystemClock.elapsedRealtime())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(req)
        } catch (_: Throwable) {
        }
    }

    fun publishPlayback(
        trackKey: String,
        isPlaying: Boolean,
        positionMs: Long,
        speed: Float
    ) {
        val stateKey = "$trackKey|$isPlaying|${positionMs / 250L}|$speed"
        if (stateKey == lastPlaybackKey) return
        lastPlaybackKey = stateKey

        val bytes = JSONObject().apply {
            put("trackKey", trackKey)
            put("isPlaying", isPlaying)
            put("positionMs", positionMs.coerceAtLeast(0L))
            put("speed", if (speed > 0f) speed.toDouble() else 1.0)
            put("sentAtWallMs", System.currentTimeMillis())
        }.toString().toByteArray(Charsets.UTF_8)

        sendMessageToAllNodes(PATH_PLAYBACK_STATE, bytes)
    }

    fun clearTrack() {
        lastTrackKey = ""
        lastTrackJson = ""
        lastPlaybackKey = ""
        publishPlayback(trackKey = "", isPlaying = false, positionMs = 0L, speed = 1f)
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

        val size = 96
        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val minDim = min(cover.width, cover.height)
        val left = (cover.width - minDim) / 2
        val top = (cover.height - minDim) / 2
        val src = Rect(left, top, left + minDim, top + minDim)
        val dst = Rect(0, 0, size, size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(cover, src, dst, paint)

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 76, out)
        lastCoverRef = cover
        cachedArtBytes = out.toByteArray()
        return cachedArtBytes
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_CMD_PLAY_PAUSE -> LyricsPlaybackStore.onPlayPause?.invoke()
            PATH_CMD_NEXT -> LyricsPlaybackStore.onNext?.invoke()
            PATH_CMD_PREV -> LyricsPlaybackStore.onPrevious?.invoke()
            PATH_CMD_SEEK -> {
                val posMs = String(event.data, Charsets.UTF_8).toLongOrNull() ?: return
                LyricsPlaybackStore.onSeekTo?.invoke(posMs.coerceAtLeast(0L))
            }
            PATH_CMD_REQUEST_SYNC -> {
                lastPlaybackKey = ""
                if (lastTrackJson.isNotEmpty()) {
                    try {
                        val req = PutDataMapRequest.create(PATH_TRACK_DATA).apply {
                            dataMap.putString("payload", lastTrackJson)
                            dataMap.putByteArray("art", cachedArtBytes)
                            dataMap.putLong("ts", SystemClock.elapsedRealtime())
                        }.asPutDataRequest().setUrgent()
                        dataClient.putDataItem(req)
                    } catch (_: Throwable) {
                    }
                }
                publishPlayback(
                    trackKey = lastTrackKey,
                    isPlaying = LyricsPlaybackStore.isPlaying.value,
                    positionMs = LyricsPlaybackStore.currentPositionMs(),
                    speed = LyricsPlaybackStore.speed.value
                )
            }
        }
    }
}
