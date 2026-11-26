package com.bitchord.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

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
    private const val PATH_TRACK_DATA_MSG = "/bitchord/track_data_msg"
    private const val PATH_PLAYBACK_STATE = "/bitchord/playback_state"
    private const val PATH_CMD_PLAY_PAUSE = "/bitchord/cmd/play_pause"
    private const val PATH_CMD_NEXT = "/bitchord/cmd/next"
    private const val PATH_CMD_PREV = "/bitchord/cmd/prev"
    private const val PATH_CMD_SEEK = "/bitchord/cmd/seek"
    private const val PATH_CMD_REQUEST_SYNC = "/bitchord/cmd/request_sync"
    private const val PATH_CMD_WATCH_INACTIVE = "/bitchord/cmd/watch_inactive"

    private const val BT_ESTIMATED_LATENCY_MS = 45L
    private const val BACKWARD_JITTER_GUARD_MS = 1400L
    private const val FORWARD_JITTER_GUARD_MS = 220L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val track = MutableStateFlow(WearTrackState())
    val isPlaying = MutableStateFlow(false)
    val basePositionMs = MutableStateFlow(0L)
    val baseRealtimeMs = MutableStateFlow(0L)
    val speed = MutableStateFlow(1f)
    val phoneConnected = MutableStateFlow(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Volatile private var localSeekLockUntilMs = 0L
    @Volatile private var localSeekTargetMs = 0L

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
        val target = positionMs.coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        localSeekTargetMs = target
        localSeekLockUntilMs = now + 1000L
        basePositionMs.value = target
        baseRealtimeMs.value = now
        sendCommand(PATH_CMD_SEEK, target.toString().toByteArray(Charsets.UTF_8))
    }

    fun requestSyncFromPhone() {
        sendCommand(PATH_CMD_REQUEST_SYNC, ByteArray(0))
    }

    fun notifyWatchInactive() {
    sendCommand(PATH_CMD_WATCH_INACTIVE, ByteArray(0))
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
                val payloads = mutableListOf<Pair<String, ByteArray?>>()
                try {
                    for (item in buffer) {
                        if (item.uri.path == PATH_TRACK_DATA) {
                            val dataMap = DataMapItem.fromDataItem(item).dataMap
                            payloads.add(
                                dataMap.getString("payload").orEmpty() to dataMap.getByteArray("art")
                            )
                        }
                    }
                } finally {
                    buffer.release()
                }
                payloads.lastOrNull()?.let { (payload, art) ->
                    parseTrackDataAsync(payload, art)
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        var latest: Pair<String, ByteArray?>? = null
        try {
            for (event in events) {
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_TRACK_DATA) {
                    phoneConnected.value = true
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    latest = dataMap.getString("payload").orEmpty() to dataMap.getByteArray("art")
                }
            }
        } finally {
            events.release()
        }
        latest?.let { (payload, art) -> parseTrackDataAsync(payload, art) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        phoneConnected.value = true
        when (event.path) {
            PATH_TRACK_DATA_MSG -> {
                parseTrackDataAsync(
                    payload = String(event.data, Charsets.UTF_8),
                    artBytes = null
                )
            }
            PATH_PLAYBACK_STATE -> {
                try {
                    val json = JSONObject(String(event.data, Charsets.UTF_8))
                    val incomingKey = json.optString("trackKey", "")
                    val playing = json.optBoolean("isPlaying", false)
                    val rawPosMs = json.optLong("positionMs", 0L).coerceAtLeast(0L)
                    val spd = json.optDouble("speed", 1.0).toFloat().let { if (it > 0f) it else 1f }
                    val adjustedPosMs = if (playing) rawPosMs + BT_ESTIMATED_LATENCY_MS else rawPosMs

                    val now = SystemClock.elapsedRealtime()
                    val sameTrack = incomingKey.isNotEmpty() && incomingKey == track.value.trackKey

                    // Ignora vecchi pacchetti "in volo" subito dopo un seek dall'orologio
                    if (now < localSeekLockUntilMs && sameTrack && abs(adjustedPosMs - localSeekTargetMs) > 1500L) {
                        return
                    }

                    // Anti-jitter durante la riproduzione continua
                    if (sameTrack && isPlaying.value && playing) {
                        val delta = adjustedPosMs - currentPositionMs()
                        if (delta in -BACKWARD_JITTER_GUARD_MS..FORWARD_JITTER_GUARD_MS) {
                            speed.value = spd
                            return
                        }
                    }

                    isPlaying.value = playing
                    speed.value = spd
                    basePositionMs.value = adjustedPosMs
                    baseRealtimeMs.value = now
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun parseTrackDataAsync(payload: String, artBytes: ByteArray?) {
        if (payload.isBlank()) return
        scope.launch {
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

                val resolvedBytes = if (artBytes != null && artBytes.isNotEmpty()) {
                    artBytes
                } else {
                    val b64 = json.optString("artB64", "")
                    if (b64.isNotEmpty()) Base64.decode(b64, Base64.DEFAULT) else ByteArray(0)
                }

                val coverBmp = if (resolvedBytes.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(resolvedBytes, 0, resolvedBytes.size)
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
}
