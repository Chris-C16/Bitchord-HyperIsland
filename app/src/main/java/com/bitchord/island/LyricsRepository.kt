package com.bitchord.island

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class LyricWord(val text: String, val startMs: Long, val endMs: Long)
data class LyricLine(val startMs: Long, val endMs: Long, val fullText: String, val words: List<LyricWord>)
data class CoverPalette(
    val primary: Color = Color(0xFF2B2440),
    val secondary: Color = Color(0xFF15121E),
    val accent: Color = Color(0xFF5A4B8A)
)

object LyricsRepository {
    private val client = OkHttpClient()
    private val lineRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
    private val wordTagRegex = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<]*)""")

    suspend fun fetchSyncedLyrics(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val qTitle = URLEncoder.encode(title, "UTF-8")
                val qArtist = URLEncoder.encode(artist, "UTF-8")
                val url = "https://lrclib.net/api/get?track_name=$qTitle&artist_name=$qArtist" +
                        if (durationSec > 0) "&duration=$durationSec" else ""
                val req = Request.Builder().url(url).header("User-Agent", "BitChordIsland/1.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val body = resp.body?.string() ?: return@withContext emptyList()
                    val synced = JSONObject(body).optString("syncedLyrics", "")
                    if (synced.isBlank()) return@withContext emptyList()
                    parseLrc(synced)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun parseLrc(rawLrc: String): List<LyricLine> {
        val rawEntries = mutableListOf<Pair<Long, String>>()
        rawLrc.lines().forEach { line ->
            val match = lineRegex.find(line.trim()) ?: return@forEach
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msPart = match.groupValues[3]
            val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
            val content = match.groupValues[4].trim()
            if (content.isNotEmpty()) rawEntries.add((min * 60_000 + sec * 1_000 + ms) to content)
        }
        val sorted = rawEntries.sortedBy { it.first }
        return sorted.mapIndexed { idx, (startMs, content) ->
            val nextStart = sorted.getOrNull(idx + 1)?.first ?: (startMs + 4_500L)
            val endMs = nextStart.coerceAtLeast(startMs + 800L)
            val wordMatches = wordTagRegex.findAll(content).toList()
            if (wordMatches.isNotEmpty()) {
                val words = wordMatches.mapIndexed { wIdx, m ->
                    val wStart = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 +
                            (if (m.groupValues[3].length == 2) m.groupValues[3].toLong() * 10 else m.groupValues[3].toLong())
                    val wEnd = wordMatches.getOrNull(wIdx + 1)?.let { n ->
                        n.groupValues[1].toLong() * 60_000 + n.groupValues[2].toLong() * 1_000 +
                                (if (n.groupValues[3].length == 2) n.groupValues[3].toLong() * 10 else n.groupValues[3].toLong())
                    } ?: endMs
                    LyricWord(m.groupValues[4], wStart, wEnd.coerceAtLeast(wStart + 120L))
                }
                LyricLine(startMs, endMs, words.joinToString("") { it.text }.trim(), words)
            } else {
                val tokens = content.split(" ").filter { it.isNotEmpty() }
                val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
                var cursor = startMs
                val duration = (endMs - startMs).coerceAtLeast(500L)
                val words = tokens.mapIndexed { tIdx, token ->
                    val slice = (duration * token.length) / totalChars
                    val wStart = cursor
                    val wEnd = if (tIdx == tokens.lastIndex) endMs else (cursor + slice)
                    cursor = wEnd
                    LyricWord(if (tIdx == tokens.lastIndex) token else "$token ", wStart, wEnd)
                }
                LyricLine(startMs, endMs, content, words)
            }
        }
    }

    fun extractPalette(bitmap: Bitmap?): CoverPalette {
        if (bitmap == null) return CoverPalette()
        val p = Palette.from(bitmap).generate()
        return CoverPalette(
            primary = Color(p.getDarkVibrantColor(0xFF282038.toInt())),
            secondary = Color(p.getDarkMutedColor(0xFF14111C.toInt())),
            accent = Color(p.getVibrantColor(0xFF564787.toInt()))
        )
    }
}
