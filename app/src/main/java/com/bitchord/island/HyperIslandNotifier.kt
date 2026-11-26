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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
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

        // Soglie in larghezza visiva reale (misurate con textSize = 100f)
        private const val NORMAL_MAX_WIDTH = 680f
        private const val NARROW_MAX_WIDTH = 880f
        private const val TRAILING_JUNK_CHARS = "([{-–—/:,&+.|•"

        // Parametri rotazione 360° del vinile dentro RemoteViews
        private const val SPIN_FRAME_COUNT = 16
        private const val SPIN_FRAME_SIZE = 56
        private const val SPIN_INTERVAL_MS = 125L

        private val SPIN_FRAME_IDS = intArrayOf(
            R.id.iv_disc_f0, R.id.iv_disc_f1, R.id.iv_disc_f2, R.id.iv_disc_f3,
            R.id.iv_disc_f4, R.id.iv_disc_f5, R.id.iv_disc_f6, R.id.iv_disc_f7,
            R.id.iv_disc_f8, R.id.iv_disc_f9, R.id.iv_disc_f10, R.id.iv_disc_f11,
            R.id.iv_disc_f12, R.id.iv_disc_f13, R.id.iv_disc_f14, R.id.iv_disc_f15
        )

        // Regex pre-compilate una sola volta
        private val REGEX_MULTI_SPACE = Regex("\\s+")
        private val REGEX_LEADING_EXPLICIT_EMOJI = Regex("""^[🅴\s]+""")
        private val REGEX_LEADING_EXPLICIT_TAG = Regex("""^[\[\(]E[\]\)]\s*""", RegexOption.IGNORE_CASE)
        private val REGEX_PARENS = Regex("""\s*[\(\[].*?[\)\]]""")
        private val REGEX_UNCLOSED_PAREN = Regex("""\s*[\(\[][^\)\]]*$""")
        private val REGEX_FEAT = Regex("""\s+(feat\.|ft\.|prod\.).*$""", RegexOption.IGNORE_CASE)
    }

    private data class PillLabel(
        val text: String,
        val useNarrowFont: Boolean
    )

    private data class VinylAssets(
        val baseDisc: Bitmap,
        val icon: Icon,
        val spinFrames: Array<Bitmap>
    )

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val playingSmallIcon: Icon
    private val pausedSmallIcon: Icon
    private val playButtonBitmap: Bitmap
    private val pauseButtonBitmap: Bitmap
    private val lyricsPendingIntent: PendingIntent
    private val playPausePendingIntent: PendingIntent

    private val pillMeasurePaint = Paint().apply {
        textSize = 100f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bitmapFilterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rotateMatrix = Matrix()
    private val hsvBuffer = FloatArray(3)

    private var cachedCoverRef: Bitmap? = null
    private var cachedDiscAccent: Int = 0
    private var cachedVinylAssets: VinylAssets? = null

    private var lastSpinStep: Int = 0
    private var cachedPausedStep: Int = -1
    private var cachedPausedBase: Bitmap? = null
    private var cachedPausedDisc: Bitmap? = null

    // Cache label pillola (evita regex e measureText ad ogni cambio verso)
    private var cachedPillTitle: String? = null
    private var cachedPillPlaying: Boolean = false
    private var cachedPillLabel: PillLabel? = null

    // Cache JSON HyperIsland
    private var cachedFocusJsonKey: String = ""
    private var cachedFocusJsonPair: Pair<String, String>? = null

    // Deduplicazione + risparmio batteria a schermo spento
    private var lastFullStateKey: String = ""
    private var lastTrackStateKey: String = ""

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
        playButtonBitmap = renderPlayButtonBitmap()
        pauseButtonBitmap = renderPauseButtonBitmap()

        lyricsPendingIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, LyricsPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        playPausePendingIntent = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, IslandPlaybackReceiver::class.java).apply {
                action = IslandPlaybackReceiver.ACTION_TOGGLE_PLAYBACK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cleanLine(text: String): String {
        if (text.isEmpty()) return ""
        return text.replace(REGEX_MULTI_SPACE, " ").trim()
    }

    private fun sanitizeTrailingJunk(text: String): String {
        return text.trimEnd { it.isWhitespace() || it in TRAILING_JUNK_CHARS }
    }

    private fun simplifyTitleForPill(rawTitle: String): String {
        val clean = cleanLine(rawTitle)
            .replace(REGEX_LEADING_EXPLICIT_EMOJI, "")
            .replace(REGEX_LEADING_EXPLICIT_TAG, "")
            .trim()
        if (clean.isEmpty()) return ""

        val withoutParens = clean
            .replace(REGEX_PARENS, "")
            .replace(REGEX_UNCLOSED_PAREN, "")
            .replace(REGEX_FEAT, "")
            .trim()

        var base = if (withoutParens.isNotEmpty()) withoutParens else clean

        if (pillMeasurePaint.measureText(base) > NORMAL_MAX_WIDTH) {
            val beforeDash = base.substringBefore(" - ")
                .substringBefore(" – ")
                .substringBefore(" — ")
                .trim()
            if (beforeDash.isNotEmpty()) {
                base = beforeDash
            }
        }

        val cleaned = sanitizeTrailingJunk(cleanLine(base))
        return if (cleaned.isNotEmpty()) cleaned else clean
    }

    private fun truncateToVisualWidth(text: String, maxWidth: Float): String {
        val ellipsis = "…"
        val ellipsisWidth = pillMeasurePaint.measureText(ellipsis)
        val targetWidth = (maxWidth - ellipsisWidth).coerceAtLeast(200f)

        var end = text.length
        while (end > 1 && pillMeasurePaint.measureText(text, 0, end) > targetWidth) {
            end--
        }
        if (end < text.length && end > 1 && Character.isLowSurrogate(text[end])) {
            end--
        }

        val rawSlice = text.substring(0, end)
        val candidate = if (end < text.length && text[end].isWhitespace()) {
            sanitizeTrailingJunk(rawSlice)
        } else {
            val lastSpace = rawSlice.lastIndexOf(' ')
            val wordCandidate = if (lastSpace >= 3) {
                sanitizeTrailingJunk(rawSlice.substring(0, lastSpace))
            } else {
                ""
            }
            if (wordCandidate.isNotEmpty() &&
                pillMeasurePaint.measureText(wordCandidate) >= targetWidth * 0.52f
            ) {
                wordCandidate
            } else {
                sanitizeTrailingJunk(rawSlice)
            }
        }

        return if (candidate.isNotEmpty()) "$candidate$ellipsis" else "Lyrics"
    }

    private fun buildStaticPillLabel(title: String, isPlaying: Boolean): PillLabel {
        val cached = cachedPillLabel
        if (cached != null && cachedPillTitle == title && cachedPillPlaying == isPlaying) {
            return cached
        }

        val label = if (!isPlaying) {
            PillLabel("In pausa", useNarrowFont = false)
        } else {
            val base = simplifyTitleForPill(title)
            if (base.isEmpty()) {
                PillLabel("Lyrics", useNarrowFont = false)
            } else {
                val width = pillMeasurePaint.measureText(base)
                when {
                    width <= NORMAL_MAX_WIDTH -> PillLabel(base, useNarrowFont = false)
                    width <= NARROW_MAX_WIDTH -> PillLabel(base, useNarrowFont = true)
                    else -> PillLabel(
                        text = truncateToVisualWidth(base, NARROW_MAX_WIDTH),
                        useNarrowFont = true
                    )
                }
            }
        }

        cachedPillTitle = title
        cachedPillPlaying = isPlaying
        cachedPillLabel = label
        return label
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
        vinyl: VinylAssets,
        prev: String,
        curr: String,
        next: String,
        accentColor: Int,
        isPlaying: Boolean
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.island_lyrics_expanded)

        if (isPlaying) {
            val startStep = ((SystemClock.elapsedRealtime() / SPIN_INTERVAL_MS) % SPIN_FRAME_COUNT).toInt()
            lastSpinStep = startStep

            rv.setViewVisibility(R.id.iv_disc_cover, View.GONE)
            rv.setViewVisibility(R.id.vf_disc_spin, View.VISIBLE)
            for (i in 0 until SPIN_FRAME_COUNT) {
                val frame = vinyl.spinFrames[(startStep + i) % SPIN_FRAME_COUNT]
                rv.setImageViewBitmap(SPIN_FRAME_IDS[i], frame)
            }
        } else {
            rv.setViewVisibility(R.id.vf_disc_spin, View.GONE)
            rv.setViewVisibility(R.id.iv_disc_cover, View.VISIBLE)
            rv.setImageViewBitmap(R.id.iv_disc_cover, getPausedDiscBitmap(vinyl.baseDisc, lastSpinStep))
        }

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

        rv.setImageViewBitmap(
            R.id.btn_play_pause,
            if (isPlaying) pauseButtonBitmap else playButtonBitmap
        )
        rv.setOnClickPendingIntent(R.id.btn_play_pause, playPausePendingIntent)

        return rv
    }

    private fun buildIslandJson(
        pillText: String,
        useNarrowFont: Boolean,
        accentHex: String
    ): JSONObject {
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
                    put("narrowFont", useNarrowFont)
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

    private fun getOrBuildFocusParamJson(
        pillText: String,
        useNarrowFont: Boolean,
        accentHex: String
    ): Pair<String, String> {
        val key = "$pillText|$useNarrowFont|$accentHex"
        val existing = cachedFocusJsonPair
        if (existing != null && cachedFocusJsonKey == key) {
            return existing
        }

        val islandJson = buildIslandJson(pillText, useNarrowFont, accentHex)

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

        val pair = focusJson to customFocusJson
        cachedFocusJsonKey = key
        cachedFocusJsonPair = pair
        return pair
    }

    private fun buildNotificationInternal(
        pillLabel: PillLabel,
        accentColor: Int,
        prevText: String,
        currText: String,
        nextText: String,
        cover: Bitmap?,
        isPlaying: Boolean
    ): Notification {
        val accentHex = String.format("#%06X", 0xFFFFFF and accentColor)
        val expandedBody = formatStyledExpandedLyrics(prevText, currText, nextText, accentColor)
        val vinyl = getOrCreateVinylAssets(cover, accentColor)
        val smallIcon = if (isPlaying) playingSmallIcon else pausedSmallIcon

        val expandedRv = buildExpandedRemoteViews(
            vinyl = vinyl,
            prev = prevText,
            curr = currText,
            next = nextText,
            accentColor = accentColor,
            isPlaying = isPlaying
        )
        val (focusJson, customFocusJson) = getOrBuildFocusParamJson(
            pillText = pillLabel.text,
            useNarrowFont = pillLabel.useNarrowFont,
            accentHex = accentHex
        )

        val focusPics = Bundle().apply {
            putParcelable("miui.focus.pic_disc", vinyl.icon)
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
            .setLargeIcon(vinyl.baseDisc)
            .setContentTitle(pillLabel.text)
            .setContentText(expandedBody)
            .setColor(accentColor)
            .setColorized(false)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(lyricsPendingIntent)
            .addExtras(extras)
            .build()
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
        val pillLabel = buildStaticPillLabel(title, isPlaying)
        val accentColor = boostAccentBrightness(palette.accent.toArgb())
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            isPlaying = isPlaying
        )

        return buildNotificationInternal(
            pillLabel = pillLabel,
            accentColor = accentColor,
            prevText = prevText,
            currText = currText,
            nextText = nextText,
            cover = cover,
            isPlaying = isPlaying
        )
    }

    fun markForegroundPosted(
        title: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean
    ) {
        val pillLabel = buildStaticPillLabel(title, isPlaying)
        val accentColor = boostAccentBrightness(palette.accent.toArgb())
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            isPlaying = isPlaying
        )
        val coverId = System.identityHashCode(cover)
        val trackStateKey = "${pillLabel.text}|$isPlaying|$accentColor|$coverId"
        lastTrackStateKey = trackStateKey
        lastFullStateKey = "$trackStateKey|$prevText|$currText|$nextText"
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
        val pillLabel = buildStaticPillLabel(title, isPlaying)
        val accentColor = boostAccentBrightness(palette.accent.toArgb())
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            isPlaying = isPlaying
        )

        val coverId = System.identityHashCode(cover)
        val trackStateKey = "${pillLabel.text}|$isPlaying|$accentColor|$coverId"
        val fullStateKey = "$trackStateKey|$prevText|$currText|$nextText"

        if (fullStateKey == lastFullStateKey) return

        val isScreenOn = powerManager?.isInteractive ?: true
        if (!isScreenOn && trackStateKey == lastTrackStateKey) return

        lastTrackStateKey = trackStateKey
        lastFullStateKey = fullStateKey

        manager.notify(
            NOTIFICATION_ID,
            buildNotificationInternal(
                pillLabel = pillLabel,
                accentColor = accentColor,
                prevText = prevText,
                currText = currText,
                nextText = nextText,
                cover = cover,
                isPlaying = isPlaying
            )
        )
    }

    fun cancel() {
        lastFullStateKey = ""
        lastTrackStateKey = ""
        manager.cancel(NOTIFICATION_ID)
    }

    private fun boostAccentBrightness(color: Int): Int {
        Color.colorToHSV(color, hsvBuffer)
        if (hsvBuffer[1] < 0.14f) {
            return Color.rgb(228, 236, 255)
        }
        hsvBuffer[2] = hsvBuffer[2].coerceAtLeast(0.92f)
        hsvBuffer[1] = hsvBuffer[1].coerceIn(0.40f, 0.85f)
        return Color.HSVToColor(255, hsvBuffer)
    }

    private fun getOrCreateVinylAssets(cover: Bitmap?, accentColor: Int): VinylAssets {
        val existing = cachedVinylAssets
        if (existing != null && cachedCoverRef === cover && cachedDiscAccent == accentColor) {
            return existing
        }

        val baseDisc = renderVinylDiscBitmap(cover, accentColor)
        val discIcon = Icon.createWithBitmap(baseDisc)
        val stepDeg = 360f / SPIN_FRAME_COUNT
        val frames = Array(SPIN_FRAME_COUNT) { index ->
            renderRotatedDisc(baseDisc, SPIN_FRAME_SIZE, index * stepDeg)
        }

        val assets = VinylAssets(baseDisc = baseDisc, icon = discIcon, spinFrames = frames)
        cachedCoverRef = cover
        cachedDiscAccent = accentColor
        cachedVinylAssets = assets
        cachedPausedBase = null
        cachedPausedDisc = null
        cachedPausedStep = -1
        return assets
    }

    private fun getPausedDiscBitmap(baseDisc: Bitmap, step: Int): Bitmap {
        val normalizedStep = ((step % SPIN_FRAME_COUNT) + SPIN_FRAME_COUNT) % SPIN_FRAME_COUNT
        val existing = cachedPausedDisc
        if (existing != null && cachedPausedBase === baseDisc && cachedPausedStep == normalizedStep) {
            return existing
        }

        val angle = normalizedStep * (360f / SPIN_FRAME_COUNT)
        val rotated = if (normalizedStep == 0) {
            baseDisc
        } else {
            renderRotatedDisc(baseDisc, baseDisc.width, angle)
        }
        cachedPausedBase = baseDisc
        cachedPausedStep = normalizedStep
        cachedPausedDisc = rotated
        return rotated
    }

    private fun renderRotatedDisc(baseDisc: Bitmap, targetSize: Int, degrees: Float): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = targetSize.toFloat() / baseDisc.width.toFloat()
        rotateMatrix.reset()
        rotateMatrix.postScale(scale, scale)
        rotateMatrix.postRotate(degrees, targetSize / 2f, targetSize / 2f)
        canvas.drawBitmap(baseDisc, rotateMatrix, bitmapFilterPaint)
        return out
    }

    private fun renderVinylDiscBitmap(cover: Bitmap?, accentColor: Int): Bitmap {
        val size = 112
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
            strokeWidth = 1.5f
        }
        canvas.drawCircle(cx, cy, outerRadius * 0.84f, groovePaint)
        canvas.drawCircle(cx, cy, outerRadius * 0.68f, groovePaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
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
            strokeWidth = 1.8f
        }
        canvas.drawCircle(cx, cy, size * 0.125f, holeRimPaint)

        return bmp
    }

    private fun renderPlayButtonBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 4.5f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(size * 0.34f, size * 0.22f)
            lineTo(size * 0.76f, size * 0.50f)
            lineTo(size * 0.34f, size * 0.78f)
            close()
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun renderPauseButtonBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val barW = size * 0.17f
        val barH = size * 0.56f
        val gap = size * 0.18f
        val cy = size / 2f
        val leftX = size / 2f - gap / 2f - barW
        val rightX = size / 2f + gap / 2f
        val radius = barW / 2f
        canvas.drawRoundRect(RectF(leftX, cy - barH / 2f, leftX + barW, cy + barH / 2f), radius, radius, paint)
        canvas.drawRoundRect(RectF(rightX, cy - barH / 2f, rightX + barW, cy + barH / 2f), radius, radius, paint)
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
        val radius = barW / 2f
        canvas.drawRoundRect(RectF(leftX, cy - barH / 2f, leftX + barW, cy + barH / 2f), radius, radius, paint)
        canvas.drawRoundRect(RectF(rightX, cy - barH / 2f, rightX + barW, cy + barH / 2f), radius, radius, paint)
        return bmp
    }
}
