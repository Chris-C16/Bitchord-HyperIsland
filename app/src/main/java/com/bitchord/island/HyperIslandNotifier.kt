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

        val pendingLyrics = createLyricsPendingIntent()
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

    private fun createLyricsPendingIntent(): PendingIntent {
        val openLyricsIntent = Intent(context, LyricsPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 10, openLyricsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Formatta il testo su 2 righe (Titolo + Sottotitolo della card HyperIsland)
     * evitando che i versi lunghi vengano tagliati con "..."
     */
    private fun formatTwoLines(currentLyric: String, nextLyric: String, title: String, artist: String): Pair<String, String> {
        if (currentLyric.isBlank()) {
            return title to artist
        }
        // Se il verso corrente è lungo (> 25 caratteri), lo spezziamo su entrambe le righe
        // così lo leggi tutto intero e chiaro senza troncamenti!
        if (currentLyric.length > 25) {
            val words = currentLyric.split(" ")
            val firstLine = StringBuilder()
            val secondLine = StringBuilder()
            for (w in words) {
                if (firstLine.length + w.length <= 24) {
                    if (firstLine.isNotEmpty()) firstLine.append(" ")
                    firstLine.append(w)
                } else {
                    if (secondLine.isNotEmpty()) secondLine.append(" ")
                    secondLine.append(w)
                }
            }
            val top = firstLine.toString().ifBlank { currentLyric.take(24) }
            val bottom = secondLine.toString().take(34)
            return top to bottom
        }

        // Altrimenti riga 1 = verso attivo, riga 2 = verso successivo intero (senza "• Titolo" che rubava spazio)
        val bottom = nextLyric.ifBlank { "$title — $artist" }.take(34)
        return currentLyric to bottom
    }

    fun buildNotification(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLyric: String,
        cover: Bitmap?,
        songDurationMs: Long,
        songPositionMs: Long,
        isPlaying: Boolean
    ): Notification {
        if (!mediaSession.isActive) mediaSession.isActive = true

        val (topLine, bottomLine) = formatTwoLines(
            currentLine?.fullText.orEmpty(),
            nextLyric,
            title,
            artist
        )

        // La barra di avanzamento della HyperIsland anima il progresso del SINGOLO VERSO corrente!
        val barDuration = if (currentLine != null) {
            (currentLine.endMs - currentLine.startMs).coerceAtLeast(400L)
        } else {
            songDurationMs.coerceAtLeast(1L)
        }
        val barPosition = if (currentLine != null) {
            (songPositionMs - currentLine.startMs).coerceIn(0L, barDuration)
        } else {
            songPositionMs.coerceAtLeast(0L)
        }

        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, topLine)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, bottomLine)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "$title • $artist")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, barDuration)

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
                .setState(stateCode, barPosition, if (isPlaying) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )

        val pendingLyrics = createLyricsPendingIntent()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(topLine)
            .setContentText(bottomLine)
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
        currentLine: LyricLine?,
        nextLyric: String,
        cover: Bitmap?,
        songDurationMs: Long,
        songPositionMs: Long,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, artist, currentLine, nextLyric, cover, songDurationMs, songPositionMs, isPlaying)
        )
    }

    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
        manager.cancel(NOTIFICATION_ID)
    }
}
