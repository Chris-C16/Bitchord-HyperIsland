package com.bitchord.island

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

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

    const val FACTORY_DEFAULT_OFFSET_MS = 600L

    @Volatile
    private var prefs: SharedPreferences? = null

    val preferredPlayer = MutableStateFlow(PreferredPlayer.AUTO)
    val defaultSyncOffsetMs = MutableStateFlow(FACTORY_DEFAULT_OFFSET_MS)
    val appInfoStepDone = MutableStateFlow(false)
    val lastActiveMusicPackage = MutableStateFlow("")

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        preferredPlayer.value = PreferredPlayer.fromId(sp.getString(KEY_PLAYER_ID, PreferredPlayer.AUTO.id))
        defaultSyncOffsetMs.value = sp.getLong(KEY_DEFAULT_OFFSET_MS, FACTORY_DEFAULT_OFFSET_MS)
        appInfoStepDone.value = sp.getBoolean(KEY_APP_INFO_DONE, false)
        lastActiveMusicPackage.value = sp.getString(KEY_LAST_ACTIVE_PKG, "").orEmpty()
        LyricsPlaybackStore.syncOffsetMs.value = defaultSyncOffsetMs.value
    }

    fun setPreferredPlayer(context: Context, player: PreferredPlayer) {
        init(context)
        preferredPlayer.value = player
        prefs?.edit()?.putString(KEY_PLAYER_ID, player.id)?.apply()
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
