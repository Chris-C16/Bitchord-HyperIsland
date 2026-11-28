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
        private const val SCAN_THROTTLE_MS = 650L
        private const val MAX_TRAVERSE_NODES = 280
        private const val MAX_ATTEMPTS_BEFORE_FOUND = 6

        private val REGEX_CODEC = Regex("""\b(flac|alac|wav|mqa|dsd\d*|aac|opus|mp3)\b""", RegexOption.IGNORE_CASE)
        private val REGEX_BIT_DEPTH = Regex("""(\d{2})\s*-?\s*bit\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE = Regex("""(\d{2,3}(?:\.\d)?)\s*k?hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE_HZ = Regex("""\b(384000|352800|192000|176400|96000|88200|48000|44100)\s*hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_AUDIO_CONTEXT = Regex(
            """(?i)(?:\b(flac|alac|wav|mqa|dsd\d*|aac|opus|mp3|stereo|mono)\b|\d+\s*-?\s*bit\b|\d+(?:\.\d)?\s*k?hz\b|\d+\s*kbps\b)"""
        )
        private val REGEX_RAW_SPEC_HINT = Regex(
            """(?i)(?:\d+\s*-?\s*bit\b|\d+(?:\.\d)?\s*k?hz\b)"""
        )

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

        if (trackKey == lastAttemptedTrackKey && attemptsForTrack >= MAX_ATTEMPTS_BEFORE_FOUND &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
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

        val candidates = ArrayList<String>(16)
        for (root in roots) {
            scanSubtreeRecursive(root, candidates)
        }
        if (candidates.isEmpty()) return

        val rawMatch = candidates.firstOrNull { REGEX_RAW_SPEC_HINT.containsMatchIn(it) }
            ?: candidates.firstOrNull()
        if (!rawMatch.isNullOrBlank()) {
            val combinedRaw = candidates.filter { REGEX_RAW_SPEC_HINT.containsMatchIn(it) }
                .distinct()
                .joinToString(" | ")
                .ifBlank { rawMatch }
            LyricsPlaybackStore.lastAccessibilityRawMatch.value = combinedRaw.take(64)
        }

        val parsed = parseAudioQualityCandidates(candidates) ?: return
        val (codec, spec) = parsed
        if (!hasRealSpec(spec)) return

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
        val seenHashes = HashSet<Int>(4)

        fun tryAddRoot(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val nodePkg = node.packageName?.toString().orEmpty()
            if (isAcceptedPackage(nodePkg) || (nodePkg.isBlank() && isAcceptedPackage(eventPkg))) {
                val id = System.identityHashCode(node)
                if (seenHashes.add(id)) {
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

    private fun scanSubtreeRecursive(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (out.size >= 48) return
        var visited = 0

        fun visit(cur: AccessibilityNodeInfo) {
            if (visited >= MAX_TRAVERSE_NODES || out.size >= 48) return
            visited++

            val text = cur.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && text.length <= 180 && REGEX_AUDIO_CONTEXT.containsMatchIn(text)) {
                out += text
            }
            val desc = cur.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc.length <= 180 && REGEX_AUDIO_CONTEXT.containsMatchIn(desc)) {
                out += desc
            }

            val count = cur.childCount
            for (i in 0 until count) {
                if (visited >= MAX_TRAVERSE_NODES) break
                val child = cur.getChild(i) ?: continue
                visit(child)
            }
        }

        visit(node)
    }

    private fun hasRealSpec(spec: String): Boolean {
        return spec.contains("Bit", ignoreCase = true) || spec.contains("k", ignoreCase = true)
    }

    private fun parseAudioQualityCandidates(candidates: List<String>): Pair<String, String>? {
        var codec: String? = null
        var bitDepth: String? = null
        var rateKhz: String? = null

        for (text in candidates) {
            if (codec == null) {
                REGEX_CODEC.find(text)?.let { match ->
                    codec = match.groupValues[1].uppercase().take(7)
                }
            }
            if (bitDepth == null) {
                REGEX_BIT_DEPTH.find(text)?.let { match ->
                    val bits = match.groupValues[1].toIntOrNull()
                    if (bits in setOf(16, 24, 32, 64)) {
                        bitDepth = match.groupValues[1]
                    }
                }
            }
            if (rateKhz == null) {
                REGEX_SAMPLE_RATE_HZ.find(text)?.let { match ->
                    rateKhz = when (match.groupValues[1]) {
                        "44100" -> "44.1"
                        "48000" -> "48"
                        "88200" -> "88.2"
                        "96000" -> "96"
                        "176400" -> "176.4"
                        "192000" -> "192"
                        "352800" -> "352.8"
                        "384000" -> "384"
                        else -> null
                    }
                }
            }
            if (rateKhz == null) {
                REGEX_SAMPLE_RATE.find(text)?.let { match ->
                    if (match.value.contains("k", ignoreCase = true)) {
                        val raw = match.groupValues[1]
                        val num = raw.toFloatOrNull()
                        if (num != null && num in 22f..768f) {
                            rateKhz = if (raw.endsWith(".0")) raw.removeSuffix(".0") else raw
                        }
                    }
                }
            }
        }

        val spec = formatCompactSpec(rateKhz, bitDepth) ?: return null
        val resolvedCodec = codec ?: "FLAC"
        return resolvedCodec to spec
    }

    private fun formatCompactSpec(rateKhz: String?, bitDepth: String?): String? {
        val cleanRate = rateKhz?.let { if (it.endsWith(".0")) it.removeSuffix(".0") else it }
        return when {
            cleanRate != null && bitDepth != null -> {
                val full = "${cleanRate}k ${bitDepth}Bit"
                if (full.length <= 10) {
                    full
                } else {
                    val intRate = cleanRate.substringBefore('.')
                    val compact = "${intRate}k ${bitDepth}Bit"
                    if (compact.length <= 10) compact else "${intRate}k ${bitDepth}b"
                }
            }
            bitDepth != null -> "${bitDepth}Bit"
            cleanRate != null -> {
                val solo = "${cleanRate}kHz"
                if (solo.length <= 10) solo else "${cleanRate.substringBefore('.')}kHz"
            }
            else -> null
        }
    }
}
