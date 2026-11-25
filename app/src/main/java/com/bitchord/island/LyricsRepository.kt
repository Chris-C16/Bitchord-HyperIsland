package com.bitchord.island

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs

data class LyricWord(val text: String, val startMs: Long, val endMs: Long)
data class LyricLine(val startMs: Long, val endMs: Long, val fullText: String, val words: List<LyricWord>)
data class CoverPalette(
    val primary: Color = Color(0xFF282038),
    val secondary: Color = Color(0xFF14111C),
    val accent: Color = Color(0xFF564787)
)

object LyricsRepository {
    private val client = OkHttpClient()
    private val lineRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})](.*)""")
    private val wordTagRegex = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<]*)""")

    suspend fun fetchSyncedLyrics(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val rawTitle = stripLeadingBadges(title)
            val cleanTitle = cleanTrackTitle(rawTitle)
            val leetTitle = normalizeLeet(cleanTitle)
            val asciiTitle = stripAccents(leetTitle)

            val rawArtist = artist.trim()
            val primaryArtist = rawArtist.split(",", "&", "feat.", "ft.", " x ", " X ")
                .firstOrNull()?.trim().orEmpty()

            val titleVariants = linkedSetOf(rawTitle, cleanTitle, leetTitle, asciiTitle)
                .filter { it.isNotBlank() }

            val artistVariants = linkedSetOf<String>().apply {
                if (rawArtist.isNotBlank()) add(rawArtist)
                if (primaryArtist.isNotBlank()) add(primaryArtist)
                addAll(artistAliases(primaryArtist.ifBlank { rawArtist }))
            }.filter { it.isNotBlank() }

            // 1. Endpoint esatto /api/get con tutte le varianti titolo/artista
            for (t in titleVariants) {
                for (a in artistVariants) {
                    val direct = queryGetEndpoint(t, a, durationSec)
                    if (direct.isNotEmpty()) return@withContext direct
                }
            }

            // 2. Endpoint /api/search strutturato (ignora scarti di durata > 2s)
            for (t in titleVariants) {
                for (a in artistVariants) {
                    val found = querySearchEndpoint(trackName = t, artistName = a, freeQuery = null, durationSec = durationSec)
                    if (found.isNotEmpty()) return@withContext found
                }
            }

            // 3. Fallback /api/search libero (?q=)
            val freeQueries = linkedSetOf(
                "$cleanTitle ${artistVariants.firstOrNull().orEmpty()}".trim(),
                "$leetTitle ${artistVariants.lastOrNull().orEmpty()}".trim(),
                "$asciiTitle ${artistVariants.lastOrNull().orEmpty()}".trim(),
                leetTitle
            ).filter { it.isNotBlank() }

            for (q in freeQueries) {
                val found = querySearchEndpoint(trackName = null, artistName = null, freeQuery = q, durationSec = durationSec)
                if (found.isNotEmpty()) return@withContext found
            }

            emptyList()
        }

    private fun stripLeadingBadges(raw: String): String {
        return raw
            .replace(Regex("""^[🅴\s]+"""), "")
            .replace(Regex("""^[\[\(]E[\]\)]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanTrackTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*[\(\[].*?[\)\]]"""), "")
            .replace(Regex("""\s+(feat\.|ft\.).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun artistAliases(artist: String): List<String> {
        val lower = artist.lowercase().trim()
        return when (lower) {
            "tha supreme", "thasupreme" -> listOf("thasup")
            "thasup" -> listOf("tha Supreme")
            else -> emptyList()
        }
    }

    private fun normalizeLeet(text: String): String {
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val ch = text[i]
            val prevIsLetter = i > 0 && text[i - 1].isLetter()
            val nextIsLetter = i + 1 < text.length && text[i + 1].isLetter()
            val mapped = when (ch) {
                '@' -> 'a'
                '$' -> 's'
                '!' -> if (prevIsLetter || nextIsLetter) 'i' else ch
                '+' -> if (prevIsLetter && nextIsLetter) 't' else ch
                '3' -> if (prevIsLetter || nextIsLetter) 'e' else ch
                '0' -> if (prevIsLetter || nextIsLetter) 'o' else ch
                '1' -> if (prevIsLetter || nextIsLetter) 'i' else ch
                '5' -> if (prevIsLetter || nextIsLetter) 's' else ch
                '7' -> if (prevIsLetter || nextIsLetter) 't' else ch
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }

    private fun stripAccents(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
    }

    private fun extractSyncedLrc(obj: JSONObject): String? {
        if (obj.isNull("syncedLyrics")) return null
        val synced = obj.optString("syncedLyrics", "").trim()
        return if (synced.isBlank() || synced.equals("null", ignoreCase = true)) null else synced
    }

    private fun queryGetEndpoint(title: String, artist: String, durationSec: Long): List<LyricLine> {
        return try {
            val qTitle = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$qTitle&artist_name=$qArtist" +
                if (durationSec > 0) "&duration=$durationSec" else ""
            val req = Request.Builder().url(url).header("User-Agent", "BitChordIsland/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val synced = extractSyncedLrc(JSONObject(body)) ?: return emptyList()
                parseLrc(synced)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun querySearchEndpoint(
        trackName: String?,
        artistName: String?,
        freeQuery: String?,
        durationSec: Long
    ): List<LyricLine> {
        return try {
            val url = if (!freeQuery.isNullOrBlank()) {
                val q = URLEncoder.encode(freeQuery.trim(), "UTF-8")
                "https://lrclib.net/api/search?q=$q"
            } else {
                val qTitle = URLEncoder.encode(trackName.orEmpty().trim(), "UTF-8")
                val qArtist = URLEncoder.encode(artistName.orEmpty().trim(), "UTF-8")
                "https://lrclib.net/api/search?track_name=$qTitle&artist_name=$qArtist"
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "BitChordIsland/2.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: return emptyList())

                var bestCloseParsed: List<LyricLine> = emptyList()
                var bestCloseDiff = Long.MAX_VALUE
                var firstValidParsed: List<LyricLine> = emptyList()

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val synced = extractSyncedLrc(obj) ?: continue
                    val parsed = parseLrc(synced)
                    if (parsed.isEmpty()) continue

                    if (durationSec <= 0L) return parsed
                    if (firstValidParsed.isEmpty()) firstValidParsed = parsed

                    val itemDur = obj.optDouble("duration", -1.0).toLong()
                    if (itemDur > 0L) {
                        val diff = abs(itemDur - durationSec)
                        if (diff <= 12L && diff < bestCloseDiff) {
                            bestCloseDiff = diff
                            bestCloseParsed = parsed
                        }
                    }
                }

                if (bestCloseParsed.isNotEmpty()) bestCloseParsed else firstValidParsed
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
