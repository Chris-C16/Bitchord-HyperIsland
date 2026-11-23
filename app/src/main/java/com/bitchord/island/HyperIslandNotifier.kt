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
        private const val MAX_PILL_CHARS = 7
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
            context,
            10,
            openLyricsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

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

    fun getMicroChunk(currentLine: LyricLine?, songPositionMs: Long, fallbackTitle: String): Pair<Int, String> {
        val full = currentLine?.fullText?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (full.isBlank()) {
            return 0 to buildChunks(fallbackTitle.trim(), MAX_PILL_CHARS).firstOrNull().orEmpty()
        }

        val chunks = buildChunks(full, MAX_PILL_CHARS)
        if (chunks.size <= 1) return 0 to chunks.first()

        val rawDuration = currentLine!!.endMs - currentLine.startMs
        val safeDuration = if (rawDuration > 500L) rawDuration else 3500L
        val elapsed = (songPositionMs - currentLine.startMs).coerceAtLeast(0L)
        val ratio = (elapsed.toFloat() / safeDuration).coerceIn(0f, 0.999f)
        val chunkIdx = (ratio * chunks.size).toInt().coerceIn(0, chunks.lastIndex)
        return chunkIdx to chunks[chunkIdx]
    }

    private fun buildChunks(text: String, maxLen: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf("")

        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (w in words) {
            if (w.length > maxLen) {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
                var i = 0
                while (i < w.length) {
                    val end = (i + maxLen).coerceAtMost(w.length)
                    result.add(w.substring(i, end))
                    i += maxLen
                }
                continue
            }

            if (current.isEmpty()) {
                current.append(w)
            } else if (current.length + 1 + w.length <= maxLen) {
                current.append(" ").append(w)
            } else {
                result.add(current.toString())
                current.clear()
                current.append(w)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
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
        val (_, miniPillChunk) = getMicroChunk(currentLine, songPositionMs, title)
        val accentColor = palette.accent.toArgb()

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(topLine)
            .setContentText(bottomParagraphs)
            .setSubText("$title • $artist")
            .setColor(accentColor)
            .setColorized(false)
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
                    .invoke(builder, miniPillChunk)

                val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                val progressStyle = progressStyleClass.getDeclaredConstructor().newInstance()
                progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(progressStyle, 50)
                progressStyleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
                    .invoke(progressStyle, true)

                Notification.Builder::class.java
                    .getMethod("setStyle", Notification.Style::class.java)
                    .invoke(builder, progressStyle)
            } catch (_: Throwable) {
                builder.setProgress(100, 50, false)
            }
        } else {
            builder.setProgress(100, 50, false)
        }

        return builder.build()
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
