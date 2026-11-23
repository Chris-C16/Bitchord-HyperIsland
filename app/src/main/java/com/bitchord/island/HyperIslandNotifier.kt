package com.bitchord.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb

class HyperIslandNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "bitchord_lyrics_live_pill"
        const val NOTIFICATION_ID = 3601
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HyperIsland Lyrics (Live)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Seconda pillola HyperIsland dedicata ai testi"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun createLyricsPendingIntent(): PendingIntent {
        val openLyricsIntent = Intent(context, LyricsPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 10, openLyricsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or Intent.FLAG_IMMUTABLE
        )
    }

    /**
     * Formatta 3 versi consecutivi nella card espansa:
     * - Titolo (grassetto): Verso attivo corrente
     * - Corpo (2 paragrafi su due righe con \n): Verso +1 e Verso +2
     */
    private fun formatMultiParagraphLyrics(
        currentLine: LyricLine?,
        nextLyric1: String,
        nextLyric2: String,
        title: String,
        artist: String
    ): Pair<String, String> {
        val active = currentLine?.fullText?.trim().orEmpty()
        if (active.isBlank()) {
            return title to "$artist\nIn attesa del testo sincronizzato..."
        }

        val p1 = nextLyric1.trim()
        val p2 = nextLyric2.trim()

        val bottomParagraphs = when {
            p1.isNotBlank() && p2.isNotBlank() -> "$p1\n$p2"
            p1.isNotBlank() -> "$p1\n♪ $title"
            else -> "$title — $artist"
        }

        return active to bottomParagraphs
    }

    fun buildNotification(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        nextLyric2: String,
        palette: CoverPalette,
        songPositionMs: Long,
        isPlaying: Boolean
    ): Notification {
        val (topLine, bottomParagraphs) = formatMultiParagraphLyrics(
            currentLine, nextLyric1, nextLyric2, title, artist
        )

        // Nella pillola chiusa a destra della fotocamera (max ~15 caratteri netti senza tagli bruschi)
        val miniPillText = buildMiniPillText(currentLine, songPositionMs, title)

        val lineProgress = if (currentLine != null) {
            val dur = (currentLine.endMs - currentLine.startMs).coerceAtLeast(300L)
            (((songPositionMs - currentLine.startMs).toFloat() / dur) * 100f).toInt().coerceIn(0, 100)
        } else 0

        val accentColor = palette.accent.toArgb()
        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_note)
            .setContentTitle(topLine)
            .setContentText(bottomParagraphs)
            .setSubText("$title • $artist")
            .setColor(accentColor)
            .setColorized(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(createLyricsPendingIntent())
            .addExtras(extras)

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, miniPillText)

                val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()
                progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(progressStyle, lineProgress)
                progressStyleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
                    .invoke(progressStyle, true)

                Notification.Builder::class.java
                    .getMethod("setStyle", Notification.Style::class.java)
                    .invoke(builder, progressStyle)
            } catch (_: Throwable) {
                builder.setProgress(100, lineProgress, false)
            }
        } else {
            builder.setProgress(100, lineProgress, false)
        }

        return builder.build()
    }

    private fun buildMiniPillText(currentLine: LyricLine?, songPositionMs: Long, fallbackTitle: String): String {
        val full = currentLine?.fullText?.trim().orEmpty()
        if (full.isBlank()) return trimWord(fallbackTitle, 15)
        if (full.length <= 15) return full

        val dur = (currentLine!!.endMs - currentLine.startMs).coerceAtLeast(1L)
        val progress = ((songPositionMs - currentLine.startMs).toFloat() / dur).coerceIn(0f, 1f)
        return if (progress < 0.48f) {
            trimWord(full, 15)
        } else {
            val start = (full.length - 15).coerceAtLeast(0)
            val sub = full.substring(start)
            val sp = sub.indexOf(' ')
            if (sp in 1..7) sub.substring(sp + 1) else sub
        }
    }

    private fun trimWord(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val sub = text.substring(0, maxLen)
        val sp = sub.lastIndexOf(' ')
        return if (sp > maxLen / 2) sub.substring(0, sp) else sub
    }

    fun update(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLyric1: String,
        nextLyric2: String,
        palette: CoverPalette,
        songPositionMs: Long,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, artist, currentLine, nextLyric1, nextLyric2, palette, songPositionMs, isPlaying)
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)
}
