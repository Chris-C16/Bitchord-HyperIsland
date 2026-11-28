package com.bitchord.island

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

class BitChordQualityAccessibilityService : AccessibilityService() {

    companion object {
        private const val SCAN_THROTTLE_MS = 400L
        private const val MAX_TRAVERSE_NODES = 250
        private const val MAX_ATTEMPTS_BEFORE_FOUND = 8

        private val REGEX_CODEC = Regex("""\b(FLAC|ALAC|WAV|MQA|DSD\d*|OPUS|AAC|MP3)\b""", RegexOption.IGNORE_CASE)
        private val REGEX_BIT_DEPTH = Regex("""\b(16|24|32)\s*-?\s*bit\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE = Regex("""\b(\d{2,3}(?:\.\d+)?)\s*k\s*hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE_HZ = Regex("""\b(384000|352800|192000|176400|96000|88200|48000|44100)\s*hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_PURE_TIME = Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")

        val isServiceConnected = MutableStateFlow(false)

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (isServiceConnected.value) return true
            return try {
                val am = context.getSystemService(AccessibilityManager::class.java)
                val targetCls = BitChordQualityAccessibilityService::class.java.name
                val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                if (enabledList?.any { info ->
                        val si = info.resolveInfo?.serviceInfo
                        si != null && si.packageName == context.packageName && si.name == targetCls
                    } == true
                ) {
                    return true
                }
                val enabledInt = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                if (enabledInt != 1) return false
                val flat = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val full = ComponentName(context, BitChordQualityAccessibilityService::class.java).flattenToString()
                val short = "${context.packageName}/.$targetCls".replace(".com.bitchord.island.", ".")
                flat.contains(full, ignoreCase = true) || flat.contains(short, ignoreCase = true)
            } catch (_: Throwable) {
                false
            }
        }
    }

    private var lastScanRealtimeMs = 0L
    private var lastAttemptedTrackKey = ""
    private var attemptsForTrack = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppPreferences.init(applicationContext)
        isServiceConnected.value = true
    }

    override fun onDestroy() {
        isServiceConnected.value = false
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Nessuna operazione bloccante richiesta
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (!isAcceptedPackage(pkg)) return

        LyricsPlaybackStore.lastAccessibilityPackage.value = pkg

        val title = LyricsPlaybackStore.title.value
        val artist = LyricsPlaybackStore.artist.value
        val trackKey = LyricsPlaybackStore.buildTrackQualityKey(title, artist)
        if (trackKey.isBlank()) return

        // Se kHz/Bit reali per questo brano sono già in cache, return immediato
        val cached = AppPreferences.getCachedBitChordQuality(applicationContext, trackKey)
        if (cached != null && hasRealSpec(cached.second)) {
            return
        }

        val eventType = event?.eventType ?: 0
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            attemptsForTrack = 0
        } else if (trackKey == lastAttemptedTrackKey && attemptsForTrack >= MAX_ATTEMPTS_BEFORE_FOUND) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRealtimeMs < SCAN_THROTTLE_MS) return

        val roots = collectMatchingWindowRoots(pkg)
        if (roots.isEmpty()) return

        lastScanRealtimeMs = now
        if (trackKey != lastAttemptedTrackKey) {
            lastAttemptedTrackKey = trackKey
            attemptsForTrack = 1
        } else {
            attemptsForTrack++
        }

        val combinedBuffer = StringBuilder(512)
        val sampleNodes = ArrayList<String>(2)
        var visitedTotal = 0

        for (root in roots) {
            if (visitedTotal >= MAX_TRAVERSE_NODES) break
            visitedTotal = collectWindowTexts(
                node = root,
                visitedStart = visitedTotal,
                buffer = combinedBuffer,
                sampleOut = sampleNodes
            )
        }

        if (combinedBuffer.isEmpty()) return

        val allText = combinedBuffer.toString()
        val parsed = parseConcatenatedBuffer(allText)

        if (parsed == null) {
            if (sampleNodes.isNotEmpty()) {
                LyricsPlaybackStore.lastAccessibilityRawMatch.value =
                    sampleNodes.joinToString(" | ").take(64)
            }
            return
        }

        val (codec, spec, matchedSnippet) = parsed
        LyricsPlaybackStore.lastAccessibilityRawMatch.value = matchedSnippet.ifBlank { spec }.take(64)

        AppPreferences.putCachedBitChordQuality(applicationContext, trackKey, codec, spec)
        LyricsPlaybackStore.audioCodecBadge.value = codec
        LyricsPlaybackStore.audioSpecBadge.value = spec
        BitChordListenerService.onBitChordQualityDetected(applicationContext, trackKey)
    }

    private fun isAcceptedPackage(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName) return false
        if (pkg.contains("bitchord", ignoreCase = true)) return true
        val activePkg = LyricsPlaybackStore.activePlayerPackage.value
        return activePkg.isNotBlank() && pkg.equals(activePkg, ignoreCase = true)
    }

    private fun collectMatchingWindowRoots(eventPkg: String): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(4)
        val seenIds = HashSet<Int>(4)

        fun tryAddRoot(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val nodePkg = node.packageName?.toString().orEmpty()
            if (isAcceptedPackage(nodePkg) || (nodePkg.isBlank() && isAcceptedPackage(eventPkg))) {
                val id = System.identityHashCode(node)
                if (seenIds.add(id)) {
                    result += node
                }
            }
        }

        try {
            tryAddRoot(rootInActiveWindow)
        } catch (_: Throwable) {
        }

        try {
            val allWindows = windows
            if (allWindows != null) {
                for (window in allWindows) {
                    val root = try {
                        window.root
                    } catch (_: Throwable) {
                        null
                    }
                    tryAddRoot(root)
                }
            }
        } catch (_: Throwable) {
        }

        return result
    }

    private fun collectWindowTexts(
        node: AccessibilityNodeInfo,
        visitedStart: Int,
        buffer: StringBuilder,
        sampleOut: MutableList<String>
    ): Int {
        var visited = visitedStart

        fun appendPiece(raw: CharSequence?) {
            val clean = raw?.toString()?.trim() ?: return
            if (clean.isEmpty() || clean.length > 220) return
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(clean)
            if (sampleOut.size < 2 && !REGEX_PURE_TIME.matches(clean) && !sampleOut.contains(clean)) {
                sampleOut += clean
            }
        }

        fun visit(cur: AccessibilityNodeInfo) {
            if (visited >= MAX_TRAVERSE_NODES) return
            visited++

            appendPiece(cur.text)
            appendPiece(cur.contentDescription)

            val count = cur.childCount
            for (i in 0 until count) {
                if (visited >= MAX_TRAVERSE_NODES) break
                val child = cur.getChild(i) ?: continue
                visit(child)
            }
        }

        visit(node)
        return visited
    }

    private fun hasRealSpec(spec: String): Boolean {
        return spec.contains("Bit", ignoreCase = true) || spec.contains("k", ignoreCase = true)
    }

    private data class ParsedAccessibilityAudio(
        val codec: String,
        val spec: String,
        val rawMatch: String
    )

    private fun parseConcatenatedBuffer(fullText: String): ParsedAccessibilityAudio? {
        val codec = REGEX_CODEC.find(fullText)?.groupValues?.get(1)?.uppercase()?.take(7)

        val bitMatch = REGEX_BIT_DEPTH.find(fullText)
        val bitDepth = bitMatch?.groupValues?.get(1)

        var rateMatchText = ""
        var shortRateInt: String? = null
        val khzMatch = REGEX_SAMPLE_RATE.find(fullText)
        if (khzMatch != null) {
            val num = khzMatch.groupValues[1].toFloatOrNull()
            if (num != null && num in 22f..768f) {
                shortRateInt = num.toInt().toString()
                rateMatchText = khzMatch.value
            }
        }
        if (shortRateInt == null) {
            val rawHzMatch = REGEX_SAMPLE_RATE_HZ.find(fullText)
            if (rawHzMatch != null) {
                val hz = rawHzMatch.groupValues[1].toIntOrNull()
                if (hz != null) {
                    shortRateInt = (hz / 1000).toString()
                    rateMatchText = rawHzMatch.value
                }
            }
        }

        val spec = when {
            shortRateInt != null && bitDepth != null -> {
                val full = "${shortRateInt}k ${bitDepth}Bit"
                if (full.length <= 10) full else "${shortRateInt}k ${bitDepth}b"
            }
            bitDepth != null -> "${bitDepth}Bit"
            shortRateInt != null -> "${shortRateInt}kHz"
            else -> return null
        }

        val rawSnippet = listOfNotNull(
            codec,
            bitMatch?.value,
            rateMatchText.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        return ParsedAccessibilityAudio(
            codec = codec ?: "FLAC",
            spec = spec,
            rawMatch = rawSnippet
        )
    }
}
