package com.bitchord.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle

class HyperIslandNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "bitchord_live_updates"
        const val NOTIFICATION_ID = 3601
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live Lyrics",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Testi sincronizzati Live Update"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(
        title: String,
        artist: String,
        prevLyric: String,
        currentLyric: String,
        nextLyric: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean
    ): Notification {
        val openBitChordIntent = context.packageManager.getLaunchIntentForPackage("com.bitchord.app")
            ?: Intent(context, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            context, 1, openBitChordIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeText = currentLyric.ifBlank { title }
        val chipText = activeText.take(24)

        val expandedBody = buildString {
            if (prevLyric.isNotBlank()) appendLine(prevLyric)
            append("▶ $activeText")
            if (nextLyric.isNotBlank()) {
                appendLine()
                append(nextLyric)
            }
        }

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(activeText)
            .setContentText(nextLyric.ifBlank { "$title — $artist" })
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(expandedBody)
                    .setBigContentTitle("$title — $artist")
            )
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pendingOpen)
            .addExtras(extras)

        if (cover != null) builder.setLargeIcon(cover)

        // API nativa Android 16 Live Updates (riconosciuta da HyperOS 3+ Super Island)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, chipText)
            } catch (_: Throwable) {
            }
        }

        return builder.build()
    }

    fun update(
        title: String,
        artist: String,
        prevLyric: String,
        currentLyric: String,
        nextLyric: String,
        cover: Bitmap?,
        palette: CoverPalette,
        isPlaying: Boolean
    ) {
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, artist, prevLyric, currentLyric, nextLyric, cover, palette, isPlaying)
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)
}
