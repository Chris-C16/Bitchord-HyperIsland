package com.bitchord.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class WearLyricLine(
    val startMs: Long,
    val text: String
)

data class WearTrackState(
    val trackKey: String = "",
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0L,
    val accentColor: Int = 0xFFFFFFFF.toInt(),
    val bgTopColor: Int = 0xFF1F2233.toInt(),
    val bgBottomColor: Int = 0xFF0A0B10.toInt(),
    val lines: List<WearLyricLine> = emptyList(),
    val cover: Bitmap? = null
)

object WearSyncRepository : DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private const val PATH_TRACK_DATA = "/bitchord/track_data"
    private const val PATH_PLAYBACK_STATE = "/bitchord/playback_state"
    private const val PATH_CMD_PLAY_PAUSE = "/bitchord/cmd/play_pause"
    private const val PATH_CMD_NEXT = "/bitchord/cmd/next"
    private const val PATH_CMD_PREV = "/bitchord/cmd/prev"
    private const val PATH_CMD_SEEK = "/bitchord/cmd/seek"
    private const val PATH_CMD_REQUEST_SYNC = "/bitchord/cmd/request_sync"

    val track = MutableStateFlow(WearTrackState())
    val isPlaying = MutableStateFlow(false)
    val basePositionMs = MutableStateFlow(0L)
    val baseRealtimeMs = MutableStateFlow(0L)
    val speed = MutableStateFlow(1f)
    val phoneConnected = MutableStateFlow(false)

    private var appContext: Context? = null
    private var started = false

    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!started) {
            started = true
            try {
                Wearable.getDataClient(ctx).addListener(this)
                Wearable.getMessageClient(ctx).addListener(this)
            } catch (_: Throwable) {
            }
        }
        readCachedTrackData(ctx)
        requestSyncFromPhone()
    }

    fun currentPositionMs(): Long {
        if (!isPlaying.value) return basePositionMs.value
        val elapsed = (SystemClock.elapsedRealtime() - baseRealtimeMs.value).coerceAtLeast(0L)
        return basePositionMs.value + (elapsed * speed.value).toLong()
    }

    fun sendPlayPause() = sendCommand(PATH_CMD_PLAY_PAUSE, ByteArray(0))
    fun sendNext() = sendCommand(PATH_CMD_NEXT, ByteArray(0))
    fun sendPrev() = sendCommand(PATH_CMD_PREV, ByteArray(0))
    fun sendSeekTo(positionMs: Long) {
        basePositionMs.value = positionMs.coerceAtLeast(0L)
        baseRealtimeMs.value = SystemClock.elapsedRealtime()
        sendCommand(PATH_CMD_SEEK, positionMs.toString().toByteArray(Charsets.UTF_8))
    }

    fun requestSyncFromPhone() {
        sendCommand(PATH_CMD_REQUEST_SYNC, ByteArray(0))
    }

    private fun sendCommand(path: String, data: ByteArray) {
        val ctx = appContext ?: return
        try {
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
                phoneConnected.value = nodes.isNotEmpty()
                for (node in nodes) {
                    Wearable.getMessageClient(ctx).sendMessage(node.id, path, data)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun readCachedTrackData(ctx: Context) {
        try {
            Wearable.getDataClient(ctx).dataItems.addOnSuccessListener { buffer ->
                try {
                    for (item in buffer) {
                        if (item.uri.path == PATH_TRACK_DATA) {
                            val dataMap = DataMapItem.fromDataItem(item).dataMap
                            parseTrackData(
                                payload = dataMap.getString("payload").orEmpty(),
                                artBytes = dataMap.getByteArray("art")
                            )
                        }
                    }
                } finally {
                    buffer.release()
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        try {
            for (event in events) {
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_TRACK_DATA) {
                    phoneConnected.value = true
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    parseTrackData(
                        payload = dataMap.getString("payload").orEmpty(),
                        artBytes = dataMap.getByteArray("art")
                    )
                }
            }
        } finally {
            events.release()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_PLAYBACK_STATE) return
        phoneConnected.value = true
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val playing = json.optBoolean("isPlaying", false)
            val posMs = json.optLong("positionMs", 0L)
            val spd = json.optDouble("speed", 1.0).toFloat()
            val sentAtWall = json.optLong("sentAtWallMs", 0L)
            val transitDelayMs = if (sentAtWall > 0L) {
                (System.currentTimeMillis() - sentAtWall).coerceIn(0L, 1500L)
            } else {
                0L
            }

            isPlaying.value = playing
            speed.value = if (spd > 0f) spd else 1f
            basePositionMs.value = if (playing) posMs + transitDelayMs else posMs
            baseRealtimeMs.value = SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
        }
    }

    private fun parseTrackData(payload: String, artBytes: ByteArray?) {
        if (payload.isBlank()) return
        try {
            val json = JSONObject(payload)
            val linesArr = json.optJSONArray("lines")
            val parsedLines = ArrayList<WearLyricLine>(linesArr?.length() ?: 0)
            if (linesArr != null) {
                for (i in 0 until linesArr.length()) {
                    val obj = linesArr.optJSONObject(i) ?: continue
                    val startMs = obj.optLong("t", -1L)
                    val text = obj.optString("s", "").trim()
                    if (startMs >= 0L && text.isNotEmpty()) {
                        parsedLines.add(WearLyricLine(startMs = startMs, text = text))
                    }
                }
            }

            val coverBmp = if (artBytes != null && artBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            } else {
                null
            }

            track.value = WearTrackState(
                trackKey = json.optString("trackKey", ""),
                title = json.optString("title", ""),
                artist = json.optString("artist", ""),
                durationMs = json.optLong("durationMs", 0L),
                accentColor = json.optInt("accent", 0xFFFFFFFF.toInt()),
                bgTopColor = json.optInt("bgTop", 0xFF1F2233.toInt()),
                bgBottomColor = json.optInt("bgBottom", 0xFF0A0B10.toInt()),
                lines = parsedLines,
                cover = coverBmp
            )
        } catch (_: Throwable) {
        }
    }
}
