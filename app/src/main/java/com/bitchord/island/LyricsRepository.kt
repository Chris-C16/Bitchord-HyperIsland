package com.bitchord.island

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

data class LyricWord(val text: String, val startMs: Long, val endMs: Long)
data class LyricLine(val startMs: Long, val endMs: Long, val fullText: String, val words: List<LyricWord>)
data class CoverPalette(
    val primary: Color = Color(0xFF282038),
    val secondary: Color = Color(0xFF14111C),
    val accent: Color = Color(0xFF564787)
)

object LyricsRepository {
    private const val USER_AGENT = "BitChordIsland/3.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // Cache in memoria per gli ultimi 64 brani (riproduzioni ripetute istantanee a 0 rete)
    private val lyricsCache = LruCache<String, List<LyricLine>>(64)

    // Regex pre-compilate una sola volta
    private val leadingTagRegex = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{1,3})]""")
    private val wordTagRegex = Regex("""<(\d{1,3}):(\d{2})[.:](\d{1,3})>([^<]*)""")
    private val regexExplicitEmoji = Regex("""^[🅴\s]+""")
    private val regexExplicitTag = Regex("""^[\[\(]E[\]\)]\s*""", RegexOption.IGNORE_CASE)
    private val regexMultiSpace = Regex("""\s+""")
    private val regexParens = Regex("""\s*[\(\[].*?[\)\]]""")
    private val regexFeat = Regex("""\s+(feat\.|ft\.).*$""", RegexOption.IGNORE_CASE)
    private val regexDiacritics = Regex("""\p{InCombiningDiacriticalMarks}+""")

    suspend fun fetchSyncedLyrics(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val rawTitle = stripLeadingBadges(title)
            val rawArtist = artist.trim()
            if (rawTitle.isBlank()) return@withContext emptyList()

            val cacheKey = "${rawTitle.lowercase()}|${rawArtist.lowercase()}|$durationSec"
            lyricsCache.get(cacheKey)?.let { return@withContext it }

            val cleanTitle = cleanTrackTitle(rawTitle)
            val leetTitle = normalizeLeet(cleanTitle)
            val asciiTitle = stripAccents(leetTitle)

            val primaryArtist = rawArtist.split(",", "&", "feat.", "ft.", " x ", " X ")
                .firstOrNull()?.trim().orEmpty()

            val titleVariants = deduplicateCaseInsensitive(listOf(rawTitle, cleanTitle, leetTitle, asciiTitle))
            val artistVariants = deduplicateCaseInsensitive(
                buildList {
                    if (rawArtist.isNotBlank()) add(rawArtist)
                    if (primaryArtist.isNotBlank()) add(primaryArtist)
                    addAll(artistAliases(primaryArtist.ifBlank { rawArtist }))
                }
            )

            // 1. Endpoint esatto /api/get con tutte le varianti titolo/artista
            for (t in titleVariants) {
                for (a in artistVariants) {
                    currentCoroutineContext().ensureActive()
                    val direct = queryGetEndpoint(t, a, durationSec)
                    if (direct.isNotEmpty()) {
                        lyricsCache.put(cacheKey, direct)
                        return@withContext direct
                    }
                }
            }

            // 2. Endpoint /api/search strutturato (ignora scarti di durata > 2s)
            for (t in titleVariants) {
                for (a in artistVariants) {
                    currentCoroutineContext().ensureActive()
                    val found = querySearchEndpoint(
                        trackName = t,
                        artistName = a,
                        freeQuery = null,
                        durationSec = durationSec
                    )
                    if (found.isNotEmpty()) {
                        lyricsCache.put(cacheKey, found)
                        return@withContext found
                    }
                }
            }

            // 3. Fallback /api/search libero (?q=)
            val freeQueries = deduplicateCaseInsensitive(
                listOf(
                    "$cleanTitle ${artistVariants.firstOrNull().orEmpty()}".trim(),
                    "$leetTitle ${artistVariants.lastOrNull().orEmpty()}".trim(),
                    "$asciiTitle ${artistVariants.lastOrNull().orEmpty()}".trim(),
                    leetTitle
                )
            )

            for (q in freeQueries) {
                currentCoroutineContext().ensureActive()
                val found = querySearchEndpoint(
                    trackName = null,
                    artistName = null,
                    freeQuery = q,
                    durationSec = durationSec
                )
                if (found.isNotEmpty()) {
                    lyricsCache.put(cacheKey, found)
                    return@withContext found
                }
            }

            emptyList()
        }

    private fun deduplicateCaseInsensitive(items: List<String>): List<String> {
        val seen = HashSet<String>(items.size)
        val out = ArrayList<String>(items.size)
        for (item in items) {
            val trimmed = item.trim()
            if (trimmed.isNotEmpty() && seen.add(trimmed.lowercase())) {
                out.add(trimmed)
            }
        }
        return out
    }

    private fun stripLeadingBadges(raw: String): String {
        return raw
            .replace(regexExplicitEmoji, "")
            .replace(regexExplicitTag, "")
            .replace(regexMultiSpace, " ")
            .trim()
    }

    private fun cleanTrackTitle(raw: String): String {
        return raw
            .replace(regexParens, "")
            .replace(regexFeat, "")
            .replace(regexMultiSpace, " ")
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
        return sb.toString().replace(regexMultiSpace, " ").trim()
    }

    private fun stripAccents(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(regexDiacritics, "")
    }

    private fun extractSyncedLrc(obj: JSONObject): String? {
        if (obj.isNull("syncedLyrics")) return null
        val synced = obj.optString("syncedLyrics", "").trim()
        return if (synced.isBlank() || synced.equals("null", ignoreCase = true)) null else synced
    }

    private suspend fun httpGetString(url: String): String? = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                } catch (_: Exception) {
                    null
                }
                if (cont.isActive) cont.resume(body)
            }
        })
    }

    private suspend fun queryGetEndpoint(title: String, artist: String, durationSec: Long): List<LyricLine> {
        return try {
            val qTitle = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$qTitle&artist_name=$qArtist" +
                if (durationSec > 0) "&duration=$durationSec" else ""
            val body = httpGetString(url) ?: return emptyList()
            val synced = extractSyncedLrc(JSONObject(body)) ?: return emptyList()
            parseLrc(synced)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun querySearchEndpoint(
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
            val body = httpGetString(url) ?: return emptyList()
            val arr = JSONArray(body)

            // Seleziona i candidati prima di eseguire parseLrc (evita fino a 20 parsing inutili)
            val candidates = ArrayList<Pair<Long, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val synced = extractSyncedLrc(obj) ?: continue
                val itemDur = obj.optDouble("duration", -1.0).toLong()
                val diff = if (durationSec > 0L && itemDur > 0L) {
                    abs(itemDur - durationSec)
                } else {
                    Long.MAX_VALUE
                }
                candidates.add(diff to synced)
            }
            if (candidates.isEmpty()) return emptyList()

            val ordered = if (durationSec > 0L) {
                candidates.sortedWith(
                    compareBy<Pair<Long, String>> { if (it.first <= 12L) 0 else 1 }
                        .thenBy { it.first }
                )
            } else {
                candidates
            }

            for ((_, synced) in ordered) {
                val parsed = parseLrc(synced)
                if (parsed.isNotEmpty()) return parsed
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTimestampMs(minStr: String, secStr: String, msStr: String): Long {
        val min = minStr.toLongOrNull() ?: 0L
        val sec = secStr.toLongOrNull() ?: 0L
        val msRaw = msStr.toLongOrNull() ?: 0L
        val ms = when (msStr.length) {
            1 -> msRaw * 100L
            2 -> msRaw * 10L
            else -> msRaw
        }
        return min * 60_000L + sec * 1_000L + ms
    }

    fun parseLrc(rawLrc: String): List<LyricLine> {
        val rawEntries = ArrayList<Pair<Long, String>>()
        rawLrc.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed[0] != '[') return@forEach

            val timestamps = ArrayList<Long>(2)
            var cursor = 0
            while (cursor < trimmed.length) {
                val match = leadingTagRegex.find(trimmed, cursor)
                if (match == null || match.range.first != cursor) break
                timestamps.add(
                    parseTimestampMs(
                        match.groupValues[1],
                        match.groupValues[2],
                        match.groupValues[3]
                    )
                )
                cursor = match.range.last + 1
            }

            if (timestamps.isEmpty()) return@forEach
            val content = trimmed.substring(cursor).trim()
            if (content.isNotEmpty()) {
                for (ts in timestamps) {
                    rawEntries.add(ts to content)
                }
            }
        }

        val sorted = rawEntries.sortedBy { it.first }
        return sorted.mapIndexed { idx, (startMs, content) ->
            val nextStart = sorted.getOrNull(idx + 1)?.first ?: (startMs + 4_500L)
            val endMs = nextStart.coerceAtLeast(startMs + 800L)
            val wordMatches = wordTagRegex.findAll(content).toList()
            if (wordMatches.isNotEmpty()) {
                val words = wordMatches.mapIndexed { wIdx, m ->
                    val wStart = parseTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    val wEnd = wordMatches.getOrNull(wIdx + 1)?.let { n ->
                        parseTimestampMs(n.groupValues[1], n.groupValues[2], n.groupValues[3])
                    } ?: endMs
                    LyricWord(m.groupValues[4], wStart, wEnd.coerceAtLeast(wStart + 120L))
                }
                LyricLine(startMs, endMs, words.joinToString("") { it.text }.trim(), words)
            } else {
                val tokens = content.split(' ').filter { it.isNotEmpty() }
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
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return CoverPalette()
        }
        val p = Palette.from(bitmap).maximumColorCount(12).generate()
        return CoverPalette(
            primary = Color(p.getDarkVibrantColor(0xFF282038.toInt())),
            secondary = Color(p.getDarkMutedColor(0xFF14111C.toInt())),
            accent = Color(p.getVibrantColor(0xFF564787.toInt()))
        )
    }
}
