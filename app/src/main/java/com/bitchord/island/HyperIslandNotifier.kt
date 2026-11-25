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
import kotlin.math.max

class HyperIslandNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "bitchord_lyrics_live_pill"
        const val NOTIFICATION_ID = 3601
        private const val MAX_STATIC_PILL_CHARS = 12
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val playingSmallIcon: Icon
    private val pausedSmallIcon: Icon

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

    private fun cleanLine(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun buildStaticPillText(title: String, isPlaying: Boolean): String {
        if (!isPlaying) return "In pausa"
        val clean = cleanLine(title)
        if (clean.isEmpty()) return "Lyrics"
        if (clean.length <= MAX_STATIC_PILL_CHARS) return clean
        val spaceCut = clean.lastIndexOf(' ', MAX_STATIC_PILL_CHARS)
        return if (spaceCut >= 5) {
            clean.substring(0, spaceCut).trimEnd()
        } else {
            clean.substring(0, MAX_STATIC_PILL_CHARS - 1).trimEnd() + "…"
        }
    }

    private fun resolveDisplayLines(
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        title: String,
        isPlaying: Boolean
    ): Triple<String, String, String> {
        val prev = cleanLine(prevLyric)
        val curr = cleanLine(currentLine?.fullText.orEmpty())
        val next = cleanLine(nextLyric1)

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

    private fun buildIslandJson(pillText: String, accentHex: String): JSONObject {
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
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", "")
                        }
                    )
                }
            )
            put(
                "textInfo",
                JSONObject().apply {
                    put("title", pillText)
                    put("narrowFont", true)
                    put("showHighlightColor", false)
                }
            )
        }

        val smallIslandArea = JSONObject().apply {
            put(
                "picInfo",
                JSONObject().apply {
                    put("type", 1)
                    put("pic", "miui.focus.pic_icon")
                }
            )
            put(
                "combinePicInfo",
                JSONObject().apply {
                    put(
                        "picInfo",
                        JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_icon")
                        }
                    )
                }
            )
        }

        return JSONObject().apply {
            put("islandProperty", 1)
            put("highlightColor", accentHex)
            put("bigIslandArea", bigIslandArea)
            put("smallIslandArea", smallIslandArea)
        }
    }

    private fun buildFocusParamJson(pillText: String, accentHex: String): Pair<String, String> {
        val islandJson = buildIslandJson(pillText, accentHex)

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "bitchord_lyrics")
            put("updatable", true)
            put("islandFirstFloat", false)
            put("enableFloat", false)
            put("aodTitle", pillText)
            put("aodPic", "miui.focus.pic_disc")
            put("ticker", pillText)
            put("tickerPic", "miui.focus.pic_icon")
            put("param_island", islandJson)
        }

        val focusJson = JSONObject().apply {
            put("param_v2", paramV2)
        }.toString()

        val customFocusJson = JSONObject().apply {
            put("ticker", pillText)
            put("tickerPic", "miui.focus.pic_icon")
            put("aodTitle", pillText)
            put("aodPic", "miui.focus.pic_disc")
            put("updatable", true)
            put("enableFloat", false)
            put("islandFirstFloat", false)
            put("param_island", islandJson)
        }.toString()

        return focusJson to customFocusJson
    }

    fun buildNotification(
        title: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean
    ): Notification {
        val pillText = buildStaticPillText(title, isPlaying)
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
        val smallIcon = if (isPlaying) playingSmallIcon else pausedSmallIcon

        val expandedRv = buildExpandedRemoteViews(discBitmap, prevText, currText, nextText, accentColor)
        val (focusJson, customFocusJson) = buildFocusParamJson(pillText, accentHex)

        val focusPics = Bundle().apply {
            putParcelable("miui.focus.pic_disc", discIcon)
            putParcelable("miui.focus.pic_icon", smallIcon)
        }

        val extras = Bundle().apply {
            putString("miui.focus.param", focusJson)
            putString("miui.focus.param.custom", customFocusJson)
            putBundle("miui.focus.pics", focusPics)
            putParcelable("miui.focus.rv", expandedRv)
            putParcelable("miui.focus.rvNight", expandedRv)
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setLargeIcon(discBitmap)
            .setContentTitle(pillText)
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
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                prevLyric = prevLyric,
                currentLine = currentLine,
                nextLyric1 = nextLyric1,
                cover = cover,
                palette = palette,
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
}
