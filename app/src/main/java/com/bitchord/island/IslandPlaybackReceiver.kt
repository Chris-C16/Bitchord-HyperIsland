package com.bitchord.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

class IslandPlaybackReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE_PLAYBACK = "com.bitchord.island.ACTION_TOGGLE_PLAYBACK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TOGGLE_PLAYBACK) return

        val audioManager = context.getSystemService(AudioManager::class.java)
        val wasPlaying = LyricsPlaybackStore.isPlaying.value
        val callback = LyricsPlaybackStore.onPlayPause

        if (callback != null) {
            callback.invoke()

            // Rete di sicurezza: se eravamo in pausa e la sessione di BitChord era morta,
            // dopo 450ms invia KEYCODE_MEDIA_PLAY (idempotente) per risvegliare il player
            if (!wasPlaying && audioManager != null) {
                val pendingResult = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (!LyricsPlaybackStore.isPlaying.value && !audioManager.isMusicActive) {
                            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }, 450L)
            }
        } else if (audioManager != null) {
            val keyCode = if (wasPlaying) {
                KeyEvent.KEYCODE_MEDIA_PAUSE
            } else {
                KeyEvent.KEYCODE_MEDIA_PLAY
            }
            dispatchMediaKey(audioManager, keyCode)
        }
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
