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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Senza cover grande a destra, la card ha tutta la larghezza disponibile (~42 caratteri per riga).
     * Se il verso supera i 42 caratteri, lo spezza in modo pulito tra riga 1 e riga 2.
     */
    private fun formatFullWidthLines(
        currentLine: LyricLine?,
        nextLyric: String,
        title: String,
        artist: String
    ): Pair<String, String> {
        val full = currentLine?.fullText?.trim().orEmpty()
        if (full.isBlank()) return "$title — $artist" to "In attesa del testo..."

        if (full.length <= 42) {
            val second = nextLyric.trim().ifBlank { "$title • $artist" }.take(46)
            return full to second
        }

        // Verso lungo: distribuisce le parole su tutta la larghezza delle due righe senza mischiare altri versi
        val words = full.split(" ")
        val line1 = StringBuilder()
        val line2 = StringBuilder()
        for (w in words) {
            if (line1.length + w.length + 1 <= 40) {
                if (line1.isNotEmpty()) line1.append(" ")
                line1.append(w)
            } else {
                if (line2.length + w.length + 1 <= 44) {
                    if (line2.isNotEmpty()) line2.append(" ")
                    line2.append(w)
                }
            }
        }
        return line1.toString() to line2.toString()
    }

    fun buildNotification(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLyric: String,
        palette: CoverPalette,
        songPositionMs: Long,
        isPlaying: Boolean
    ): Notification {
        val (topLine, bottomLine) = formatFullWidthLines(currentLine, nextLyric, title, artist)

        // Testo compatto per la pillola chiusa in cima (scorrevole sul verso attivo)
        val miniPillText = buildMiniPillText(currentLine, songPositionMs, title)

        // Progresso (0..100) del singolo verso per la barra animata nella card espansa
        val lineProgress = if (currentLine != null) {
            val dur = (currentLine.endMs - currentLine.startMs).coerceAtLeast(300L)
            (((songPositionMs - currentLine.startMs).toFloat() / dur) * 100f).toInt().coerceIn(0, 100)
        } else 0

        val accentColor = palette.accent.toArgb()
        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(topLine)
            .setContentText(bottomLine)
            .setColor(accentColor)
            .setColorized(false)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(createLyricsPendingIntent())
            .addExtras(extras)

        // API ufficiali Android 16 Live Updates (seconda pillola Super Island)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, miniPillText)

                // Applica Notification.ProgressStyle nativo di Android 16 se disponibile
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
        if (full.isBlank()) return fallbackTitle.take(18)
        if (full.length <= 20) return full

        val dur = (currentLine!!.endMs - currentLine.startMs).coerceAtLeast(1L)
        val progress = ((songPositionMs - currentLine.startMs).toFloat() / dur).coerceIn(0f, 1f)
        return if (progress < 0.48f) {
            val sub = full.substring(0, 20)
            val sp = sub.lastIndexOf(' ')
            if (sp > 10) sub.substring(0, sp) else sub
        } else {
            val start = (full.length - 20).coerceAtLeast(0)
            val sub = full.substring(start)
            val sp = sub.indexOf(' ')
            if (sp in 1..9) sub.substring(sp + 1) else sub
        }
    }

    fun update(
        title: String,
        artist: String,
        currentLine: LyricLine?,
        nextLyric: String,
        palette: CoverPalette,
        songPositionMs: Long,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, artist, currentLine, nextLyric, palette, songPositionMs, isPlaying)
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)
}
