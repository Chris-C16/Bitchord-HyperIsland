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
        private const val SCAN_THROTTLE_MS = 700L
        private const val MAX_TRAVERSE_NODES = 240
        private const val MAX_NO_SPEC_ATTEMPTS_PER_TRACK = 3

        private val REGEX_CODEC = Regex("""\b(flac|alac|wav|mqa|dsd\d*|aac|opus|mp3)\b""", RegexOption.IGNORE_CASE)
        private val REGEX_BIT_DEPTH = Regex("""(\d{2})\s*-?\s*bit\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE = Regex("""(\d{2,3}(?:\.\d)?)\s*k?hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_SAMPLE_RATE_HZ = Regex("""\b(384000|352800|192000|176400|96000|88200|48000|44100)\s*hz\b""", RegexOption.IGNORE_CASE)
        private val REGEX_HIRES_LOSSLESS = Regex("""hi[\s_-]*res(?:\s+lossless)?""", RegexOption.IGNORE_CASE)
        private val REGEX_LOSSLESS = Regex("""\blossless\b""", RegexOption.IGNORE_CASE)
        private val REGEX_AUDIO_CONTEXT = Regex(
            """(?i)\b(flac|alac|wav|mqa|dsd\d*|aac|opus|mp3|lossless|hi[\s_-]*res|stereo|kbps|khz|bit)\b"""
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
    private var noSpecAttempts = 0

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
        // Filtro immediato anti-consumo: ignora ogni app diversa da BitChord senza leggere nodi
        val pkg = event?.packageName?.toString().orEmpty()
        if (!isBitChordPackage(pkg)) return

        val title = LyricsPlaybackStore.title.value
        val artist = LyricsPlaybackStore.artist.value
        val trackKey = LyricsPlaybackStore.buildTrackQualityKey(title, artist)
        if (trackKey.isBlank()) return

        // Se la qualità reale per questo brano è già in cache, return immediato (zero lavoro)
        val cached = AppPreferences.getCachedBitChordQuality(applicationContext, trackKey)
        if (cached != null) {
            if (hasNumericAudioSpec(cached.second)) return
            if (trackKey == lastAttemptedTrackKey && noSpecAttempts >= MAX_NO_SPEC_ATTEMPTS_PER_TRACK) {
                return
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRealtimeMs < SCAN_THROTTLE_MS) return

        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return

        lastScanRealtimeMs = now
        if (trackKey != lastAttemptedTrackKey) {
            lastAttemptedTrackKey = trackKey
            noSpecAttempts = 1
        } else {
            noSpecAttempts++
        }

        val detected = scanWindowForAudioQuality(root) ?: return
        val (codec, spec) = detected

        // Evita di sovrascrivere una specifica numerica già nota con un tag meno specifico
        if (cached != null && hasNumericAudioSpec(cached.second) && !hasNumericAudioSpec(spec)) {
            return
        }
        if (cached != null && cached.first == codec && cached.second == spec) {
            return
        }

        AppPreferences.putCachedBitChordQuality(applicationContext, trackKey, codec, spec)
        LyricsPlaybackStore.audioCodecBadge.value = codec
        LyricsPlaybackStore.audioSpecBadge.value = spec
        BitChordListenerService.onBitChordQualityDetected(applicationContext, trackKey)
    }

    private fun isBitChordPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg.contains("bitchord", ignoreCase = true)) return true
        val activePkg = LyricsPlaybackStore.activePlayerPackage.value
        return activePkg.isNotBlank() &&
            activePkg.contains("bitchord", ignoreCase = true) &&
            pkg.equals(activePkg, ignoreCase = true)
    }

    private fun hasNumericAudioSpec(spec: String): Boolean {
        return spec.contains("Bit", ignoreCase = true) || spec.contains("k", ignoreCase = true)
    }

    private fun scanWindowForAudioQuality(root: AccessibilityNodeInfo): Pair<String, String>? {
        val candidateTexts = ArrayList<String>(16)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_TRAVERSE_NODES) {
            val node = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && text.length <= 160 && REGEX_AUDIO_CONTEXT.containsMatchIn(text)) {
                candidateTexts += text
            }
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc.length <= 160 && REGEX_AUDIO_CONTEXT.containsMatchIn(desc)) {
                candidateTexts += desc
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }

        if (candidateTexts.isEmpty()) return null
        return parseAudioQualityCandidates(candidateTexts)
    }

    private fun parseAudioQualityCandidates(candidates: List<String>): Pair<String, String>? {
        var codec: String? = null
        var bitDepth: String? = null
        var rateKhz: String? = null
        var isHiRes = false
        var isLossless = false

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
            if (!isHiRes && REGEX_HIRES_LOSSLESS.containsMatchIn(text)) {
                isHiRes = true
            }
            if (!isLossless && REGEX_LOSSLESS.containsMatchIn(text)) {
                isLossless = true
            }
        }

        if (codec == null && bitDepth == null && rateKhz == null && !isHiRes && !isLossless) {
            return null
        }

        val resolvedCodec = codec ?: "FLAC"
        val resolvedSpec = formatCompactSpec(rateKhz, bitDepth, isHiRes)
        return resolvedCodec to resolvedSpec
    }

    private fun formatCompactSpec(rateKhz: String?, bitDepth: String?, isHiRes: Boolean): String {
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
            isHiRes -> "Hi-Res"
            else -> "Lossless"
        }
    }
}
