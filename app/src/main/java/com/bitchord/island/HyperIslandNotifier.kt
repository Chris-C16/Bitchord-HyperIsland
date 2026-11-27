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
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class HyperIslandNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "bitchord_lyrics_live_pill"
        const val NOTIFICATION_ID = 3601

        // Soglie larghezza visiva reale (misurate con textSize = 100f)
        private const val LEFT_WING_MAX_WIDTH = 600f
        private const val RIGHT_WING_MAX_WIDTH = 480f
        private const val TRAILING_JUNK_CHARS = "([{-–—/:,&+.|•"

        // Parametri rotazione 360° del vinile dentro RemoteViews (rispetto limite Binder ~1MB)
        private const val SPIN_FRAME_COUNT = 16
        private const val SPIN_FRAME_SIZE = 72
        private const val SPIN_INTERVAL_MS = 125L
        private const val WING_ICON_SIZE = 96

        private const val PROGRESS_BUCKET_COUNT = 10

        const val PIC_DISC = "miui.focus.pic_disc"
        const val PIC_WAVE = "miui.focus.pic_wave"
        const val PIC_PAUSE = "miui.focus.pic_pause"
        const val PIC_SMALL = "miui.focus.pic_icon"

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

    data class WingState(
        val text: String,
        val iconKey: String,
        val progressBucket: Int
    )

    private data class VinylAssets(
        val baseDisc: Bitmap,
        val icon: Icon,
        val spinFrames: Array<Bitmap>
    )

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val smallWaveIcon: Icon
    private val smallPauseIcon: Icon
    private val wingWaveIcon: Icon
    private val wingPauseIcon: Icon
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

    private var cachedRingAccent: Int = 0
    private var cachedProgressRingIcons: Array<Icon>? = null

    private var lastSpinStep: Int = 0
    private var cachedPausedStep: Int = -1
    private var cachedPausedBase: Bitmap? = null
    private var cachedPausedDisc: Bitmap? = null

    // Cache titolo/artista semplificati per le due ali
    private var cachedRawLeftTitle: String? = null
    private var cachedCleanLeftTitle: String = ""
    private var cachedRawRightArtist: String? = null
    private var cachedCleanRightArtist: String = ""

    // Cache JSON HyperIsland
    private var cachedFocusJsonKey: String = ""
    private var cachedFocusJsonPair: Pair<String, String>? = null

    // Deduplicazione notifiche
    private var lastFullStateKey: String = ""

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

        smallWaveIcon = Icon.createWithBitmap(renderWaveBitmap(48))
        smallPauseIcon = Icon.createWithBitmap(renderPauseGlyphBitmap(48))
        wingWaveIcon = Icon.createWithBitmap(renderWaveBitmap(WING_ICON_SIZE))
        wingPauseIcon = Icon.createWithBitmap(renderPauseGlyphBitmap(WING_ICON_SIZE))
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

    private fun simplifyMetadataText(raw: String): String {
        val clean = cleanLine(raw)
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

        if (pillMeasurePaint.measureText(base) > LEFT_WING_MAX_WIDTH) {
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

    private fun truncateToVisualWidth(text: String, maxWidth: Float, fallback: String): String {
        if (pillMeasurePaint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = pillMeasurePaint.measureText(ellipsis)
        val targetWidth = (maxWidth - ellipsisWidth).coerceAtLeast(180f)

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

        return if (candidate.isNotEmpty()) "$candidate$ellipsis" else fallback
    }

    private fun buildLeftWingTitle(rawTitle: String): String {
        if (cachedRawLeftTitle == rawTitle && cachedCleanLeftTitle.isNotEmpty()) {
            return cachedCleanLeftTitle
        }
        val base = simplifyMetadataText(rawTitle)
        val fitted = if (base.isEmpty()) {
            "Lyrics"
        } else {
            truncateToVisualWidth(base, LEFT_WING_MAX_WIDTH, "Lyrics")
        }
        cachedRawLeftTitle = rawTitle
        cachedCleanLeftTitle = fitted
        return fitted
    }

    private fun buildCleanArtist(rawArtist: String, fallback: String): String {
        val cleanedRaw = rawArtist.trim()
        if (cleanedRaw.isEmpty()) return fallback
        if (cachedRawRightArtist == cleanedRaw && cachedCleanRightArtist.isNotEmpty()) {
            return cachedCleanRightArtist
        }
        val primary = cleanedRaw.split(",", "&", " x ", " X ")
            .firstOrNull()?.trim().orEmpty()
        val simplified = simplifyMetadataText(primary.ifEmpty { cleanedRaw })
        val fitted = if (simplified.isEmpty()) {
            fallback
        } else {
            truncateToVisualWidth(simplified, RIGHT_WING_MAX_WIDTH, fallback)
        }
        cachedRawRightArtist = cleanedRaw
        cachedCleanRightArtist = fitted
        return fitted
    }

    private fun formatCountdownMmSs(seconds: Long): String {
        val safeSec = seconds.coerceAtLeast(0L)
        val min = safeSec / 60L
        val sec = safeSec % 60L
        return String.format(Locale.US, "-%02d:%02d", min, sec)
    }

    private fun formatElapsedDuration(posMs: Long, durationMs: Long): String {
        val posSec = (posMs.coerceAtLeast(0L) / 1000L)
        val pMin = posSec / 60L
        val pSec = posSec % 60L
        if (durationMs <= 0L) {
            return String.format(Locale.US, "%02d:%02d", pMin, pSec)
        }
        val durSec = (durationMs.coerceAtLeast(0L) / 1000L)
        val dMin = durSec / 60L
        val dSec = durSec % 60L
        return String.format(Locale.US, "%02d:%02d / %02d:%02d", pMin, pSec, dMin, dSec)
    }

    private fun computeProgressBucket(posMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val fraction = (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        // 10 step discreti al 10%: bucket 0..9 -> chiave miui.focus.pic_progress_10..100
        return (fraction * (PROGRESS_BUCKET_COUNT - 1)).roundToInt().coerceIn(0, PROGRESS_BUCKET_COUNT - 1)
    }

    fun computeRightWingState(
        artist: String,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        syncOffsetMs: Long,
        showSyncEvent: Boolean,
        sourceLabel: String
    ): WingState {
        // 1. Priorità Eventi Istantanei (Sync modificato entro 2.0s)
        if (showSyncEvent) {
            val sign = if (syncOffsetMs >= 0L) "+" else ""
            val label = String.format(Locale.US, "Sync %s%.1fs", sign, syncOffsetMs / 1000f)
            return WingState(text = label, iconKey = PIC_WAVE, progressBucket = -1)
        }

        // 2. Stato Pausa
        if (!isPlaying) {
            return WingState(text = "In pausa", iconKey = PIC_PAUSE, progressBucket = -1)
        }

        val safePos = posMs.coerceAtLeast(0L)
        val cleanSource = sourceLabel.ifBlank { "Hi-Fi" }

        // 3. Primi 6 secondi: Artista pulito + onda audio
        if (safePos < 6_000L) {
            val artistLabel = buildCleanArtist(artist, fallback = cleanSource)
            return WingState(text = artistLabel, iconKey = PIC_WAVE, progressBucket = -1)
        }

        // 4. Ultimi 15 secondi (se durata > 20s): countdown finale al secondo + anello progresso
        val remainingMs = if (durationMs > 0L) (durationMs - safePos).coerceAtLeast(0L) else -1L
        if (durationMs > 20_000L && remainingMs in 0L..15_000L) {
            val sec = (remainingMs / 1000L).coerceIn(0L, 15L)
            val bucket = computeProgressBucket(safePos, durationMs)
            val stepPct = (bucket + 1) * 10
            return WingState(
                text = formatCountdownMmSs(sec),
                iconKey = "miui.focus.pic_progress_$stepPct",
                progressBucket = bucket
            )
        }

        // 5. Tra 6s e 30s: countdown a step di 5 secondi + anello progresso
        if (safePos < 30_000L) {
            return if (durationMs > 0L) {
                val steppedSec = ((remainingMs / 1000L) / 5L) * 5L
                val bucket = computeProgressBucket(safePos, durationMs)
                val stepPct = (bucket + 1) * 10
                WingState(
                    text = formatCountdownMmSs(steppedSec),
                    iconKey = "miui.focus.pic_progress_$stepPct",
                    progressBucket = bucket
                )
            } else {
                WingState(text = cleanSource, iconKey = PIC_WAVE, progressBucket = -1)
            }
        }

        // 6. Dopo 30s: alterna ogni 30s Qualità/Sorgente <-> Countdown a step 5s
        val windowA = ((safePos - 30_000L) / 30_000L) % 2L == 0L
        return if (windowA || durationMs <= 0L) {
            WingState(text = cleanSource, iconKey = PIC_WAVE, progressBucket = -1)
        } else {
            val steppedSec = ((remainingMs / 1000L) / 5L) * 5L
            val bucket = computeProgressBucket(safePos, durationMs)
            val stepPct = (bucket + 1) * 10
            WingState(
                text = formatCountdownMmSs(steppedSec),
                iconKey = "miui.focus.pic_progress_$stepPct",
                progressBucket = bucket
            )
        }
    }

    private fun resolveDisplayLines(
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        title: String,
        artist: String,
        posMs: Long,
        durationMs: Long,
        sourceLabel: String,
        hasSyncedLyrics: Boolean,
        isPlaying: Boolean
    ): Triple<String, String, String> {
        if (!hasSyncedLyrics) {
            val cleanTitle = cleanLine(title).ifBlank { "DynamicLyrics" }
            val cleanArtist = cleanLine(artist).ifBlank { sourceLabel.ifBlank { "Musica attiva" } }
            val progressTime = formatElapsedDuration(posMs, durationMs)
            val qualityBadge = if (isPlaying) {
                "${sourceLabel.ifBlank { "Hi-Fi" }}  •  $progressTime"
            } else {
                "In pausa  •  ${sourceLabel.ifBlank { "Hi-Fi" }}  •  $progressTime"
            }
            return Triple(cleanTitle, cleanArtist, qualityBadge)
        }

        val prev = cleanLine(prevLyric)
        val curr = cleanLine(currentLine?.fullText.orEmpty())
        val next = cleanLine(nextLyric1)

        if (!isPlaying && curr.isBlank()) {
            return Triple(cleanLine(title).ifBlank { "In pausa" }, cleanLine(artist), "Riproduzione in pausa")
        }
        if (curr.isBlank()) {
            return Triple(cleanLine(title).ifBlank { "In attesa del testo…" }, cleanLine(artist), next)
        }
        return Triple(prev, curr, next)
    }

    private fun appendStyledLine(
        sb: SpannableStringBuilder,
        text: String,
        isPrimary: Boolean,
        color: Int,
        scale: Float = 1.0f
    ) {
        if (text.isBlank()) return
        if (sb.isNotEmpty()) sb.append("\n")
        val start = sb.length
        sb.append(text)
        val end = sb.length
        if (isPrimary) {
            sb.setSpan(TypefaceSpan("sans-serif-medium"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.setSpan(TypefaceSpan("sans-serif"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (scale != 1.0f) {
            sb.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatStyledExpandedLyrics(
        prev: String,
        curr: String,
        next: String,
        accentColor: Int,
        hasSyncedLyrics: Boolean
    ): CharSequence {
        val sb = SpannableStringBuilder()
        if (!hasSyncedLyrics) {
            appendStyledLine(sb, prev, isPrimary = true, color = Color.WHITE, scale = 1.06f)
            if (curr.isNotBlank()) appendStyledLine(sb, curr, isPrimary = false, color = Color.argb(190, 255, 255, 255), scale = 0.92f)
            if (next.isNotBlank()) appendStyledLine(sb, next, isPrimary = true, color = accentColor, scale = 0.86f)
        } else {
            if (prev.isNotBlank()) appendStyledLine(sb, prev, isPrimary = false, color = Color.argb(115, 255, 255, 255), scale = 0.86f)
            appendStyledLine(sb, curr, isPrimary = true, color = accentColor, scale = 1.08f)
            if (next.isNotBlank()) appendStyledLine(sb, next, isPrimary = false, color = Color.argb(115, 255, 255, 255), scale = 0.86f)
        }
        return sb
    }

    private fun buildExpandedRemoteViews(
        vinyl: VinylAssets,
        prev: String,
        curr: String,
        next: String,
        accentColor: Int,
        hasSyncedLyrics: Boolean,
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

        if (!hasSyncedLyrics) {
            // Modalità senza testi sincronizzati: Vinile HD | Titolo / Artista / Badge Qualità + Avanzamento | Play/Pausa
            val styledTitle = SpannableStringBuilder(prev).apply {
                setSpan(TypefaceSpan("sans-serif-medium"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(1.18f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            rv.setViewVisibility(R.id.tv_lyric_prev, View.VISIBLE)
            rv.setTextColor(R.id.tv_lyric_prev, Color.WHITE)
            rv.setTextViewText(R.id.tv_lyric_prev, styledTitle)

            if (curr.isNotBlank()) {
                val styledArtist = SpannableStringBuilder(curr).apply {
                    setSpan(RelativeSizeSpan(0.88f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                rv.setViewVisibility(R.id.tv_lyric_curr, View.VISIBLE)
                rv.setTextColor(R.id.tv_lyric_curr, Color.argb(205, 255, 255, 255))
                rv.setTextViewText(R.id.tv_lyric_curr, styledArtist)
            } else {
                rv.setViewVisibility(R.id.tv_lyric_curr, View.GONE)
            }

            if (next.isNotBlank()) {
                val styledBadge = SpannableStringBuilder(next).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                rv.setViewVisibility(R.id.tv_lyric_next, View.VISIBLE)
                rv.setTextColor(R.id.tv_lyric_next, accentColor)
                rv.setTextViewText(R.id.tv_lyric_next, styledBadge)
            } else {
                rv.setViewVisibility(R.id.tv_lyric_next, View.GONE)
            }
        } else {
            if (prev.isNotBlank()) {
                rv.setViewVisibility(R.id.tv_lyric_prev, View.VISIBLE)
                rv.setTextColor(R.id.tv_lyric_prev, Color.argb(122, 255, 255, 255))
                rv.setTextViewText(R.id.tv_lyric_prev, prev)
            } else {
                rv.setViewVisibility(R.id.tv_lyric_prev, View.GONE)
            }

            rv.setViewVisibility(R.id.tv_lyric_curr, View.VISIBLE)
            rv.setTextViewText(R.id.tv_lyric_curr, curr)
            rv.setTextColor(R.id.tv_lyric_curr, accentColor)

            if (next.isNotBlank()) {
                rv.setViewVisibility(R.id.tv_lyric_next, View.VISIBLE)
                rv.setTextColor(R.id.tv_lyric_next, Color.argb(122, 255, 255, 255))
                rv.setTextViewText(R.id.tv_lyric_next, next)
            } else {
                rv.setViewVisibility(R.id.tv_lyric_next, View.GONE)
            }
        }

        rv.setImageViewBitmap(
            R.id.btn_play_pause,
            if (isPlaying) pauseButtonBitmap else playButtonBitmap
        )
        rv.setOnClickPendingIntent(R.id.btn_play_pause, playPausePendingIntent)

        return rv
    }

    private fun buildIslandJson(
        leftText: String,
        rightText: String,
        rightIconKey: String,
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
                            put("pic", PIC_DISC)
                        }
                    )
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", leftText)
                            put("narrowFont", true)
                            put("showHighlightColor", false)
                        }
                    )
                }
            )
            put(
                "imageTextInfoRight",
                JSONObject().apply {
                    put("type", 2)
                    put(
                        "picInfo",
                        JSONObject().apply {
                            put("type", 1)
                            put("pic", rightIconKey)
                        }
                    )
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", rightText)
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
                    put("pic", PIC_DISC)
                }
            )
            put(
                "combinePicInfo",
                JSONObject().apply {
                    put(
                        "picInfo",
                        JSONObject().apply {
                            put("type", 1)
                            put("pic", rightIconKey)
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
        leftText: String,
        rightText: String,
        rightIconKey: String,
        accentHex: String
    ): Pair<String, String> {
        val key = "$leftText|$rightText|$rightIconKey|$accentHex"
        val existing = cachedFocusJsonPair
        if (existing != null && cachedFocusJsonKey == key) {
            return existing
        }

        val islandJson = buildIslandJson(leftText, rightText, rightIconKey, accentHex)
        val combinedTicker = "$leftText • $rightText"

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "bitchord_lyrics")
            put("updatable", true)
            put("reopen", true)
            put("islandFirstFloat", false)
            put("enableFloat", false)
            put("aodTitle", combinedTicker)
            put("aodPic", PIC_DISC)
            put("ticker", combinedTicker)
            put("tickerPic", rightIconKey)
            put("param_island", islandJson)
        }

        val focusJson = JSONObject().apply {
            put("param_v2", paramV2)
        }.toString()

        val customFocusJson = JSONObject().apply {
            put("ticker", combinedTicker)
            put("tickerPic", rightIconKey)
            put("aodTitle", combinedTicker)
            put("aodPic", PIC_DISC)
            put("updatable", true)
            put("reopen", true)
            put("enableFloat", false)
            put("islandFirstFloat", false)
            put("param_island", islandJson)
        }.toString()

        val pair = focusJson to customFocusJson
        cachedFocusJsonKey = key
        cachedFocusJsonPair = pair
        return pair
    }

    private fun resolveRightWingIcon(
        wingState: WingState,
        accentColor: Int
    ): Icon {
        return when {
            wingState.iconKey == PIC_PAUSE -> wingPauseIcon
            wingState.progressBucket in 0 until PROGRESS_BUCKET_COUNT -> {
                getOrCreateProgressRingIcons(accentColor)[wingState.progressBucket]
            }
            else -> wingWaveIcon
        }
    }

    private fun buildNotificationInternal(
        leftText: String,
        wingState: WingState,
        accentColor: Int,
        prevText: String,
        currText: String,
        nextText: String,
        cover: Bitmap?,
        hasSyncedLyrics: Boolean,
        isPlaying: Boolean
    ): Notification {
        val accentHex = String.format("#%06X", 0xFFFFFF and accentColor)
        val expandedBody = formatStyledExpandedLyrics(prevText, currText, nextText, accentColor, hasSyncedLyrics)
        val vinyl = getOrCreateVinylAssets(cover, accentColor)
        val smallIcon = if (isPlaying) smallWaveIcon else smallPauseIcon
        val rightIcon = resolveRightWingIcon(wingState, accentColor)

        val expandedRv = buildExpandedRemoteViews(
            vinyl = vinyl,
            prev = prevText,
            curr = currText,
            next = nextText,
            accentColor = accentColor,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )
        val (focusJson, customFocusJson) = getOrBuildFocusParamJson(
            leftText = leftText,
            rightText = wingState.text,
            rightIconKey = wingState.iconKey,
            accentHex = accentHex
        )

        val focusPics = Bundle().apply {
            putParcelable(PIC_DISC, vinyl.icon)
            putParcelable(PIC_SMALL, smallIcon)
            putParcelable(wingState.iconKey, rightIcon)
        }

        val extras = Bundle().apply {
            putString("miui.focus.param", focusJson)
            putString("miui.focus.param.custom", customFocusJson)
            putBundle("miui.focus.pics", focusPics)
            putParcelable("miui.focus.rv", expandedRv)
            putParcelable("miui.focus.rv.island.expand", expandedRv)
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setLargeIcon(vinyl.baseDisc)
            .setContentTitle(leftText)
            .setContentText(expandedBody)
            .setSubText(wingState.text)
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

    private fun resolveDominantAccentColor(cover: Bitmap?, palette: CoverPalette): Int {
        val paletteAccent = palette.accent.toArgb()
        val defaultAccent = CoverPalette().accent.toArgb()
        if (paletteAccent != defaultAccent || cover == null || cover.isRecycled) {
            return boostAccentBrightness(paletteAccent)
        }
        val sampled = sampleDominantCoverColor(cover) ?: paletteAccent
        return boostAccentBrightness(sampled)
    }

    private fun sampleDominantCoverColor(cover: Bitmap): Int? {
        return try {
            if (cover.isRecycled || cover.width <= 0 || cover.height <= 0) return null
            val mini = Bitmap.createScaledBitmap(cover, 12, 12, true)
            var bestColor = 0
            var bestScore = -1f
            for (y in 0 until mini.height) {
                for (x in 0 until mini.width) {
                    val px = mini.getPixel(x, y)
                    Color.colorToHSV(px, hsvBuffer)
                    val sat = hsvBuffer[1]
                    val valB = hsvBuffer[2]
                    val score = sat * 1.6f + if (valB in 0.25f..0.92f) 0.4f else 0f
                    if (score > bestScore) {
                        bestScore = score
                        bestColor = px
                    }
                }
            }
            if (mini !== cover) mini.recycle()
            if (bestScore > 0f) bestColor else null
        } catch (_: Throwable) {
            null
        }
    }

    fun buildNotification(
        title: String,
        artist: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        syncOffsetMs: Long,
        showSyncEvent: Boolean,
        sourceLabel: String,
        hasSyncedLyrics: Boolean
    ): Notification {
        val leftText = buildLeftWingTitle(title)
        val wingState = computeRightWingState(
            artist = artist,
            isPlaying = isPlaying,
            posMs = posMs,
            durationMs = durationMs,
            syncOffsetMs = syncOffsetMs,
            showSyncEvent = showSyncEvent,
            sourceLabel = sourceLabel
        )
        val accentColor = resolveDominantAccentColor(cover, palette)
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            artist = artist,
            posMs = posMs,
            durationMs = durationMs,
            sourceLabel = sourceLabel,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )

        return buildNotificationInternal(
            leftText = leftText,
            wingState = wingState,
            accentColor = accentColor,
            prevText = prevText,
            currText = currText,
            nextText = nextText,
            cover = cover,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )
    }

    fun markForegroundPosted(
        title: String,
        artist: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        syncOffsetMs: Long,
        showSyncEvent: Boolean,
        sourceLabel: String,
        hasSyncedLyrics: Boolean
    ) {
        val leftText = buildLeftWingTitle(title)
        val wingState = computeRightWingState(
            artist = artist,
            isPlaying = isPlaying,
            posMs = posMs,
            durationMs = durationMs,
            syncOffsetMs = syncOffsetMs,
            showSyncEvent = showSyncEvent,
            sourceLabel = sourceLabel
        )
        val accentColor = resolveDominantAccentColor(cover, palette)
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            artist = artist,
            posMs = posMs,
            durationMs = durationMs,
            sourceLabel = sourceLabel,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )
        val coverId = System.identityHashCode(cover)
        lastFullStateKey =
            "$leftText|${wingState.text}|${wingState.iconKey}|$isPlaying|$accentColor|$coverId|$hasSyncedLyrics|$prevText|$currText|$nextText"
    }

    fun resetCache() {
        lastFullStateKey = ""
    }

    fun update(
        title: String,
        artist: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        syncOffsetMs: Long,
        showSyncEvent: Boolean,
        sourceLabel: String,
        hasSyncedLyrics: Boolean
    ) {
        val isScreenOn = powerManager?.isInteractive ?: true
        if (!isScreenOn) return

        val leftText = buildLeftWingTitle(title)
        val wingState = computeRightWingState(
            artist = artist,
            isPlaying = isPlaying,
            posMs = posMs,
            durationMs = durationMs,
            syncOffsetMs = syncOffsetMs,
            showSyncEvent = showSyncEvent,
            sourceLabel = sourceLabel
        )
        val accentColor = resolveDominantAccentColor(cover, palette)
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            artist = artist,
            posMs = posMs,
            durationMs = durationMs,
            sourceLabel = sourceLabel,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )

        val coverId = System.identityHashCode(cover)
        val fullStateKey =
            "$leftText|${wingState.text}|${wingState.iconKey}|$isPlaying|$accentColor|$coverId|$hasSyncedLyrics|$prevText|$currText|$nextText"

        if (fullStateKey == lastFullStateKey) return
        lastFullStateKey = fullStateKey

        try {
            manager.notify(
                NOTIFICATION_ID,
                buildNotificationInternal(
                    leftText = leftText,
                    wingState = wingState,
                    accentColor = accentColor,
                    prevText = prevText,
                    currText = currText,
                    nextText = nextText,
                    cover = cover,
                    hasSyncedLyrics = hasSyncedLyrics,
                    isPlaying = isPlaying
                )
            )
        } catch (t: Throwable) {
            Log.e("HyperIslandNotifier", "Notification update failed: ${t.message}", t)
        }
    }

    fun cancel() {
        lastFullStateKey = ""
        manager.cancel(NOTIFICATION_ID)
    }

    private fun boostAccentBrightness(color: Int): Int {
        Color.colorToHSV(color, hsvBuffer)
        if (hsvBuffer[1] < 0.14f) {
            return Color.rgb(228, 236, 255)
        }
        hsvBuffer[2] = hsvBuffer[2].coerceAtLeast(0.94f)
        hsvBuffer[1] = hsvBuffer[1].coerceIn(0.45f, 0.90f)
        return Color.HSVToColor(255, hsvBuffer)
    }

    private fun getOrCreateProgressRingIcons(accentColor: Int): Array<Icon> {
        val existing = cachedProgressRingIcons
        if (existing != null && cachedRingAccent == accentColor) {
            return existing
        }

        val generated = Array(PROGRESS_BUCKET_COUNT) { bucket ->
            val fraction = (bucket + 1) / PROGRESS_BUCKET_COUNT.toFloat()
            Icon.createWithBitmap(renderProgressRingBitmap(WING_ICON_SIZE, fraction, accentColor))
        }
        cachedRingAccent = accentColor
        cachedProgressRingIcons = generated
        return generated
    }

    private fun renderProgressRingBitmap(size: Int, progressFraction: Float, accentColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val strokeW = size * 0.115f
        val pad = strokeW * 0.95f
        val oval = RectF(pad, pad, size - pad, size - pad)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = strokeW
        }
        canvas.drawOval(oval, trackPaint)

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            strokeCap = Paint.Cap.ROUND
        }
        val sweepDeg = (progressFraction.coerceIn(0.08f, 1f) * 360f)
        canvas.drawArc(oval, -90f, sweepDeg, false, arcPaint)
        return bmp
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
            renderRotatedDisc(baseDisc, SPIN_FRAME_SIZE, angle)
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
            val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
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

    private fun renderWaveBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val heights = floatArrayOf(0.42f, 0.92f, 0.56f)
        val pad = size * 0.18f
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

    private fun renderPauseGlyphBitmap(size: Int): Bitmap {
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
