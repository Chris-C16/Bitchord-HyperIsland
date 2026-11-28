package com.bitchord.island

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

enum class TargetFps(val fps: Int, val label: String) {
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS"),
    FPS_120(120, "120 FPS");

    companion object {
        fun fromFps(value: Int): TargetFps {
            return entries.firstOrNull { it.fps == value } ?: FPS_60
        }
    }
}

enum class PreferredPlayer(
    val id: String,
    val label: String,
    val packageHints: List<String>,
    val defaultPackage: String?
) {
    AUTO("auto", "Automatico", emptyList(), null),
    BITCHORD("bitchord", "BitChord", listOf("bitchord"), null),
    SPOTIFY("spotify", "Spotify", listOf("com.spotify.music", "spotify"), "com.spotify.music"),
    YOUTUBE_MUSIC("ytmusic", "YT Music", listOf("com.google.android.apps.youtube.music"), "com.google.android.apps.youtube.music"),
    APPLE_MUSIC("applemusic", "Apple Music", listOf("com.apple.android.music"), "com.apple.android.music");

    fun matches(packageName: String): Boolean {
        if (this == AUTO) return true
        val lower = packageName.lowercase()
        return packageHints.any { lower.contains(it) }
    }

    companion object {
        fun fromId(id: String?): PreferredPlayer {
            return entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}

object AppPreferences {
    private const val PREFS_NAME = "dynamic_lyrics_prefs"
    private const val KEY_PLAYER_ID = "preferred_player_id"
    private const val KEY_DEFAULT_OFFSET_MS = "default_sync_offset_ms"
    private const val KEY_APP_INFO_DONE = "app_info_step_done"
    private const val KEY_LAST_ACTIVE_PKG = "last_active_music_pkg"
    private const val KEY_LAST_PKG_PREFIX = "last_pkg_"
    private const val KEY_TARGET_FPS = "target_fps"
    private const val KEY_LAST_CRASH = "last_uncaught_crash"
    private const val KEY_SHADER_SAFE_MODE = "shader_safe_mode"
    private const val KEY_SHADER_PENDING_FRAME = "shader_pending_frame"
    private const val KEY_BITCHORD_QUALITY_LRU = "bitchord_quality_lru_v1"
    private const val MAX_QUALITY_CACHE_ENTRIES = 100

    const val FACTORY_DEFAULT_OFFSET_MS = 600L

    private val qualityLock = Any()
    private val qualityCache = object : LinkedHashMap<String, Pair<String, String>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?): Boolean {
            return size > MAX_QUALITY_CACHE_ENTRIES
        }
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var crashHandlerInstalled = false

    @Volatile
    private var crashInProgress = false

    val preferredPlayer = MutableStateFlow(PreferredPlayer.AUTO)
    val defaultSyncOffsetMs = MutableStateFlow(FACTORY_DEFAULT_OFFSET_MS)
    val appInfoStepDone = MutableStateFlow(false)
    val lastActiveMusicPackage = MutableStateFlow("")
    val targetFps = MutableStateFlow(TargetFps.FPS_60)
    val lastUncaughtCrash = MutableStateFlow("")
    val shaderSafeMode = MutableStateFlow(false)

    private fun safeString(sp: SharedPreferences, key: String, fallback: String): String {
        return try {
            sp.getString(key, fallback) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun safeLong(sp: SharedPreferences, key: String, fallback: Long): Long {
        return try {
            sp.getLong(key, fallback)
        } catch (_: Throwable) {
            try {
                sp.getInt(key, fallback.toInt()).toLong()
            } catch (_: Throwable) {
                fallback
            }
        }
    }

    private fun safeBoolean(sp: SharedPreferences, key: String, fallback: Boolean): Boolean {
        return try {
            sp.getBoolean(key, fallback)
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun safeInt(sp: SharedPreferences, key: String, fallback: Int): Int {
        return try {
            sp.getInt(key, fallback)
        } catch (_: Throwable) {
            fallback
        }
    }

    @Synchronized
    fun init(context: Context) {
        installCrashLogger(context)
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        preferredPlayer.value = PreferredPlayer.fromId(safeString(sp, KEY_PLAYER_ID, PreferredPlayer.AUTO.id))
        defaultSyncOffsetMs.value = safeLong(sp, KEY_DEFAULT_OFFSET_MS, FACTORY_DEFAULT_OFFSET_MS)
        appInfoStepDone.value = safeBoolean(sp, KEY_APP_INFO_DONE, false)
        lastActiveMusicPackage.value = safeString(sp, KEY_LAST_ACTIVE_PKG, "")
        targetFps.value = TargetFps.fromFps(safeInt(sp, KEY_TARGET_FPS, TargetFps.FPS_60.fps))
        lastUncaughtCrash.value = safeString(sp, KEY_LAST_CRASH, "")
        loadQualityCacheLocked(sp)

        val wasPending = safeBoolean(sp, KEY_SHADER_PENDING_FRAME, false)
        val savedSafeMode = safeBoolean(sp, KEY_SHADER_SAFE_MODE, false)
        if (wasPending) {
            try {
                sp.edit()
                    .putBoolean(KEY_SHADER_PENDING_FRAME, false)
                    .putBoolean(KEY_SHADER_SAFE_MODE, true)
                    .commit()
            } catch (_: Throwable) {
            }
            shaderSafeMode.value = true
        } else {
            shaderSafeMode.value = savedSafeMode
        }

        LyricsPlaybackStore.syncOffsetMs.value = defaultSyncOffsetMs.value
    }

    fun markShaderPendingFrameSync(context: Context, pending: Boolean) {
        init(context)
        try {
            prefs?.edit()?.putBoolean(KEY_SHADER_PENDING_FRAME, pending)?.commit()
        } catch (_: Throwable) {
        }
    }

    fun setShaderSafeMode(context: Context, enabled: Boolean) {
        init(context)
        shaderSafeMode.value = enabled
        try {
            prefs?.edit()
                ?.putBoolean(KEY_SHADER_SAFE_MODE, enabled)
                ?.putBoolean(KEY_SHADER_PENDING_FRAME, false)
                ?.commit()
        } catch (_: Throwable) {
        }
    }

    fun installCrashLogger(context: Context) {
        if (crashHandlerInstalled) return
        val procName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Application.getProcessName()
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        if (procName.endsWith(":crash")) return

        crashHandlerInstalled = true
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (crashInProgress) {
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            crashInProgress = true

            var launchedCrashUi = false
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()

                val fullReport = buildString {
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT})\n")
                    append("Thread: ${thread.name}\n\n")
                    append(sw.toString().trim())
                }

                try {
                    appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST_CRASH, fullReport.take(2200))
                        .commit()
                    lastUncaughtCrash.value = fullReport.take(2200)
                } catch (_: Throwable) {
                }

                val reportIntent = Intent(appCtx, CrashReportActivity::class.java).apply {
                    putExtra(CrashReportActivity.EXTRA_STACKTRACE, fullReport.take(16_000))
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                appCtx.startActivity(reportIntent)
                launchedCrashUi = true
                try {
                    Thread.sleep(140L)
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            }

            if (launchedCrashUi) {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun clearLastCrash(context: Context) {
        init(context)
        lastUncaughtCrash.value = ""
        prefs?.edit()?.remove(KEY_LAST_CRASH)?.apply()
    }

    fun setPreferredPlayer(context: Context, player: PreferredPlayer) {
        init(context)
        preferredPlayer.value = player
        prefs?.edit()?.putString(KEY_PLAYER_ID, player.id)?.apply()
    }

    fun setTargetFps(context: Context, fps: TargetFps) {
        init(context)
        targetFps.value = fps
        prefs?.edit()?.putInt(KEY_TARGET_FPS, fps.fps)?.apply()
    }

    fun setDefaultSyncOffsetMs(context: Context, offsetMs: Long) {
        init(context)
        val clamped = offsetMs.coerceIn(-5000L, 5000L)
        defaultSyncOffsetMs.value = clamped
        LyricsPlaybackStore.syncOffsetMs.value = clamped
        prefs?.edit()?.putLong(KEY_DEFAULT_OFFSET_MS, clamped)?.apply()
    }

    fun markAppInfoDone(context: Context, done: Boolean = true) {
        init(context)
        appInfoStepDone.value = done
        prefs?.edit()?.putBoolean(KEY_APP_INFO_DONE, done)?.apply()
    }

    fun rememberLastActivePackage(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        init(context)
        lastActiveMusicPackage.value = packageName
        val editor = prefs?.edit()?.putString(KEY_LAST_ACTIVE_PKG, packageName)
        PreferredPlayer.entries.forEach { player ->
            if (player != PreferredPlayer.AUTO && player.matches(packageName)) {
                editor?.putString("$KEY_LAST_PKG_PREFIX${player.id}", packageName)
            }
        }
        editor?.apply()
    }

    fun getLaunchTargetPackage(context: Context): String? {
        init(context)
        val preferred = preferredPlayer.value
        if (preferred != PreferredPlayer.AUTO) {
            return prefs?.getString("$KEY_LAST_PKG_PREFIX${preferred.id}", null)
                ?: preferred.defaultPackage
                ?: lastActiveMusicPackage.value.takeIf { it.isNotBlank() }
        }
        return lastActiveMusicPackage.value.takeIf { it.isNotBlank() }
    }

    private fun isRealRateOrBitSpec(spec: String): Boolean {
        if (spec.isBlank()) return false
        return Regex("""\d+\s*(?:k|khz|bit|b)\b""", RegexOption.IGNORE_CASE).containsMatchIn(spec)
    }

    private fun loadQualityCacheLocked(sp: SharedPreferences) {
        val raw = safeString(sp, KEY_BITCHORD_QUALITY_LRU, "")
        if (raw.isBlank()) return
        synchronized(qualityLock) {
            qualityCache.clear()
            try {
                val arr = JSONArray(raw)
                val start = (arr.length() - MAX_QUALITY_CACHE_ENTRIES).coerceAtLeast(0)
                for (i in start until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val k = obj.optString("k")
                    val c = obj.optString("c")
                    val s = obj.optString("s")
                    if (k.isNotBlank() && c.isNotBlank() && isRealRateOrBitSpec(s)) {
                        qualityCache[k] = c to s
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun getCachedBitChordQuality(context: Context, trackKey: String): Pair<String, String>? {
        if (trackKey.isBlank()) return null
        init(context)
        return synchronized(qualityLock) {
            qualityCache[trackKey]?.takeIf { isRealRateOrBitSpec(it.second) }
        }
    }

    fun putCachedBitChordQuality(
        context: Context,
        trackKey: String,
        codec: String,
        spec: String
    ) {
        if (trackKey.isBlank() || codec.isBlank() || !isRealRateOrBitSpec(spec)) return
        init(context)
        val serialized = synchronized(qualityLock) {
            qualityCache[trackKey] = codec to spec
            val arr = JSONArray()
            for ((k, v) in qualityCache) {
                arr.put(
                    JSONObject().apply {
                        put("k", k)
                        put("c", v.first)
                        put("s", v.second)
                    }
                )
            }
            arr.toString()
        }
        try {
            prefs?.edit()?.putString(KEY_BITCHORD_QUALITY_LRU, serialized)?.apply()
        } catch (_: Throwable) {
        }
    }
}
