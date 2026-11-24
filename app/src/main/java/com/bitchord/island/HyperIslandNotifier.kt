package com.bitchord.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil

class HyperIslandNotifier(private val context: Context) {

    data class PillChunk(
        val index: Int,
        val text: String,
        val totalChunks: Int
    )

    companion object {
        const val CHANNEL_ID = "bitchord_lyrics_live_pill"
        const val NOTIFICATION_ID = 3601
        private const val MAX_PILL_VISUAL_WIDTH = 10.2f

        private val WAVE_FRAMES = arrayOf(
            floatArrayOf(0.32f, 0.88f, 0.52f, 0.96f),
            floatArrayOf(0.76f, 0.38f, 0.96f, 0.48f),
            floatArrayOf(0.96f, 0.64f, 0.34f, 0.84f),
            floatArrayOf(0.46f, 0.96f, 0.72f, 0.36f)
        )
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val playingSmallIcons: Array<Icon>
    private val pausedSmallIcon: Icon
    private val idleSmallIcon: Icon
    private val badgeCache = HashMap<String, Bitmap>()

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HyperIsland Lyrics (Live)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Pillola HyperIsland dedicata ai testi sincronizzati"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        playingSmallIcons = Array(WAVE_FRAMES.size) { frameIdx ->
            Icon.createWithBitmap(renderSmallWaveBitmap(WAVE_FRAMES[frameIdx]))
        }
        pausedSmallIcon = Icon.createWithBitmap(renderSmallPauseBitmap())
        idleSmallIcon = Icon.createWithBitmap(renderSmallIdleBitmap())
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

    fun computePillChunk(
        currentLine: LyricLine?,
        nextLineStartMs: Long?,
        songPositionMs: Long,
        fallbackTitle: String,
        isPlaying: Boolean
    ): PillChunk {
        val full = currentLine?.fullText?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (full.isBlank()) {
            val cleanTitle = fallbackTitle.replace(Regex("\\s+"), " ").trim()
            val idleText = if (!isPlaying) {
                "In pausa"
            } else {
                buildBalancedChunks(cleanTitle.ifEmpty { "♪" }).first()
            }
            return PillChunk(0, idleText, 1)
        }

        val chunks = buildBalancedChunks(full)
        if (chunks.size <= 1) {
            return PillChunk(0, chunks.first(), 1)
        }

        val explicitDuration = currentLine!!.endMs - currentLine.startMs
        val gapToNext = if (nextLineStartMs != null && nextLineStartMs > currentLine.startMs) {
            nextLineStartMs - currentLine.startMs
        } else {
            0L
        }
        val safeDuration = when {
            explicitDuration in 600L..15000L -> explicitDuration
            gapToNext in 600L..15000L -> gapToNext
            else -> (chunks.size * 1150L).coerceIn(1800L, 6500L)
        }

        val elapsed = (songPositionMs - currentLine.startMs).coerceAtLeast(0L)
        val ratio = (elapsed.toFloat() / safeDuration).coerceIn(0f, 0.999f)
        val chunkIdx = (ratio * chunks.size).toInt().coerceIn(0, chunks.lastIndex)
        return PillChunk(chunkIdx, chunks[chunkIdx], chunks.size)
    }

    private fun buildBalancedChunks(text: String): List<String> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return listOf("♪")
        if (visualWidth(cleaned) <= MAX_PILL_VISUAL_WIDTH) return listOf(cleaned)

        val rawWords = cleaned.split(" ").filter { it.isNotBlank() }
        val words = mutableListOf<String>()
        for (w in rawWords) {
            if (visualWidth(w) <= MAX_PILL_VISUAL_WIDTH) {
                words.add(w)
            } else {
                words.addAll(splitLongWord(w))
            }
        }

