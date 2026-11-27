package com.bitchord.island

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.PrintWriter
import java.io.StringWriter

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

    const val FACTORY_DEFAULT_OFFSET_MS = 600L

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var crashHandlerInstalled = false

    val preferredPlayer = MutableStateFlow(PreferredPlayer.AUTO)
    val defaultSyncOffsetMs = MutableStateFlow(FACTORY_DEFAULT_OFFSET_MS)
    val appInfoStepDone = MutableStateFlow(false)
    val lastActiveMusicPackage = MutableStateFlow("")
    val targetFps = MutableStateFlow(TargetFps.FPS_60)
    val lastUncaughtCrash = MutableStateFlow("")

    fun init(context: Context) {
        installCrashLogger(context)
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        preferredPlayer.value = PreferredPlayer.fromId(sp.getString(KEY_PLAYER_ID, PreferredPlayer.AUTO.id))
        defaultSyncOffsetMs.value = sp.getLong(KEY_DEFAULT_OFFSET_MS, FACTORY_DEFAULT_OFFSET_MS)
        appInfoStepDone.value = sp.getBoolean(KEY_APP_INFO_DONE, false)
        lastActiveMusicPackage.value = sp.getString(KEY_LAST_ACTIVE_PKG, "").orEmpty()
        targetFps.value = TargetFps.fromFps(sp.getInt(KEY_TARGET_FPS, TargetFps.FPS_60.fps))
        lastUncaughtCrash.value = sp.getString(KEY_LAST_CRASH, "").orEmpty()
        LyricsPlaybackStore.syncOffsetMs.value = defaultSyncOffsetMs.value
    }

    fun installCrashLogger(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()
                val summary = "[${thread.name}] ${sw.toString().trim().take(1800)}"
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, summary)
                    .commit()
                lastUncaughtCrash.value = summary
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
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
}
