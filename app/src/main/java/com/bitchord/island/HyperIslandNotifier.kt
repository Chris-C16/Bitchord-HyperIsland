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
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil

class HyperIslandNotifier(private val context: Context) {

    data class PillChunk(
        val index: Int,
        val text: String,
        val totalChunks: Int,
        val isInstrumental: Boolean = false
    )

    companion object {
        const val CHANNEL_ID = "bitchord_lyrics_live_pill"
        const val NOTIFICATION_ID = 3601
        private const val MAX_PILL_VISUAL_WIDTH = 10.6f
        private const val INSTRUMENTAL_HOLD_MS = 450L
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val playingSmallIcon: Icon
    private val pausedSmallIcon: Icon
    private val idleSmallIcon: Icon

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

        playingSmallIcon = Icon.createWithBitmap(renderStaticWaveBitmap())
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
                buildBalancedChunks(cleanTitle.ifEmpty { "• • •" }).first()
            }
            return PillChunk(0, idleText, 1, isInstrumental = true)
        }

        val chunks = buildBalancedChunks(full)
        val estimatedVocalMs = (full.length * 85L + 420L).coerceIn(1100L, 4200L)
        val gapToNext = if (nextLineStartMs != null && nextLineStartMs > currentLine!!.startMs) {
            nextLineStartMs - currentLine.startMs
        } else {
            0L
        }
        val explicitDuration = currentLine!!.endMs - currentLine.startMs

        val availableWindow = when {
            gapToNext > 300L -> gapToNext
            explicitDuration > 300L -> explicitDuration
            else -> estimatedVocalMs
        }

        val vocalDuration = if (availableWindow > estimatedVocalMs + 1200L) {
            estimatedVocalMs
        } else {
            availableWindow.coerceIn(700L, 5200L)
        }

        val elapsed = (songPositionMs - currentLine.startMs).coerceAtLeast(0L)

        if (isPlaying && availableWindow > vocalDuration + INSTRUMENTAL_HOLD_MS && elapsed > vocalDuration + INSTRUMENTAL_HOLD_MS) {
            return PillChunk(-1, "• • •", chunks.size, isInstrumental = true)
        }

        if (chunks.size <= 1) {
            return PillChunk(0, chunks.first(), 1)
        }

        val ratio = (elapsed.toFloat() / vocalDuration).coerceIn(0f, 0.999f)
        val chunkIdx = (ratio * chunks.size).toInt().coerceIn(0, chunks.lastIndex)
        return PillChunk(chunkIdx, chunks[chunkIdx], chunks.size)
    }

    private fun buildBalancedChunks(text: String): List<String> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return listOf("• • •")
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
        val softLimit = ((totalWidth / targetCount) * 1.12f).coerceIn(5.2f, MAX_PILL_VISUAL_WIDTH)

        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (w in words) {
            if (current.isEmpty()) {
                current.append(w)
            } else {
                val candidate = "$current $w"
                val candWidth = visualWidth(candidate)
                val isLastBucket = result.size >= targetCount - 1
                if (!w.endsWith("-") && !current.endsWith("-") &&
                    (candWidth <= softLimit || (isLastBucket && candWidth <= MAX_PILL_VISUAL_WIDTH))
                ) {
                    current.append(" ").append(w)
                } else {
                    result.add(current.toString())
                    current.clear()
                    current.append(w)
                }
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.ifEmpty { listOf("• • •") }
    }

    private fun splitLongWord(word: String): List<String> {
        val totalW = visualWidth(word)
        val maxContentW = MAX_PILL_VISUAL_WIDTH - 1.1f
        val partsCount = ceil(totalW / maxContentW).toInt().coerceAtLeast(2)
        val targetW = totalW / partsCount

        val parts = mutableListOf<String>()
        var startIdx = 0
        var remainingParts = partsCount
        val vowels = "aeiouàèéìòóùáíúAEIOUÀÈÉÌÒÓÙÁÍÚ"

        while (startIdx < word.length) {
            if (remainingParts <= 1) {
                val tail = word.substring(startIdx)
                if (visualWidth(tail) <= MAX_PILL_VISUAL_WIDTH) {
                    parts.add(tail)
                    break
                }
            }

            var endIdx = startIdx
            var accW = 0f
            while (endIdx < word.length && accW + charVisualWidth(word[endIdx]) <= maxContentW) {
                accW += charVisualWidth(word[endIdx])
                endIdx++
                if (accW >= targetW && remainingParts > 1) break
            }

            if (endIdx <= startIdx) endIdx = (startIdx + 1).coerceAtMost(word.length)

            val leftover = word.length - endIdx
            if (leftover in 1..2 && endIdx - startIdx > 3) {
                endIdx -= (3 - leftover)
            }

            if (endIdx < word.length && endIdx - startIdx >= 3) {
                if (word[endIdx - 1] !in vowels) {
                    if (endIdx - 2 > startIdx + 1 && word[endIdx - 2] in vowels) {
                        endIdx -= 1
                    } else if (endIdx < word.length - 2 && word[endIdx] in vowels) {
                        if (visualWidth(word.substring(startIdx, endIdx + 1)) <= maxContentW) {
                            endIdx += 1
                        }
                    }
                }
            }

            val slice = word.substring(startIdx, endIdx)
            val isLast = endIdx >= word.length
            parts.add(if (isLast) slice else "$slice-")
            startIdx = endIdx
            remainingParts = (remainingParts - 1).coerceAtLeast(1)
        }
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

    private fun ellipsizeForCard(line: String, maxChars: Int = 42): String {
        val clean = line.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= maxChars) return clean
        val cut = clean.lastIndexOf(' ', maxChars - 1).takeIf { it >= maxChars / 2 } ?: (maxChars - 1)
        return clean.substring(0, cut).trimEnd() + "…"
    }

    private fun appendStyledLine(
        sb: SpannableStringBuilder,
        text: String,
        isCurrent: Boolean
    ) {
        if (text.isBlank()) return
        if (sb.isNotEmpty()) sb.append("\n")
        val start = sb.length
        sb.append(text)
        val end = sb.length
        if (isCurrent) {
            sb.setSpan(TypefaceSpan("sans-serif-medium"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(1.06f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.setSpan(TypefaceSpan("sans-serif"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.argb(115, 255, 255, 255)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.86f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatStyledExpandedLyrics(
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        chunk: PillChunk
    ): CharSequence {
        val sb = SpannableStringBuilder()
        val prev = ellipsizeForCard(prevLyric)
        val curr = ellipsizeForCard(currentLine?.fullText.orEmpty())
        val next = ellipsizeForCard(nextLyric1)

        if (curr.isBlank()) {
            appendStyledLine(sb, "In attesa del testo…", isCurrent = true)
            if (next.isNotBlank()) appendStyledLine(sb, next, isCurrent = false)
            return sb
        }

        if (chunk.isInstrumental) {
            appendStyledLine(sb, curr, isCurrent = false)
            appendStyledLine(sb, "♪  • • •", isCurrent = true)
            if (next.isNotBlank()) appendStyledLine(sb, next, isCurrent = false)
            return sb
        }

        if (prev.isNotBlank()) appendStyledLine(sb, prev, isCurrent = false)
        appendStyledLine(sb, curr, isCurrent = true)
        if (next.isNotBlank()) appendStyledLine(sb, next, isCurrent = false)
        return sb
    }

    fun buildNotification(
        title: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLineStartMs: Long?,
        nextLyric1: String,
        palette: CoverPalette,
        songPositionMs: Long,
        songDurationMs: Long,
        isPlaying: Boolean
    ): Notification {
        val chunk = computePillChunk(currentLine, nextLineStartMs, songPositionMs, title, isPlaying)
        val expandedBody = formatStyledExpandedLyrics(prevLyric, currentLine, nextLyric1, chunk)
        val accentColor = boostAccentBrightness(palette.accent.toArgb())

        val smallIcon = when {
            !isPlaying -> pausedSmallIcon
            chunk.isInstrumental -> idleSmallIcon
            else -> playingSmallIcon
        }

        val progressPct = if (songDurationMs > 0L) {
            ((songPositionMs * 100L) / songDurationMs).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        // String pura in setContentTitle: fondamentale perché HyperOS la legga nella pillola chiusa
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(chunk.text)
            .setContentText(expandedBody)
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
        prevLyric: String,
        currentLine: LyricLine?,
        nextLineStartMs: Long?,
        nextLyric1: String,
        palette: CoverPalette,
        songPositionMs: Long,
        songDurationMs: Long,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                title,
                prevLyric,
                currentLine,
                nextLineStartMs,
                nextLyric1,
                palette,
                songPositionMs,
                songDurationMs,
                isPlaying
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

    private fun renderStaticWaveBitmap(): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val heights = floatArrayOf(0.42f, 0.92f, 0.42f)
        val pad = size * 0.16f
        val usableW = size - pad * 2f
        val usableH = size - pad * 2f
        val gap = usableW * 0.16f
        val barW = (usableW - gap * (heights.size - 1)) / heights.size
        val centerY = size / 2f

        for (i in heights.indices) {
            val h = (usableH * heights[i]).coerceAtLeast(barW)
            val left = pad + i * (barW + gap)
            val top = centerY - h / 2f
            canvas.drawRoundRect(RectF(left, top, left + barW, top + h), barW / 2f, barW / 2f, paint)
        }
        return bmp
    }

    private fun renderSmallPauseBitmap(): Bitmap {
        val size = 48
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
        val size = 48
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
}