        val totalWidth = visualWidth(cleaned)
        val targetCount = ceil(totalWidth / MAX_PILL_VISUAL_WIDTH).toInt().coerceAtLeast(2)
        val softLimit = ((totalWidth / targetCount) * 1.15f).coerceIn(5.0f, MAX_PILL_VISUAL_WIDTH)

        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (w in words) {
            if (current.isEmpty()) {
                current.append(w)
            } else {
                val candidate = "$current $w"
                val candWidth = visualWidth(candidate)
                val isLastBucket = result.size >= targetCount - 1
                if (candWidth <= softLimit || (isLastBucket && candWidth <= MAX_PILL_VISUAL_WIDTH)) {
                    current.append(" ").append(w)
                } else {
                    result.add(current.toString())
                    current.clear()
                    current.append(w)
                }
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.ifEmpty { listOf("♪") }
    }

    private fun splitLongWord(word: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        val limit = MAX_PILL_VISUAL_WIDTH - 0.8f
        var widthAcc = 0f

        for (ch in word) {
            val cw = charVisualWidth(ch)
            if (sb.isNotEmpty() && widthAcc + cw > limit) {
                parts.add(sb.toString())
                sb.clear()
                widthAcc = 0f
            }
            sb.append(ch)
            widthAcc += cw
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }

    private fun visualWidth(text: String): Float {
        var total = 0f
        for (ch in text) total += charVisualWidth(ch)
        return total
    }

    private fun charVisualWidth(ch: Char): Float = when {
        ch == ' ' -> 0.48f
        ch in "ilI!|.,:;'`" -> 0.50f
        ch in "tfjr()-/" -> 0.68f
        ch in "mwMWOGQDHU" -> 1.32f
        ch.isUpperCase() -> 1.15f
        else -> 1.0f
    }

    private fun formatExpandedBody(
        currentLine: LyricLine?,
        chunk: PillChunk,
        nextLyric1: String,
        nextLyric2: String,
        title: String,
        artist: String
    ): String {
        val active = currentLine?.fullText?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (active.isBlank()) {
            return if (artist.isNotBlank()) {
                "$artist\nIn attesa del testo sincronizzato..."
            } else {
                "In attesa del testo sincronizzato..."
            }
        }

        val p1 = nextLyric1.trim()
        val p2 = nextLyric2.trim()

        return if (chunk.totalChunks > 1) {
            if (p1.isNotBlank()) "$active\n$p1" else "$active\n♪ $title"
        } else {
            when {
                p1.isNotBlank() && p2.isNotBlank() -> "$p1\n$p2"
                p1.isNotBlank() -> "$p1\n♪ $title"
                else -> "$title • $artist"
            }
        }
    }

    fun buildNotification(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLineStartMs: Long?,
        nextLyric1: String,
        nextLyric2: String,
        palette: CoverPalette,
        songPositionMs: Long,
        songDurationMs: Long,
        isPlaying: Boolean,
        animStep: Int
    ): Notification {
        val chunk = computePillChunk(currentLine, nextLineStartMs, songPositionMs, title, isPlaying)
        val expandedBody = formatExpandedBody(currentLine, chunk, nextLyric1, nextLyric2, title, artist)
        val accentColor = boostAccentBrightness(palette.accent.toArgb())
        val frameIndex = ((animStep % WAVE_FRAMES.size) + WAVE_FRAMES.size) % WAVE_FRAMES.size

        val hasLyrics = !currentLine?.fullText.isNullOrBlank()
        val smallIcon = when {
            !isPlaying -> pausedSmallIcon
            !hasLyrics -> idleSmallIcon
            else -> playingSmallIcons[frameIndex]
        }

        val expandedBadge = getExpandedBadge(frameIndex, isPlaying, accentColor)
        val progressPct = if (songDurationMs > 0L) {
            ((songPositionMs * 100L) / songDurationMs).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val subHeader = if (artist.isNotBlank()) "$title • $artist" else title

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setLargeIcon(expandedBadge)
            .setContentTitle(chunk.text)
            .setContentText(expandedBody)
            .setSubText(subHeader)
            .setColor(accentColor)
            .setColorized(false)
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
                    .invoke(builder, chunk.text)

                val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()
                progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(progressStyle, progressPct)
                progressStyleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
                    .invoke(progressStyle, true)

                Notification.Builder::class.java
                    .getMethod("setStyle", Notification.Style::class.java)
                    .invoke(builder, progressStyle)
            } catch (_: Throwable) {
                builder.setProgress(100, progressPct, false)
            }
        } else {
            builder.setProgress(100, progressPct, false)
        }

        return builder.build()
    }

    fun update(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLineStartMs: Long?,
        nextLyric1: String,
        nextLyric2: String,
        palette: CoverPalette,
        songPositionMs: Long,
        songDurationMs: Long,
        isPlaying: Boolean,
        animStep: Int
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                title,
                artist,
                currentLine,
                nextLineStartMs,
                nextLyric1,
                nextLyric2,
                palette,
                songPositionMs,
                songDurationMs,
                isPlaying,
                animStep
            )
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun boostAccentBrightness(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] < 0.68f) hsv[2] = 0.88f
        if (hsv[1] > 0.12f && hsv[1] < 0.45f) hsv[1] = 0.55f
        return Color.HSVToColor(255, hsv)
    }

    private fun getExpandedBadge(frameIndex: Int, isPlaying: Boolean, accentColor: Int): Bitmap {
        val key = "$frameIndex|$isPlaying|$accentColor"
        badgeCache[key]?.let { return it }

        if (badgeCache.size > 24) {
            badgeCache.clear()
        }

        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 26)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val outerRect = RectF(4f, 4f, size - 4f, size - 4f)
        canvas.drawRoundRect(outerRect, 34f, 34f, bgPaint)
        canvas.drawRoundRect(outerRect, 34f, 34f, borderPaint)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.FILL
        }

        if (isPlaying) {
            drawBars(canvas, size.toFloat(), 0.24f, WAVE_FRAMES[frameIndex], barPaint)
        } else {
            val barW = size * 0.13f
            val barH = size * 0.42f
            val gap = size * 0.14f
            val centerY = size / 2f
            val leftX = size / 2f - gap / 2f - barW
            val rightX = size / 2f + gap / 2f
            canvas.drawRoundRect(
                RectF(leftX, centerY - barH / 2f, leftX + barW, centerY + barH / 2f),
                barW / 2f, barW / 2f, barPaint
            )
            canvas.drawRoundRect(
                RectF(rightX, centerY - barH / 2f, rightX + barW, centerY + barH / 2f),
                barW / 2f, barW / 2f, barPaint
            )
        }

        badgeCache[key] = bmp
        return bmp
    }

    private fun renderSmallWaveBitmap(heights: FloatArray): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        drawBars(canvas, size.toFloat(), 0.14f, heights, paint)
        return bmp
    }

    private fun renderSmallPauseBitmap(): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val barW = size * 0.18f
        val barH = size * 0.54f
        val gap = size * 0.18f
        val cy = size / 2f
        val leftX = size / 2f - gap / 2f - barW
        val rightX = size / 2f + gap / 2f
        canvas.drawRoundRect(RectF(leftX, cy - barH / 2f, leftX + barW, cy + barH / 2f), barW / 2f, barW / 2f, paint)
        canvas.drawRoundRect(RectF(rightX, cy - barH / 2f, rightX + barW, cy + barH / 2f), barW / 2f, barW / 2f, paint)
        return bmp
    }

    private fun renderSmallIdleBitmap(): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val r = size * 0.09f
        val cy = size / 2f
        canvas.drawCircle(size * 0.24f, cy, r, paint)
        canvas.drawCircle(size * 0.50f, cy, r * 1.15f, paint)
        canvas.drawCircle(size * 0.76f, cy, r, paint)
        return bmp
    }

    private fun drawBars(
        canvas: Canvas,
        size: Float,
        paddingRatio: Float,
        heights: FloatArray,
        paint: Paint
    ) {
        val pad = size * paddingRatio
        val usableW = size - pad * 2f
        val usableH = size - pad * 2f
        val count = heights.size
        val gap = usableW * 0.11f
        val barW = (usableW - gap * (count - 1)) / count
        val centerY = size / 2f

        for (i in 0 until count) {
            val h = (usableH * heights[i]).coerceAtLeast(barW)
            val left = pad + i * (barW + gap)
            val top = centerY - h / 2f
            canvas.drawRoundRect(
                RectF(left, top, left + barW, top + h),
                barW / 2f,
                barW / 2f,
                paint
            )
        }
    }
}
