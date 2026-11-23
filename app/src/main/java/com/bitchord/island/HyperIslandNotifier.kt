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

    private fun splitForLeftAndRightPill(currentLine: LyricLine?, fallbackTitle: String): Pair<String, String> {
        val full = currentLine?.fullText?.trim().orEmpty().ifBlank { fallbackTitle.trim() }
        val words = full.split(" ").filter { it.isNotBlank() }
        if (words.size <= 1) return "♪" to trimWord(full, 15)

        val left = StringBuilder()
        val right = StringBuilder()
        for (w in words) {
            if (left.length + w.length + 1 <= 12) {
                if (left.isNotEmpty()) left.append(" ")
                left.append(w)
            } else if (right.length + w.length + 1 <= 15) {
                if (right.isNotEmpty()) right.append(" ")
                right.append(w)
            }
        }
        val leftStr = left.toString().ifBlank { "♪" }
        val rightStr = right.toString().ifBlank { trimWord(full, 15) }
        return leftStr to rightStr
    }

    private fun trimWord(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val sub = text.substring(0, maxLen)
        val sp = sub.lastIndexOf(' ')
        return if (sp > maxLen / 2) sub.substring(0, sp) else sub
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
        val (leftSideText, rightSideText) = splitForLeftAndRightPill(currentLine, title)
        val singleRightText = trimWord(currentLine?.fullText?.trim().orEmpty().ifBlank { title }, 15)

        val accentColor = palette.accent.toArgb()
        val hexAccent = String.format("#%06X", 0xFFFFFF and accentColor)

        // Parametri nativi HyperOS Focus / Super Island per popolare anche il lato sinistro della pillola
        val focusJson = """
            {
              "param_v2": {
                "protocol": 1,
                "business": "lyrics_island",
                "ticker": "${singleRightText.replace("\"", "'")}",
                "enableFloat": false,
                "island": {
                  "islandProperty": 1,
                  "bigIslandArea": {
                    "imageTextInfoLeft": {
                      "type": 1,
                      "textInfo": { "title": "${leftSideText.replace("\"", "'")}", "color": "#FFFFFF" }
                    },
                    "imageTextInfoRight": {
                      "type": 2,
                      "textInfo": { "title": "${rightSideText.replace("\"", "'")}", "color": "$hexAccent" }
                    }
                  },
                  "smallIslandArea": {
                    "picInfo": { "type": 1 }
                  }
                }
              }
            }
        """.trimIndent()

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putString("miui.focus.param", focusJson)
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
                    .invoke(builder, singleRightText)

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
