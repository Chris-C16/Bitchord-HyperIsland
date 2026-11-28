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
import android.graphics.LinearGradient
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
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class HyperIslandNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "bitchord_lyrics_live_pill"
        const val NOTIFICATION_ID = 3601

        private const val MAX_PAGE_CHARS = 10
        private const val EXPANDED_BG_WIDTH = 240
        private const val EXPANDED_BG_HEIGHT = 96
        private const val PHASE_1_END_MS = 4_500L
        private const val PHASE_1_GIANT_STEP_MS = 2_250L
        private const val PHASE_2_STEP_MS = 2_000L
        private const val PHASE_2_SINGLE_PAGE_MS = 2_500L
        private const val PHASE_3_DURATION_MS = 4_500L

        private val REGEX_REAL_SPEC = Regex("""\d+\s*(?:k|khz|bit|b)\b""", RegexOption.IGNORE_CASE)

        private const val TRAILING_JUNK_CHARS = "([{-–—/:,&+.|•"

        // Parametri rotazione 360° del vinile dentro RemoteViews (entro il limite Binder ~1MB)
        private const val SPIN_FRAME_COUNT = 16
        private const val SPIN_FRAME_SIZE = 72
        private const val SPIN_INTERVAL_MS = 125L
        private const val WING_ICON_SIZE = 96

        private const val PROGRESS_STEP_MAX = 10

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
        private val REGEX_FEAT = Regex("""\s+(?:feat\.?|ft\.?|featuring|prod\.?)\b.*$""", RegexOption.IGNORE_CASE)
        private val REGEX_DASH_SUFFIX = Regex("""\s+[-–—]\s+.*$""")
        private val REGEX_ARTIST_SPLIT = Regex("""(?i)\s*(?:,|&|/|\s+x\s+|\s+(?:feat\.?|ft\.?|featuring|prod\.?)\b)\s*""")
    }

    data class DualWingState(
        val leftText: String,
        val leftIconKey: String,
        val rightText: String,
        val rightIconKey: String,
        val progressStep: Int
    )

    private data class ExpandedTwoToneTheme(
        val bgStartColor: Int,
        val bgEndColor: Int,
        val activeLyricColor: Int
    )

    private data class PhaseBounds(
        val cleanTitle: String,
        val cleanArtist: String,
        val titlePages: List<String>,
        val artistPages: List<String>,
        val skipPhase2: Boolean,
        val phase2PageCount: Int,
        val phase2EndMs: Long,
        val phase3EndMs: Long
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

    private val bitmapFilterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rotateMatrix = Matrix()
    private val hsvBuffer = FloatArray(3)

    private var cachedCoverRef: Bitmap? = null
    private var cachedDiscAccent: Int = 0
    private var cachedVinylAssets: VinylAssets? = null

    private var cachedThemeCoverRef: Bitmap? = null
    private var cachedTwoToneTheme: ExpandedTwoToneTheme? = null

    // Cache anelli di progresso HD indicizzati per albumArtHash + step 0..10
    private val progressRingIconCache = LruCache<String, Icon>(44)
    // Cache sfondi sfumati pillola espansa indicizzati per albumArtHash + colori
    private val expandedBgCache = LruCache<String, Bitmap>(16)

    private var lastSpinStep: Int = 0
    private var cachedPausedStep: Int = -1
    private var cachedPausedBase: Bitmap? = null
    private var cachedPausedDisc: Bitmap? = null

    // Cache pulizia titolo/artista
    private var cachedRawTitle: String? = null
    private var cachedCleanTitle: String = ""
    private var cachedRawArtist: String? = null
    private var cachedCleanArtist: String = ""

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

    fun getCleanTitle(rawTitle: String): String {
        if (cachedRawTitle == rawTitle && cachedCleanTitle.isNotEmpty()) {
            return cachedCleanTitle
        }
        val clean = cleanLine(rawTitle)
            .replace(REGEX_LEADING_EXPLICIT_EMOJI, "")
            .replace(REGEX_LEADING_EXPLICIT_TAG, "")
            .trim()
        val result = if (clean.isEmpty()) {
            ""
        } else {
            val stripped = clean
                .replace(REGEX_PARENS, "")
                .replace(REGEX_UNCLOSED_PAREN, "")
                .replace(REGEX_FEAT, "")
                .replace(REGEX_DASH_SUFFIX, "")
                .trim()
            val base = if (stripped.isNotEmpty()) stripped else clean
            val trimmed = sanitizeTrailingJunk(cleanLine(base))
            if (trimmed.isNotEmpty()) trimmed else clean
        }
        cachedRawTitle = rawTitle
        cachedCleanTitle = result
        return result
    }

    fun getCleanArtist(rawArtist: String): String {
        val raw = rawArtist.trim()
        if (cachedRawArtist == raw) {
            return cachedCleanArtist
        }
        val clean = cleanLine(raw)
            .replace(REGEX_PARENS, "")
            .replace(REGEX_UNCLOSED_PAREN, "")
            .replace(REGEX_FEAT, "")
            .trim()
        val primary = clean.split(REGEX_ARTIST_SPLIT, limit = 2)
            .firstOrNull()?.trim().orEmpty()
        val finalClean = sanitizeTrailingJunk(cleanLine(primary.ifEmpty { clean }))
        cachedRawArtist = raw
        cachedCleanArtist = finalClean
        return finalClean
    }

    fun splitIntoWordPages(text: String, maxChars: Int = MAX_PAGE_CHARS): List<String> {
        val normalized = cleanLine(text)
        if (normalized.isEmpty()) return emptyList()
        val limit = maxChars.coerceAtLeast(4)
        val rawWords = normalized.split(' ').filter { it.isNotEmpty() }
        val words = rawWords.map { token ->
            val trimmed = token.trimEnd(',', ';', ':')
            trimmed.ifEmpty { token }
        }

        val pages = mutableListOf<String>()
        val current = StringBuilder()

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                pages += current.toString()
                current.clear()
            }
        }

        for (word in words) {
            if (word.length > limit) {
                flushCurrent()
                if (word.length <= limit * 2) {
                    val mid = (word.length + 1) / 2
                    pages += word.substring(0, mid)
                    pages += word.substring(mid)
                } else {
                    var idx = 0
                    while (idx < word.length) {
                        val end = (idx + limit).coerceAtMost(word.length)
                        pages += word.substring(idx, end)
                        idx = end
                    }
                }
                continue
            }

            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= limit) {
                current.append(' ').append(word)
            } else {
                flushCurrent()
                current.append(word)
            }
        }
        flushCurrent()
        return pages
    }

    private fun splitMediumTitleSymmetrically(cleanTitle: String): Pair<String, String> {
        if (!cleanTitle.contains(' ')) {
            val mid = (cleanTitle.length + 1) / 2
            return cleanTitle.substring(0, mid) to cleanTitle.substring(mid)
        }
        val mid = cleanTitle.length / 2
        var bestSpace = -1
        var bestMaxWing = Int.MAX_VALUE
        var bestDist = Int.MAX_VALUE
        for (i in 1 until cleanTitle.lastIndex) {
            if (cleanTitle[i] == ' ') {
                val leftLen = i
                val rightLen = cleanTitle.length - 1 - i
                val maxWing = max(leftLen, rightLen)
                val dist = kotlin.math.abs(i - mid)
                if (maxWing < bestMaxWing || (maxWing == bestMaxWing && dist < bestDist)) {
                    bestMaxWing = maxWing
                    bestDist = dist
                    bestSpace = i
                }
            }
        }
        if (bestSpace <= 0) {
            val splitMid = (cleanTitle.length + 1) / 2
            return cleanTitle.substring(0, splitMid) to cleanTitle.substring(splitMid)
        }
        val left = cleanTitle.substring(0, bestSpace).trimEnd(',', ';', ':').trim()
        val right = cleanTitle.substring(bestSpace + 1).trim()
        return left to right
    }

    fun computeProgressStep(posMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val fraction = (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        return (fraction * PROGRESS_STEP_MAX).roundToInt().coerceIn(0, PROGRESS_STEP_MAX)
    }

    fun hasRealAudioSpec(audioSpecBadge: String): Boolean {
        return audioSpecBadge.isNotBlank() && REGEX_REAL_SPEC.containsMatchIn(audioSpecBadge)
    }

    private fun computePhaseBounds(
        title: String,
        artist: String,
        codecFallback: String,
        hasRealSpec: Boolean
    ): PhaseBounds {
        val cleanTitle = getCleanTitle(title).ifBlank { "Lyrics" }
        val cleanArtist = getCleanArtist(artist).ifBlank { codecFallback.ifBlank { "Music" } }
        val titlePages = splitIntoWordPages(cleanTitle, MAX_PAGE_CHARS)
        val artistPages = splitIntoWordPages(cleanArtist, MAX_PAGE_CHARS)
        val skipPhase2 = cleanTitle.length <= 9 && cleanArtist.length <= 9
        val phase2PageCount = if (skipPhase2) 0 else max(artistPages.size, titlePages.size).coerceAtLeast(1)
        val phase2DurationMs = when {
            skipPhase2 -> 0L
            phase2PageCount <= 1 -> PHASE_2_SINGLE_PAGE_MS
            else -> phase2PageCount * PHASE_2_STEP_MS
        }
        val phase2EndMs = PHASE_1_END_MS + phase2DurationMs
        val phase3EndMs = phase2EndMs + if (hasRealSpec) PHASE_3_DURATION_MS else 0L
        return PhaseBounds(
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist,
            titlePages = titlePages,
            artistPages = artistPages,
            skipPhase2 = skipPhase2,
            phase2PageCount = phase2PageCount,
            phase2EndMs = phase2EndMs,
            phase3EndMs = phase3EndMs
        )
    }

    fun normalPhase3EndMs(title: String, artist: String, hasRealSpec: Boolean): Long {
        return computePhaseBounds(title, artist, "Music", hasRealSpec).phase3EndMs
    }

    fun nextPhaseTransitionPosMs(
        rawPosMs: Long,
        title: String,
        artist: String,
        hasRealSpec: Boolean
    ): Long {
        val bounds = computePhaseBounds(title, artist, "Music", hasRealSpec)
        val pos = rawPosMs.coerceAtLeast(0L)
        if (pos < PHASE_1_END_MS) {
            return if (
                bounds.cleanTitle.length > 20 &&
                bounds.titlePages.size >= 3 &&
                pos < PHASE_1_GIANT_STEP_MS
            ) {
                PHASE_1_GIANT_STEP_MS
            } else {
                PHASE_1_END_MS
            }
        }
        if (!bounds.skipPhase2 && pos < bounds.phase2EndMs) {
            if (bounds.phase2PageCount > 1) {
                val nextPagePos = PHASE_1_END_MS +
                    (((pos - PHASE_1_END_MS) / PHASE_2_STEP_MS) + 1L) * PHASE_2_STEP_MS
                return min(nextPagePos, bounds.phase2EndMs)
            }
            return bounds.phase2EndMs
        }
        if (hasRealSpec && pos < bounds.phase3EndMs) {
            return bounds.phase3EndMs
        }
        return Long.MAX_VALUE
    }

    fun computeDualWingState(
        title: String,
        artist: String,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        audioCodecBadge: String,
        audioSpecBadge: String,
        forcePhase3Flash: Boolean = false
    ): DualWingState {
        // 1. Stato Pausa bilanciato (!isPlaying): [Vinile] + 1a pagina titolo (max 9 car.) | "In pausa" + [Pausa]
        if (!isPlaying) {
            val cleanPauseTitle = getCleanTitle(title).ifBlank { "Lyrics" }
            val pauseLeftTitle = splitIntoWordPages(cleanPauseTitle, 9)
                .firstOrNull()?.takeIf { it.isNotBlank() }
                ?: cleanPauseTitle.take(9).ifBlank { "Lyrics" }
            return DualWingState(
                leftText = pauseLeftTitle,
                leftIconKey = PIC_DISC,
                rightText = "In pausa",
                rightIconKey = PIC_PAUSE,
                progressStep = -1
            )
        }

        val codecLabel = audioCodecBadge.ifBlank { "FLAC" }
        val realSpecLabel = audioSpecBadge.takeIf { hasRealAudioSpec(it) }.orEmpty()
        val hasRealSpec = realSpecLabel.isNotEmpty()
        val safePos = posMs.coerceAtLeast(0L)
        val step = computeProgressStep(safePos, durationMs)
        val ringKey = "miui.focus.pic_progress_$step"

        // Flash one-shot 4.5s quando AccessibilityService rileva kHz/Bit reali a brano iniziato
        if (forcePhase3Flash && hasRealSpec) {
            return DualWingState(
                leftText = codecLabel,
                leftIconKey = PIC_DISC,
                rightText = realSpecLabel,
                rightIconKey = ringKey,
                progressStep = step
            )
        }

        val bounds = computePhaseBounds(title, artist, codecLabel, hasRealSpec)

        // 2. Fase 1 — Attacco Simmetrico del Titolo a Parole Intere (0 <= posMs < 4_500)
        if (safePos < PHASE_1_END_MS) {
            val firstArtistPage = bounds.artistPages.firstOrNull().orEmpty()
            val (leftText, rightText) = when {
                bounds.cleanTitle.length <= MAX_PAGE_CHARS -> {
                    bounds.cleanTitle to firstArtistPage
                }
                bounds.cleanTitle.length <= MAX_PAGE_CHARS * 2 -> {
                    splitMediumTitleSymmetrically(bounds.cleanTitle)
                }
                else -> {
                    if (bounds.titlePages.size <= 2) {
                        val p0 = bounds.titlePages.firstOrNull().orEmpty()
                        val p1 = bounds.titlePages.getOrNull(1) ?: firstArtistPage
                        p0 to p1
                    } else {
                        val slot = if (safePos < PHASE_1_GIANT_STEP_MS) 0 else 1
                        val baseIdx = slot * 2
                        val leftPage = bounds.titlePages.getOrNull(baseIdx)
                            ?: bounds.titlePages.lastOrNull().orEmpty()
                        val rightPage = bounds.titlePages.getOrNull(baseIdx + 1)
                            ?: firstArtistPage
                        leftPage to rightPage
                    }
                }
            }
            return DualWingState(
                leftText = leftText,
                leftIconKey = PIC_DISC,
                rightText = rightText,
                rightIconKey = PIC_WAVE,
                progressStep = -1
            )
        }

        // 3. Fase 2 — Artista (Sx) + Titolo (Dx) a Parole Intere (1 solo giro, saltata se entrambi <= 9 car.)
        if (!bounds.skipPhase2 && safePos < bounds.phase2EndMs) {
            val pageStep = ((safePos - PHASE_1_END_MS) / PHASE_2_STEP_MS).toInt().coerceAtLeast(0)
            val leftPage = if (bounds.artistPages.isEmpty()) {
                bounds.cleanArtist.take(MAX_PAGE_CHARS)
            } else {
                bounds.artistPages[pageStep.coerceAtMost(bounds.artistPages.lastIndex)]
            }
            val rightPage = if (bounds.titlePages.isEmpty()) {
                bounds.cleanTitle.take(MAX_PAGE_CHARS)
            } else {
                bounds.titlePages[pageStep.coerceAtMost(bounds.titlePages.lastIndex)]
            }
            return DualWingState(
                leftText = leftPage,
                leftIconKey = PIC_DISC,
                rightText = rightPage,
                rightIconKey = ringKey,
                progressStep = step
            )
        }

        // 4. Fase 3 — Doppia Ala Qualità Audio (4.5s SOLO se audioSpecBadge reale con k/Bit presente)
        if (hasRealSpec && safePos < bounds.phase3EndMs) {
            return DualWingState(
                leftText = codecLabel,
                leftIconKey = PIC_DISC,
                rightText = realSpecLabel,
                rightIconKey = ringKey,
                progressStep = step
            )
        }

        // 5. Fase 4 — Riposo Minimal con Disco + Anello Colorato dalla Copertina
        return DualWingState(
            leftText = "",
            leftIconKey = PIC_DISC,
            rightText = "",
            rightIconKey = ringKey,
            progressStep = step
        )
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

    private fun formatCombinedQualityLabel(audioCodecBadge: String, audioSpecBadge: String): String {
        val codec = audioCodecBadge.ifBlank { "FLAC" }
        val realSpec = audioSpecBadge.takeIf { hasRealAudioSpec(it) }.orEmpty()
        return if (realSpec.isNotBlank() && !realSpec.equals(codec, ignoreCase = true)) {
            "$codec • $realSpec"
        } else {
            codec
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
        audioCodecBadge: String,
        audioSpecBadge: String,
        hasSyncedLyrics: Boolean,
        isPlaying: Boolean
    ): Triple<String, String, String> {
        if (!hasSyncedLyrics) {
            val cleanTitle = cleanLine(title).ifBlank { "DynamicLyrics" }
            val cleanArtist = cleanLine(artist).ifBlank { audioCodecBadge.ifBlank { "Musica attiva" } }
            val progressTime = formatElapsedDuration(posMs, durationMs)
            val badge = formatCombinedQualityLabel(audioCodecBadge, audioSpecBadge)
            val qualityLine = if (isPlaying) {
                "$badge  •  $progressTime"
            } else {
                "In pausa  •  $badge  •  $progressTime"
            }
            return Triple(cleanTitle, cleanArtist, qualityLine)
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
        albumArtHash: Int,
        theme: ExpandedTwoToneTheme,
        prev: String,
        curr: String,
        next: String,
        hasSyncedLyrics: Boolean,
        isPlaying: Boolean
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.island_lyrics_expanded)
        rv.setImageViewBitmap(
            R.id.iv_expanded_bg,
            getOrCreateExpandedBgBitmap(albumArtHash, theme)
        )

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

        val secondaryLineColor = Color.argb(179, 255, 255, 255) // #B3FFFFFF nitido sul gradiente

        if (!hasSyncedLyrics) {
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
                rv.setTextViewTextSize(R.id.tv_lyric_curr, TypedValue.COMPLEX_UNIT_SP, 14f)
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
                rv.setTextColor(R.id.tv_lyric_next, theme.activeLyricColor)
                rv.setTextViewText(R.id.tv_lyric_next, styledBadge)
            } else {
                rv.setViewVisibility(R.id.tv_lyric_next, View.GONE)
            }
        } else {
            if (prev.isNotBlank()) {
                rv.setViewVisibility(R.id.tv_lyric_prev, View.VISIBLE)
                rv.setTextColor(R.id.tv_lyric_prev, secondaryLineColor)
                rv.setTextViewText(R.id.tv_lyric_prev, prev)
            } else {
                rv.setViewVisibility(R.id.tv_lyric_prev, View.GONE)
            }

            val currSizeSp = if (curr.length > 28) 12f else 14f
            rv.setViewVisibility(R.id.tv_lyric_curr, View.VISIBLE)
            rv.setTextViewTextSize(R.id.tv_lyric_curr, TypedValue.COMPLEX_UNIT_SP, currSizeSp)
            rv.setTextViewText(R.id.tv_lyric_curr, curr)
            rv.setTextColor(R.id.tv_lyric_curr, theme.activeLyricColor)

            if (next.isNotBlank()) {
                rv.setViewVisibility(R.id.tv_lyric_next, View.VISIBLE)
                rv.setTextColor(R.id.tv_lyric_next, secondaryLineColor)
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
        wingState: DualWingState,
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
                            put("pic", wingState.leftIconKey)
                        }
                    )
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", wingState.leftText)
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
                            put("pic", wingState.rightIconKey)
                        }
                    )
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", wingState.rightText)
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
                    put("pic", wingState.leftIconKey)
                }
            )
            put(
                "combinePicInfo",
                JSONObject().apply {
                    put(
                        "picInfo",
                        JSONObject().apply {
                            put("type", 1)
                            put("pic", wingState.rightIconKey)
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
        wingState: DualWingState,
        fallbackTitle: String,
        accentHex: String
    ): Pair<String, String> {
        val key = "${wingState.leftText}|${wingState.rightText}|${wingState.leftIconKey}|${wingState.rightIconKey}|$accentHex"
        val existing = cachedFocusJsonPair
        if (existing != null && cachedFocusJsonKey == key) {
            return existing
        }

        val islandJson = buildIslandJson(wingState, accentHex)
        val combinedTicker = listOf(wingState.leftText, wingState.rightText)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .ifBlank { fallbackTitle }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "bitchord_lyrics")
            put("updatable", true)
            put("reopen", true)
            put("islandFirstFloat", false)
            put("enableFloat", false)
            put("aodTitle", combinedTicker)
            put("aodPic", wingState.leftIconKey)
            put("ticker", combinedTicker)
            put("tickerPic", wingState.rightIconKey)
            put("param_island", islandJson)
        }

        val focusJson = JSONObject().apply {
            put("param_v2", paramV2)
        }.toString()

        val customFocusJson = JSONObject().apply {
            put("ticker", combinedTicker)
            put("tickerPic", wingState.rightIconKey)
            put("aodTitle", combinedTicker)
            put("aodPic", wingState.leftIconKey)
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
        wingState: DualWingState,
        albumArtHash: Int,
        dominantColor: Int
    ): Icon {
        return when {
            wingState.rightIconKey == PIC_PAUSE -> wingPauseIcon
            wingState.progressStep in 0..PROGRESS_STEP_MAX -> {
                getOrCreateProgressRingIcon(
                    albumArtHash = albumArtHash,
                    step = wingState.progressStep,
                    dominantColor = dominantColor
                )
            }
            else -> wingWaveIcon
        }
    }

    private fun buildNotificationInternal(
        cleanTitleFallback: String,
        wingState: DualWingState,
        albumArtHash: Int,
        theme: ExpandedTwoToneTheme,
        prevText: String,
        currText: String,
        nextText: String,
        cover: Bitmap?,
        hasSyncedLyrics: Boolean,
        isPlaying: Boolean
    ): Notification {
        val accentColor = theme.activeLyricColor
        val accentHex = String.format("#%06X", 0xFFFFFF and accentColor)
        val expandedBody = formatStyledExpandedLyrics(prevText, currText, nextText, accentColor, hasSyncedLyrics)
        val vinyl = getOrCreateVinylAssets(cover, accentColor)
        val smallIcon = if (isPlaying) smallWaveIcon else smallPauseIcon
        val rightIcon = resolveRightWingIcon(wingState, albumArtHash, accentColor)

        val expandedRv = buildExpandedRemoteViews(
            vinyl = vinyl,
            albumArtHash = albumArtHash,
            theme = theme,
            prev = prevText,
            curr = currText,
            next = nextText,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )
        val (focusJson, customFocusJson) = getOrBuildFocusParamJson(
            wingState = wingState,
            fallbackTitle = cleanTitleFallback,
            accentHex = accentHex
        )

        val focusPics = Bundle().apply {
            putParcelable(PIC_DISC, vinyl.icon)
            putParcelable(PIC_SMALL, smallIcon)
            putParcelable(wingState.rightIconKey, rightIcon)
        }

        val extras = Bundle().apply {
            putString("miui.focus.param", focusJson)
            putString("miui.focus.param.custom", customFocusJson)
            putBundle("miui.focus.pics", focusPics)
            putParcelable("miui.focus.rv", expandedRv)
            putParcelable("miui.focus.rv.island.expand", expandedRv)
        }

        val subLabel = listOf(wingState.leftText, wingState.rightText)
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setLargeIcon(vinyl.baseDisc)
            .setContentTitle(cleanTitleFallback.ifBlank { "DynamicLyrics" })
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

        if (subLabel.isNotBlank()) {
            builder.setSubText(subLabel)
        }

        return builder.build()
    }

    private fun extractTwoTonePalette(bitmap: Bitmap): ExpandedTwoToneTheme {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return ExpandedTwoToneTheme(
                    bgStartColor = 0xFF1C2029.toInt(),
                    bgEndColor = 0xFF10131A.toInt(),
                    activeLyricColor = Color.WHITE
                )
            }

            val mini = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
            val colorRSum = FloatArray(12)
            val colorGSum = FloatArray(12)
            val colorBSum = FloatArray(12)
            val colorWeight = FloatArray(12)
            var totalColorWeight = 0f

            var monoRSum = 0f
            var monoGSum = 0f
            var monoBSum = 0f
            var monoWeight = 0f

            for (y in 0 until mini.height) {
                for (x in 0 until mini.width) {
                    val px = mini.getPixel(x, y)
                    if (Color.alpha(px) < 128) continue
                    Color.colorToHSV(px, hsvBuffer)
                    val h = hsvBuffer[0]
                    val s = hsvBuffer[1]
                    val v = hsvBuffer[2]

                    val r = Color.red(px).toFloat()
                    val g = Color.green(px).toFloat()
                    val b = Color.blue(px).toFloat()

                    val isNearBlackOrWhite = v < 0.08f || (v > 0.95f && s < 0.09f)
                    if (!isNearBlackOrWhite && s >= 0.14f) {
                        val bin = ((h / 30f).toInt()).coerceIn(0, 11)
                        val w = 1.0f + s * 2.2f + if (v in 0.16f..0.90f) 0.45f else 0f
                        colorRSum[bin] += r * w
                        colorGSum[bin] += g * w
                        colorBSum[bin] += b * w
                        colorWeight[bin] += w
                        totalColorWeight += w
                    } else if (v >= 0.05f && v <= 0.96f) {
                        val w = 1.0f
                        monoRSum += r * w
                        monoGSum += g * w
                        monoBSum += b * w
                        monoWeight += w
                    }
                }
            }
            if (mini !== bitmap) mini.recycle()

            if (totalColorWeight <= 0.01f) {
                if (monoWeight <= 0f) {
                    return ExpandedTwoToneTheme(
                        bgStartColor = 0xFF1C2029.toInt(),
                        bgEndColor = 0xFF10131A.toInt(),
                        activeLyricColor = Color.WHITE
                    )
                }
                val avg = Color.rgb(
                    (monoRSum / monoWeight).roundToInt().coerceIn(0, 255),
                    (monoGSum / monoWeight).roundToInt().coerceIn(0, 255),
                    (monoBSum / monoWeight).roundToInt().coerceIn(0, 255)
                )
                Color.colorToHSV(avg, hsvBuffer)
                val baseHue = hsvBuffer[0]
                val satMuted = hsvBuffer[1].coerceAtMost(0.16f)
                val bgStart = Color.HSVToColor(255, floatArrayOf(baseHue, satMuted, 0.18f))
                val bgEnd = Color.HSVToColor(255, floatArrayOf(baseHue, satMuted, 0.09f))
                val activeNeutral = Color.rgb(245, 247, 252)
                return ExpandedTwoToneTheme(bgStart, bgEnd, activeNeutral)
            }

            var dominantBin = 0
            for (i in 1 until 12) {
                if (colorWeight[i] > colorWeight[dominantBin]) {
                    dominantBin = i
                }
            }
            val domRaw = Color.rgb(
                (colorRSum[dominantBin] / colorWeight[dominantBin]).roundToInt().coerceIn(0, 255),
                (colorGSum[dominantBin] / colorWeight[dominantBin]).roundToInt().coerceIn(0, 255),
                (colorBSum[dominantBin] / colorWeight[dominantBin]).roundToInt().coerceIn(0, 255)
            )

            val domHsv = FloatArray(3)
            Color.colorToHSV(domRaw, domHsv)
            val domHue = domHsv[0]
            val domSat = domHsv[1].coerceIn(0.45f, 0.70f)
            val vStart = (0.14f + domHsv[2].coerceIn(0f, 1f) * 0.08f).coerceIn(0.14f, 0.22f)
            val vEnd = (0.07f + domHsv[2].coerceIn(0f, 1f) * 0.04f).coerceIn(0.07f, 0.11f)

            val bgStartColor = Color.HSVToColor(255, floatArrayOf(domHue, domSat, vStart))
            val bgEndColor = Color.HSVToColor(255, floatArrayOf(domHue, (domSat * 0.92f).coerceIn(0.40f, 0.68f), vEnd))

            // Seleziona colore secondario armonico distinto dal dominante
            var bestSecBin = -1
            var bestSecScore = -1f
            val candidateHsv = FloatArray(3)
            for (i in 0 until 12) {
                if (i == dominantBin || colorWeight[i] < totalColorWeight * 0.035f) continue
                val binColor = Color.rgb(
                    (colorRSum[i] / colorWeight[i]).roundToInt().coerceIn(0, 255),
                    (colorGSum[i] / colorWeight[i]).roundToInt().coerceIn(0, 255),
                    (colorBSum[i] / colorWeight[i]).roundToInt().coerceIn(0, 255)
                )
                Color.colorToHSV(binColor, candidateHsv)
                val rawDiff = abs(candidateHsv[0] - domHue)
                val hueDiff = min(rawDiff, 360f - rawDiff)
                if (hueDiff < 24f && abs(candidateHsv[2] - domHsv[2]) < 0.22f) continue
                val prominence = (colorWeight[i] / totalColorWeight).coerceAtMost(0.45f)
                val score = (hueDiff / 180f) * 1.45f + candidateHsv[1] * 1.1f + candidateHsv[2] * 0.65f + prominence
                if (score > bestSecScore) {
                    bestSecScore = score
                    bestSecBin = i
                }
            }

            val secHsv = FloatArray(3)
            if (bestSecBin >= 0) {
                val secRaw = Color.rgb(
                    (colorRSum[bestSecBin] / colorWeight[bestSecBin]).roundToInt().coerceIn(0, 255),
                    (colorGSum[bestSecBin] / colorWeight[bestSecBin]).roundToInt().coerceIn(0, 255),
                    (colorBSum[bestSecBin] / colorWeight[bestSecBin]).roundToInt().coerceIn(0, 255)
                )
                Color.colorToHSV(secRaw, secHsv)
                secHsv[1] = secHsv[1].coerceIn(0.42f, 0.88f)
                secHsv[2] = secHsv[2].coerceAtLeast(0.88f)
            } else {
                // Cover monocromatica: genera colore analogo spostando la hue di ±22°
                val hueShift = if (domHue in 25f..180f) -22f else 24f
                secHsv[0] = (domHue + hueShift + 360f) % 360f
                secHsv[1] = domHsv[1].coerceIn(0.45f, 0.78f)
                secHsv[2] = 0.94f
            }

            var activeColor = enforceContrastRatio(Color.HSVToColor(255, secHsv), bgStartColor, minRatio = 5.5)
            ExpandedTwoToneTheme(bgStartColor, bgEndColor, activeColor)
        } catch (_: Throwable) {
            ExpandedTwoToneTheme(
                bgStartColor = 0xFF1C2029.toInt(),
                bgEndColor = 0xFF10131A.toInt(),
                activeLyricColor = Color.WHITE
            )
        }
    }

    private fun enforceContrastRatio(fgColor: Int, bgColor: Int, minRatio: Double): Int {
        if (ColorUtils.calculateContrast(fgColor, bgColor) >= minRatio) {
            return fgColor
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(fgColor, hsv)
        var candidate = fgColor
        for (step in 1..20) {
            val factor = step / 20f
            hsv[2] = (hsv[2] + (1f - hsv[2]) * 0.34f).coerceAtMost(1f)
            hsv[1] = (hsv[1] * (1f - 0.16f * factor)).coerceAtLeast(0.06f)
            candidate = Color.HSVToColor(255, hsv)
            if (step >= 10) {
                candidate = ColorUtils.blendARGB(candidate, Color.rgb(255, 250, 244), 0.25f)
                Color.colorToHSV(candidate, hsv)
            }
            if (ColorUtils.calculateContrast(candidate, bgColor) >= minRatio) {
                return candidate
            }
        }
        return Color.WHITE
    }

    private fun resolveExpandedTheme(cover: Bitmap?): ExpandedTwoToneTheme {
        if (cover == null || cover.isRecycled || cover.width <= 0 || cover.height <= 0) {
            return ExpandedTwoToneTheme(
                bgStartColor = 0xFF1C2029.toInt(),
                bgEndColor = 0xFF10131A.toInt(),
                activeLyricColor = Color.WHITE
            )
        }
        val existing = cachedTwoToneTheme
        if (existing != null && cachedThemeCoverRef === cover) {
            return existing
        }
        val extracted = extractTwoTonePalette(cover)
        cachedThemeCoverRef = cover
        cachedTwoToneTheme = extracted
        return extracted
    }

    private fun getOrCreateExpandedBgBitmap(
        albumArtHash: Int,
        theme: ExpandedTwoToneTheme
    ): Bitmap {
        val key = "${albumArtHash}_${theme.bgStartColor}_${theme.bgEndColor}"
        expandedBgCache.get(key)?.let { if (!it.isRecycled) return it }

        val w = EXPANDED_BG_WIDTH
        val h = EXPANDED_BG_HEIGHT
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                theme.bgStartColor,
                theme.bgEndColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Leggerissimo bordo interno lucido (1px bianco alpha 22)
        val borderPaint = Paint().apply {
            color = Color.argb(22, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, borderPaint)

        expandedBgCache.put(key, bmp)
        return bmp
    }

    fun buildNotification(
        title: String,
        artist: String,
        prevLyric: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        cover: Bitmap?,
        albumArtHash: Int,
        palette: CoverPalette,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        audioCodecBadge: String,
        audioSpecBadge: String,
        hasSyncedLyrics: Boolean,
        forcePhase3Flash: Boolean = false
    ): Notification {
        val wingState = computeDualWingState(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            posMs = posMs,
            durationMs = durationMs,
            audioCodecBadge = audioCodecBadge,
            audioSpecBadge = audioSpecBadge,
            forcePhase3Flash = forcePhase3Flash
        )
        val theme = resolveExpandedTheme(cover)
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            artist = artist,
            posMs = posMs,
            durationMs = durationMs,
            audioCodecBadge = audioCodecBadge,
            audioSpecBadge = audioSpecBadge,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )

        return buildNotificationInternal(
            cleanTitleFallback = getCleanTitle(title),
            wingState = wingState,
            albumArtHash = albumArtHash,
            theme = theme,
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
        albumArtHash: Int,
        palette: CoverPalette,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        audioCodecBadge: String,
        audioSpecBadge: String,
        hasSyncedLyrics: Boolean,
        forcePhase3Flash: Boolean = false
    ) {
        val wingState = computeDualWingState(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            posMs = posMs,
            durationMs = durationMs,
            audioCodecBadge = audioCodecBadge,
            audioSpecBadge = audioSpecBadge,
            forcePhase3Flash = forcePhase3Flash
        )
        val theme = resolveExpandedTheme(cover)
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            artist = artist,
            posMs = posMs,
            durationMs = durationMs,
            audioCodecBadge = audioCodecBadge,
            audioSpecBadge = audioSpecBadge,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )
        lastFullStateKey =
            "${wingState.leftText}|${wingState.leftIconKey}|" +
            "${wingState.rightText}|${wingState.rightIconKey}|" +
            "$isPlaying|${theme.bgStartColor}|${theme.activeLyricColor}|$albumArtHash|$hasSyncedLyrics|$prevText|$currText|$nextText"
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
        albumArtHash: Int,
        palette: CoverPalette,
        isPlaying: Boolean,
        posMs: Long,
        durationMs: Long,
        audioCodecBadge: String,
        audioSpecBadge: String,
        hasSyncedLyrics: Boolean,
        forcePhase3Flash: Boolean = false
    ) {
        val isScreenOn = powerManager?.isInteractive ?: true
        if (!isScreenOn) return

        val wingState = computeDualWingState(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            posMs = posMs,
            durationMs = durationMs,
            audioCodecBadge = audioCodecBadge,
            audioSpecBadge = audioSpecBadge,
            forcePhase3Flash = forcePhase3Flash
        )
        val theme = resolveExpandedTheme(cover)
        val (prevText, currText, nextText) = resolveDisplayLines(
            prevLyric = prevLyric,
            currentLine = currentLine,
            nextLyric1 = nextLyric1,
            title = title,
            artist = artist,
            posMs = posMs,
            durationMs = durationMs,
            audioCodecBadge = audioCodecBadge,
            audioSpecBadge = audioSpecBadge,
            hasSyncedLyrics = hasSyncedLyrics,
            isPlaying = isPlaying
        )

        val fullStateKey =
            "${wingState.leftText}|${wingState.leftIconKey}|" +
            "${wingState.rightText}|${wingState.rightIconKey}|" +
            "$isPlaying|${theme.bgStartColor}|${theme.activeLyricColor}|$albumArtHash|$hasSyncedLyrics|$prevText|$currText|$nextText"

        if (fullStateKey == lastFullStateKey) return
        lastFullStateKey = fullStateKey

        try {
            manager.notify(
                NOTIFICATION_ID,
                buildNotificationInternal(
                    cleanTitleFallback = getCleanTitle(title),
                    wingState = wingState,
                    albumArtHash = albumArtHash,
                    theme = theme,
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

    private fun getOrCreateProgressRingIcon(
        albumArtHash: Int,
        step: Int,
        dominantColor: Int
    ): Icon {
        val safeStep = step.coerceIn(0, PROGRESS_STEP_MAX)
        val cacheKey = "${albumArtHash}_${dominantColor}_$safeStep"
        progressRingIconCache.get(cacheKey)?.let { return it }

        val fraction = safeStep / PROGRESS_STEP_MAX.toFloat()
        val icon = Icon.createWithBitmap(
            renderProgressRingBitmap(
                size = WING_ICON_SIZE,
                progressFraction = fraction,
                dominantColor = dominantColor
            )
        )
        progressRingIconCache.put(cacheKey, icon)
        return icon
    }

    private fun renderProgressRingBitmap(size: Int, progressFraction: Float, dominantColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val strokeW = size * 0.118f
        val pad = strokeW * 1.15f
        val oval = RectF(pad, pad, size - pad, size - pad)

        // Traccia di sfondo sottile semitrasparente (alpha ~60)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 0.78f
        }
        canvas.drawOval(oval, trackPaint)

        val sweepDeg = (progressFraction.coerceIn(0f, 1f) * 360f).coerceAtLeast(18f)

        // Leggero bagliore esterno attorno all'arco dominante
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(
                76,
                Color.red(dominantColor),
                Color.green(dominantColor),
                Color.blue(dominantColor)
            )
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 1.48f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(oval, -90f, sweepDeg, false, glowPaint)

        // Arco principale spesso con colore dominante della copertina
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dominantColor
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(oval, -90f, sweepDeg, false, arcPaint)

        // Sottile riflesso interno nitido per stacco sul nero HyperOS
        val innerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = (strokeW * 0.28f).coerceAtLeast(1.8f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(oval, -90f, sweepDeg, false, innerHighlightPaint)

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
