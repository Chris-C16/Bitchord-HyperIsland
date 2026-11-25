package com.bitchord.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.min

class AutoLyricsCanvasRenderer(
    private val context: Context,
    private val onFontReady: () -> Unit
) {

    companion object {
        private const val SIZE = 640
        // Margine ampio: compensa il centerCrop laterale della card verticale di Coolwalk
        private const val HORIZONTAL_MARGIN = 112f
        // Fascia libera nella metà superiore della card (sotto i 3 puntini, sopra Titolo/Artista)
        private const val SAFE_TOP = 72f
        private const val SAFE_BOTTOM = 352f
        private const val COMFORTAA_VAR_URL =
            "https://raw.githubusercontent.com/google/fonts/main/ofl/comfortaa/Comfortaa%5Bwght%5D.ttf"
        private const val COMFORTAA_FALLBACK_URL =
            "https://fonts.gstatic.com/s/comfortaa/v47/1Pt_g8LJRfWJmhDAuUsSQamb1W0lwk4S4Y_LPrQVIT9c2c8.ttf"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var comfortaaRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    @Volatile
    private var comfortaaBold: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

    private var cachedCoverRef: Bitmap? = null
    private var cachedAccent: Int = 0
    private var cachedBackground: Bitmap? = null

    init {
        loadOrDownloadComfortaa()
    }

    private fun loadOrDownloadComfortaa() {
        val fontDir = File(context.filesDir, "fonts")
        val fontFile = File(fontDir, "Comfortaa.ttf")

        if (fontFile.exists() && fontFile.length() > 10_000L) {
            applyFontFile(fontFile)
            return
        }

        thread(name = "ComfortaaFontLoader", isDaemon = true) {
            try {
                if (!fontDir.exists()) fontDir.mkdirs()
                val tmpFile = File(fontDir, "Comfortaa.ttf.tmp")
                val downloaded = downloadToFile(COMFORTAA_VAR_URL, tmpFile) ||
                    downloadToFile(COMFORTAA_FALLBACK_URL, tmpFile)
                if (downloaded && tmpFile.length() > 10_000L) {
                    if (fontFile.exists()) fontFile.delete()
                    if (tmpFile.renameTo(fontFile) && applyFontFile(fontFile)) {
                        mainHandler.post { onFontReady() }
                    }
                } else {
                    tmpFile.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun downloadToFile(urlStr: String, target: File): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return false
            }
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyFontFile(file: File): Boolean {
        return try {
            val regular = Typeface.Builder(file)
                .setFontVariationSettings("'wght' 600")
                .build()
            val bold = Typeface.Builder(file)
                .setFontVariationSettings("'wght' 700")
                .build()
            if (regular != null && bold != null) {
                comfortaaRegular = regular
                comfortaaBold = bold
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            try {
                val fallback = Typeface.createFromFile(file)
                comfortaaRegular = fallback
                comfortaaBold = Typeface.create(fallback, Typeface.BOLD)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun render(state: AutoLyricsState): Bitmap {
        val accentColor = boostAccentForCar(state.palette.accent.toArgb())
        val bg = getOrCreateBackground(state.cover, accentColor)
        val output = bg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val usableWidth = (SIZE - HORIZONTAL_MARGIN * 2f).toInt()
        val currText = state.curr.ifBlank {
            if (!state.isPlaying) "In pausa" else "• • •"
        }

        data class LineSpec(
            val text: String,
            val textSize: Float,
            val color: Int,
            val isCurrent: Boolean
        )

        fun buildSpecs(currSize: Float, adjSize: Float, includeNeighbors: Boolean): List<LineSpec> {
            val list = mutableListOf<LineSpec>()
            if (includeNeighbors && state.prev1.isNotBlank()) {
                list.add(LineSpec(state.prev1, adjSize, Color.argb(185, 255, 255, 255), false))
            }
            list.add(LineSpec(currText, currSize, Color.WHITE, true))
            if (includeNeighbors && state.next1.isNotBlank()) {
                list.add(LineSpec(state.next1, adjSize, Color.argb(185, 255, 255, 255), false))
            }
            return list
        }

        fun makeLayouts(specs: List<LineSpec>): List<Pair<LineSpec, StaticLayout>> {
            return specs.map { spec ->
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = spec.textSize
                    color = spec.color
                    typeface = if (spec.isCurrent) comfortaaBold else comfortaaRegular
                    if (spec.isCurrent) {
                        setShadowLayer(
                            12f,
                            0f,
                            2f,
                            Color.argb(
                                210,
                                Color.red(accentColor),
                                Color.green(accentColor),
                                Color.blue(accentColor)
                            )
                        )
                    } else {
                        setShadowLayer(6f, 0f, 2f, Color.argb(210, 0, 0, 0))
                    }
                }
                val layout = StaticLayout.Builder
                    .obtain(spec.text, 0, spec.text.length, paint, usableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.08f)
                    .build()
                spec to layout
            }
        }

        fun totalHeight(items: List<Pair<LineSpec, StaticLayout>>): Float {
            var total = 0f
            for (i in items.indices) {
                total += items[i].second.height
                if (i < items.lastIndex) total += 14f
            }
            return total
        }

        fun fitsCleanly(items: List<Pair<LineSpec, StaticLayout>>, maxHeight: Float): Boolean {
            if (totalHeight(items) > maxHeight) return false
            return items.all { (_, layout) ->
                (0 until layout.lineCount).all { line ->
                    layout.getLineWidth(line) <= usableWidth + 1f
                }
            }
        }

        val safeHeight = SAFE_BOTTOM - SAFE_TOP
        val tiers = listOf(
            Triple(32f, 20f, true),
            Triple(28f, 18f, true),
            Triple(24f, 16f, true),
            Triple(21f, 14f, true),
            Triple(26f, 17f, false),
            Triple(21f, 15f, false)
        )

        var items = makeLayouts(buildSpecs(32f, 20f, includeNeighbors = true))
        for ((currSize, adjSize, includeNeighbors) in tiers) {
            val candidate = makeLayouts(buildSpecs(currSize, adjSize, includeNeighbors))
            items = candidate
            if (fitsCleanly(candidate, safeHeight)) {
                break
            }
        }

        val blockHeight = totalHeight(items)
        var y = SAFE_TOP + ((safeHeight - blockHeight) / 2f).coerceAtLeast(0f)

        for (i in items.indices) {
            val (_, layout) = items[i]
            canvas.save()
            canvas.translate(HORIZONTAL_MARGIN, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
            if (i < items.lastIndex) {
                y += 14f
            }
        }

        return output
    }

    private fun getOrCreateBackground(cover: Bitmap?, accentColor: Int): Bitmap {
        val existing = cachedBackground
        if (existing != null && cachedCoverRef === cover && cachedAccent == accentColor) {
            return existing
        }

        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val darkTop = Color.rgb(
            (Color.red(accentColor) * 0.22f).toInt().coerceIn(16, 56),
            (Color.green(accentColor) * 0.22f).toInt().coerceIn(16, 56),
            (Color.blue(accentColor) * 0.26f).toInt().coerceIn(22, 68)
        )
        val bgGradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                SIZE.toFloat(),
                intArrayOf(darkTop, Color.rgb(14, 15, 22), Color.rgb(8, 9, 14)),
                floatArrayOf(0f, 0.56f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bgGradient)

        if (cover != null && !cover.isRecycled && cover.width > 0 && cover.height > 0) {
            val minDim = min(cover.width, cover.height)
            val left = (cover.width - minDim) / 2
            val top = (cover.height - minDim) / 2
            val srcRect = Rect(left, top, left + minDim, top + minDim)
            val dstRect = RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat())
            val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(cover, srcRect, dstRect, imgPaint)

            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    SIZE.toFloat(),
                    intArrayOf(
                        Color.argb(110, 8, 9, 14),
                        Color.argb(130, 8, 9, 14),
                        Color.argb(190, 6, 7, 11),
                        Color.argb(235, 5, 6, 10)
                    ),
                    floatArrayOf(0f, 0.46f, 0.62f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), scrimPaint)
        }

        cachedCoverRef = cover
        cachedAccent = accentColor
        cachedBackground = bmp
        return bmp
    }

    private fun boostAccentForCar(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.16f) {
            return Color.rgb(165, 220, 255)
        }
        hsv[2] = hsv[2].coerceAtLeast(0.96f)
        hsv[1] = hsv[1].coerceIn(0.36f, 0.78f)
        return Color.HSVToColor(255, hsv)
    }
}
