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
    BITCHORD("bitchord", "BitChord", listOf("bitchord"), null),
    SPOTIFY("spotify", "Spotify", listOf("com.spotify.music", "spotify"), "com.spotify.music"),
    YOUTUBE_MUSIC("ytmusic", "YT Music", listOf("com.google.android.apps.youtube.music"), "com.google.android.apps.youtube.music"),
    APPLE_MUSIC("applemusic", "Apple Music", listOf("com.apple.android.music"), "com.apple.android.music"),
    AUTO("auto", "Automatico", emptyList(), null);

    fun matches(packageName: String): Boolean {
        if (this == AUTO) return true
        val lower = packageName.lowercase()
        return packageHints.any { lower.contains(it) }
    }

    companion object {
        fun fromId(id: String?): PreferredPlayer {
            return entries.firstOrNull { it.id == id } ?: BITCHORD
        }
    }
}

object AppPreferences {
    private const val PREFS_NAME = "dynamic_lyrics_prefs"
    private const val KEY_PLAYER_ID = "preferred_player_id"
    private const val KEY_DEFAULT_OFFSET_MS = "default_sync_offset_ms"
    private const val KEY_APP_INFO_DONE = "app_info_step_done"
    private const val KEY_LAST_PKG_PREFIX = "last_pkg_"

    const val FACTORY_DEFAULT_OFFSET_MS = 600L

    @Volatile
    private var prefs: SharedPreferences? = null

    val preferredPlayer = MutableStateFlow(PreferredPlayer.BITCHORD)
    val defaultSyncOffsetMs = MutableStateFlow(FACTORY_DEFAULT_OFFSET_MS)
    val appInfoStepDone = MutableStateFlow(false)

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        preferredPlayer.value = PreferredPlayer.fromId(sp.getString(KEY_PLAYER_ID, PreferredPlayer.BITCHORD.id))
        defaultSyncOffsetMs.value = sp.getLong(KEY_DEFAULT_OFFSET_MS, FACTORY_DEFAULT_OFFSET_MS)
        appInfoStepDone.value = sp.getBoolean(KEY_APP_INFO_DONE, false)
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

    fun rememberPackageFor(context: Context, player: PreferredPlayer, packageName: String) {
        if (player == PreferredPlayer.AUTO || packageName.isBlank()) return
        init(context)
        prefs?.edit()?.putString("$KEY_LAST_PKG_PREFIX${player.id}", packageName)?.apply()
    }

    fun getRememberedPackageFor(context: Context, player: PreferredPlayer): String? {
        init(context)
        return prefs?.getString("$KEY_LAST_PKG_PREFIX${player.id}", null)
            ?: player.defaultPackage
    }
}
