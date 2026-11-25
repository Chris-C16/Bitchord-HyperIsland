package com.bitchord.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

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
        private const val INSTRUMENTAL_HOLD_MS = 2400L
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val playingSmallIcon: Icon
    private val pausedSmallIcon: Icon
    private val idleSmallIcon: Icon

    private var cachedCoverRef: Bitmap? = null
    private var cachedDiscAccent: Int = 0
    private var cachedDiscBitmap: Bitmap? = null
    private var cachedDiscIcon: Icon? = null

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
        val estimatedVocalMs = (full.length * 80L + 500L).coerceIn(1400L, 3800L)
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

        val hasTrueInstrumentalGap = availableWindow > estimatedVocalMs + INSTRUMENTAL_HOLD_MS
        val vocalDuration = if (hasTrueInstrumentalGap) {
            estimatedVocalMs
        } else {
            availableWindow.coerceIn(750L, 6500L)
        }

        val elapsed = (songPositionMs - currentLine.startMs).coerceAtLeast(0L)

        if (isPlaying && hasTrueInstrumentalGap && elapsed > vocalDuration + 900L) {
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

    private fun ellipsizeForCard(line: String, maxChars: Int = 40): String {
        val clean = line.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= maxChars) return clean
        val cut = clean.lastIndexOf(' ', maxChars - 1).takeIf { it >= maxChars / 2 } ?: (maxChars - 1)
        return clean.substring(0, cut).trimEnd() + "…"
    }

    private fun resolveDisplayLines(
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        title: String,
        isPlaying: Boolean
    ): Triple<String, String, String> {
        val prev = ellipsizeForCard(prevLyric)
        val curr = ellipsizeForCard(currentLine?.fullText.orEmpty())
        val next = ellipsizeForCard(nextLyric1)

        if (!isPlaying && curr.isBlank()) {
            return Triple("", title.ifBlank { "In pausa" }, "Riproduzione in pausa")
        }
        if (curr.isBlank()) {
            return Triple("", title.ifBlank { "In attesa del testo…" }, next)
        }
        return Triple(prev, curr, next)
    }

    private fun appendStyledLine(
        sb: SpannableStringBuilder,
        text: String,
        isCurrent: Boolean,
        accentColor: Int
    ) {
        if (text.isBlank()) return
        if (sb.isNotEmpty()) sb.append("\n")
        val start = sb.length
        sb.append(text)
        val end = sb.length
        if (isCurrent) {
            sb.setSpan(TypefaceSpan("sans-serif-medium"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(accentColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(1.08f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.setSpan(TypefaceSpan("sans-serif"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.argb(115, 255, 255, 255)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.86f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatStyledExpandedLyrics(
        prev: String,
        curr: String,
        next: String,
        accentColor: Int
    ): CharSequence {
        val sb = SpannableStringBuilder()
        if (prev.isNotBlank()) appendStyledLine(sb, prev, isCurrent = false, accentColor = accentColor)
        appendStyledLine(sb, curr, isCurrent = true, accentColor = accentColor)
        if (next.isNotBlank()) appendStyledLine(sb, next, isCurrent = false, accentColor = accentColor)
        return sb
    }

    private fun buildExpandedRemoteViews(
        discBitmap: Bitmap,
        prev: String,
        curr: String,
        next: String,
        accentColor: Int
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.island_lyrics_expanded)
        rv.setImageViewBitmap(R.id.iv_disc_cover, discBitmap)

        if (prev.isNotBlank()) {
            rv.setViewVisibility(R.id.tv_lyric_prev, View.VISIBLE)
            rv.setTextViewText(R.id.tv_lyric_prev, prev)
        } else {
            rv.setViewVisibility(R.id.tv_lyric_prev, View.GONE)
        }

        rv.setTextViewText(R.id.tv_lyric_curr, curr)
        rv.setTextColor(R.id.tv_lyric_curr, accentColor)

        if (next.isNotBlank()) {
            rv.setViewVisibility(R.id.tv_lyric_next, View.VISIBLE)
            rv.setTextViewText(R.id.tv_lyric_next, next)
        } else {
            rv.setViewVisibility(R.id.tv_lyric_next, View.GONE)
        }

        return rv
    }

    private fun buildFocusParamJson(
        pillText: String,
        currLineText: String,
        nextLineText: String,
        accentHex: String
    ): String {
        val baseInfo = JSONObject().apply {
            put("type", 2)
            put("title", currLineText)
            if (nextLineText.isNotBlank()) put("content", nextLineText)
            put("colorTitle", accentHex)
        }

        val picInfo = JSONObject().apply {
            put("type", 1)
            put("pic", "miui.focus.pic_disc")
        }

        val bigIslandArea = JSONObject().apply {
            put(
                "imageTextInfoLeft",
                JSONObject().apply {
                    put("type", 1)
                    put(
                        "picInfo",
                        JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_icon")
                        }
                    )
                }
            )
            put(
                "imageTextInfoRight",
                JSONObject().apply {
                    put("type", 2)
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", pillText)
                            put("narrowFont", true)
                            put("showHighlightColor", false)
                        }
                    )
                }
            )
        }

        val smallIslandArea = JSONObject().apply {
            put(
                "picInfo",
                JSONObject().apply {
                    put("type", 1)
                    put("pic", "miui.focus.pic_disc")
                }
            )
        }

        val paramIsland = JSONObject().apply {
            put("islandProperty", 1)
            put("highlightColor", accentHex)
            put("bigIslandArea", bigIslandArea)
            put("smallIslandArea", smallIslandArea)
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "bitchord_lyrics")
            put("updatable", true)
            put("islandFirstFloat", false)
            put("enableFloat", false)
            put("aodTitle", pillText)
            put("ticker", pillText)
            put("tickerPic", "miui.focus.pic_icon")
            put("baseInfo", baseInfo)
            put("picInfo", picInfo)
            put("param_island", paramIsland)
        }

        return JSONObject().apply {
            put("param_v2", paramV2)
        }.toString()
    }

    fun buildNotification(
        title: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLineStartMs: Long?,
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        songPositionMs: Long,
        songDurationMs: Long,
        isPlaying: Boolean
    ): Notification {
        val chunk = computePillChunk(currentLine, nextLineStartMs, songPositionMs, title, isPlaying)
        val accentColor = boostAccentBrightness(palette.accent.toArgb())
        val accentHex = String.format("#%06X", 0xFFFFFF and accentColor)

        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            isPlaying = isPlaying
        )

        val expandedBody = formatStyledExpandedLyrics(prevText, currText, nextText, accentColor)
        val (discBitmap, discIcon) = getOrCreateVinylDisc(cover, accentColor)

        val smallIcon = when {
            !isPlaying -> pausedSmallIcon
            chunk.isInstrumental -> idleSmallIcon
            else -> playingSmallIcon
        }

        val expandedRv = buildExpandedRemoteViews(discBitmap, prevText, currText, nextText, accentColor)
        val focusJson = buildFocusParamJson(
            pillText = chunk.text,
            currLineText = currText,
            nextLineText = nextText,
            accentHex = accentHex
        )

        val focusPics = Bundle().apply {
            putParcelable("miui.focus.pic_disc", discIcon)
            putParcelable("miui.focus.pic_icon", smallIcon)
        }

        val extras = Bundle().apply {
            putString("miui.focus.param", focusJson)
            putBundle("miui.focus.pics", focusPics)
            putParcelable("miui.focus.rv", expandedRv)
            putParcelable("miui.focus.rvNight", expandedRv)
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setLargeIcon(discBitmap)
            .setContentTitle(chunk.text)
            .setContentText(expandedBody)
            .setColor(accentColor)
            .setColorized(false)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(createLyricsPendingIntent())
            .addExtras(extras)
            .build()
    }

    fun update(
        title: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLineStartMs: Long?,
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        songPositionMs: Long,
        songDurationMs: Long,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                prevLyric = prevLyric,
                currentLine = currentLine,
                nextLineStartMs = nextLineStartMs,
                nextLyric1 = nextLyric1,
                cover = cover,
                palette = palette,
                songPositionMs = songPositionMs,
                songDurationMs = songDurationMs,
                isPlaying = isPlaying
            )
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun boostAccentBrightness(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.14f) {
            return Color.rgb(228, 236, 255)
        }
        hsv[2] = hsv[2].coerceAtLeast(0.92f)
        hsv[1] = hsv[1].coerceIn(0.40f, 0.85f)
        return Color.HSVToColor(255, hsv)
    }

    private fun getOrCreateVinylDisc(cover: Bitmap?, accentColor: Int): Pair<Bitmap, Icon> {
        val existingBmp = cachedDiscBitmap
        val existingIcon = cachedDiscIcon
        if (existingBmp != null && existingIcon != null && cachedCoverRef === cover && cachedDiscAccent == accentColor) {
            return existingBmp to existingIcon
        }

        val discBmp = renderVinylDiscBitmap(cover, accentColor)
        val discIcon = Icon.createWithBitmap(discBmp)
        cachedCoverRef = cover
        cachedDiscAccent = accentColor
        cachedDiscBitmap = discBmp
        cachedDiscIcon = discIcon
        return discBmp to discIcon
    }

    private fun renderVinylDiscBitmap(cover: Bitmap?, accentColor: Int): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val outerRadius = size / 2f - 2f

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(22, 22, 28)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, outerRadius, basePaint)

        if (cover != null && !cover.isRecycled && cover.width > 0 && cover.height > 0) {
            val scale = max(size.toFloat() / cover.width, size.toFloat() / cover.height)
            val dx = (size - cover.width * scale) / 2f
            val dy = (size - cover.height * scale) / 2f
            val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            })
            val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = shader
            }
            canvas.drawCircle(cx, cy, outerRadius - 3f, artPaint)
        } else {
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, outerRadius * 0.48f, labelPaint)
        }

        val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }
        canvas.drawCircle(cx, cy, outerRadius * 0.84f, groovePaint)
        canvas.drawCircle(cx, cy, outerRadius * 0.68f, groovePaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(cx, cy, outerRadius - 1.5f, rimPaint)

        val centerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 20, 20, 24)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, size * 0.125f, centerRingPaint)

        val holeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(cx, cy, size * 0.125f, holeRimPaint)

        return bmp
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
