package com.bitchord.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

class HyperIslandNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "bitchord_media_island"
        const val NOTIFICATION_ID = 3601
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    val mediaSession = MediaSession(context, "BitChordHyperIslandLyrics")

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HyperIsland Lyrics Player",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Pillola HyperIsland con testi sincronizzati"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        val openLyricsIntent = Intent(context, LyricsPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLyrics = PendingIntent.getActivity(
            context, 10, openLyricsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Toccare la card / pillola HyperIsland apre il player dei testi invece del player musica!
        mediaSession.setSessionActivity(pendingLyrics)
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { LyricsPlaybackStore.onPlayPause?.invoke() }
            override fun onPause() { LyricsPlaybackStore.onPlayPause?.invoke() }
            override fun onSkipToNext() { LyricsPlaybackStore.onNext?.invoke() }
            override fun onSkipToPrevious() { LyricsPlaybackStore.onPrevious?.invoke() }
            override fun onSeekTo(pos: Long) { LyricsPlaybackStore.onSeekTo?.invoke(pos) }
        })
        mediaSession.isActive = true
    }

    fun buildNotification(
        title: String,
        artist: String,
        currentLyric: String,
        nextLyric: String,
        cover: Bitmap?,
        durationMs: Long,
        positionMs: Long,
        isPlaying: Boolean
    ): Notification {
        val activeLine = currentLyric.ifBlank { title }
        val subLine = if (nextLyric.isNotBlank()) "$nextLyric  •  $title" else "$title — $artist"

        // Aggiorna i metadati della MediaSession: HyperOS li proietta direttamente nella HyperIsland
        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, activeLine)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subLine)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "$title • $artist")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(1L))

        if (cover != null) {
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
        }
        mediaSession.setMetadata(metaBuilder.build())

        val stateCode = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO

        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, positionMs, if (isPlaying) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )

        val openLyricsIntent = Intent(context, LyricsPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLyrics = PendingIntent.getActivity(
            context, 10, openLyricsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(activeLine)
            .setContentText(subLine)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pendingLyrics)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
            )

        if (cover != null) builder.setLargeIcon(cover)
        return builder.build()
    }

    fun update(
        title: String,
        artist: String,
        currentLyric: String,
        nextLyric: String,
        cover: Bitmap?,
        durationMs: Long,
        positionMs: Long,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, artist, currentLyric, nextLyric, cover, durationMs, positionMs, isPlaying)
        )
    }

    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
        manager.cancel(NOTIFICATION_ID)
    }
}
