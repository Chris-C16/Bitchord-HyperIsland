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
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class AutoLyricsCanvasRenderer(
    private val context: Context,
    private val onFontReady: () -> Unit
) {

    companion object {
        private const val SIZE = 640
        // Margine laterale calibrato per evitare tagli sui bordi e non sovrapporsi alle icone in alto
        private const val HORIZONTAL_MARGIN = 54f
        // Fascia verticale libera nella card Coolwalk: sotto i 3 puntini e sopra Titolo/Artista
        private const val SAFE_TOP = 68f
        private const val SAFE_BOTTOM = 348f
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
            val isCurrent: Boolean,
            val maxLines: Int
        )

        fun buildSpecs(
            includeOuter: Boolean,
            currSize: Float,
            adjSize: Float,
            outerSize: Float
        ): List<LineSpec> {
            val list = mutableListOf<LineSpec>()
            if (includeOuter && state.prev2.isNotBlank()) {
                list.add(LineSpec(state.prev2, outerSize, Color.argb(110, 255, 255, 255), false, 1))
            }
            if (state.prev1.isNotBlank()) {
                list.add(LineSpec(state.prev1, adjSize, Color.argb(185, 255, 255, 255), false, 1))
            }
            list.add(LineSpec(currText, currSize, accentColor, true, 2))
            if (state.next1.isNotBlank()) {
                list.add(LineSpec(state.next1, adjSize, Color.argb(185, 255, 255, 255), false, 1))
            }
            if (includeOuter && state.next2.isNotBlank()) {
                list.add(LineSpec(state.next2, outerSize, Color.argb(110, 255, 255, 255), false, 1))
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
                            16f,
                            0f,
                            2f,
                            Color.argb(
                                170,
                                Color.red(accentColor),
                                Color.green(accentColor),
                                Color.blue(accentColor)
                            )
                        )
                    } else {
                        setShadowLayer(5f, 0f, 1f, Color.argb(180, 0, 0, 0))
                    }
                }
                val layout = StaticLayout.Builder
                    .obtain(spec.text, 0, spec.text.length, paint, usableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(spec.maxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.06f)
                    .build()
                spec to layout
            }
        }

        fun totalHeight(items: List<Pair<LineSpec, StaticLayout>>): Float {
            var total = 0f
            for (i in items.indices) {
                total += items[i].second.height
                if (i < items.lastIndex) {
                    val gap = if (items[i].first.isCurrent || items[i + 1].first.isCurrent) 15f else 10f
                    total += gap
                }
            }
            return total
        }

        val safeHeight = SAFE_BOTTOM - SAFE_TOP
        var items = makeLayouts(buildSpecs(includeOuter = true, currSize = 44f, adjSize = 28f, outerSize = 21f))
        if (totalHeight(items) > safeHeight) {
            items = makeLayouts(buildSpecs(includeOuter = true, currSize = 38f, adjSize = 24f, outerSize = 19f))
        }
        if (totalHeight(items) > safeHeight) {
            items = makeLayouts(buildSpecs(includeOuter = false, currSize = 42f, adjSize = 27f, outerSize = 20f))
        }

        val blockHeight = totalHeight(items)
        var y = SAFE_TOP + ((safeHeight - blockHeight) / 2f).coerceAtLeast(0f)

        for (i in items.indices) {
            val (spec, layout) = items[i]
            canvas.save()
            canvas.translate(HORIZONTAL_MARGIN, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
            if (i < items.lastIndex) {
                y += if (spec.isCurrent || items[i + 1].first.isCurrent) 15f else 10f
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
            (Color.red(accentColor) * 0.18f).toInt().coerceIn(14, 48),
            (Color.green(accentColor) * 0.18f).toInt().coerceIn(14, 48),
            (Color.blue(accentColor) * 0.22f).toInt().coerceIn(20, 58)
        )
        val bgGradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                SIZE.toFloat(),
                intArrayOf(darkTop, Color.rgb(12, 13, 20), Color.rgb(7, 8, 12)),
                floatArrayOf(0f, 0.56f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bgGradient)

        if (cover != null && !cover.isRecycled && cover.width > 0 && cover.height > 0) {
            val small = createCenterCroppedSmall(cover, 44)
            val blurred = fastBoxBlur(small, radius = 3, passes = 3)
            small.recycle()

            val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(blurred, null, RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), imgPaint)
            blurred.recycle()

            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    SIZE.toFloat(),
                    intArrayOf(
                        Color.argb(150, 10, 11, 16),
                        Color.argb(140, 10, 11, 16),
                        Color.argb(205, 8, 9, 14),
                        Color.argb(242, 6, 7, 11)
                    ),
                    floatArrayOf(0f, 0.48f, 0.62f, 1f),
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

    private fun createCenterCroppedSmall(src: Bitmap, targetSize: Int): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val minDim = min(src.width, src.height)
        val left = (src.width - minDim) / 2
        val top = (src.height - minDim) / 2
        val srcRect = Rect(left, top, left + minDim, top + minDim)
        val dstRect = Rect(0, 0, targetSize, targetSize)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return out
    }

    private fun fastBoxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val current = IntArray(w * h)
        val next = IntArray(w * h)
        src.getPixels(current, 0, w, 0, 0, w, h)

        repeat(passes) {
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    val xStart = max(0, x - radius)
                    val xEnd = min(w - 1, x + radius)
                    for (nx in xStart..xEnd) {
                        val p = current[rowOffset + nx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                    next[rowOffset + x] = Color.rgb(r / count, g / count, b / count)
                }
            }
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    val yStart = max(0, y - radius)
                    val yEnd = min(h - 1, y + radius)
                    for (ny in yStart..yEnd) {
                        val p = next[ny * w + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                    current[y * w + x] = Color.rgb(r / count, g / count, b / count)
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(current, 0, w, 0, 0, w, h)
        return out
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
