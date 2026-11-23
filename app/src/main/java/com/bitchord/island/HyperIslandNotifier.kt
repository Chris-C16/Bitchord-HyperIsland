package com.bitchord.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

class HyperIslandNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "bitchord_hyperisland_live"
        const val NOTIFICATION_ID = 3601
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HyperIsland Lyrics",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Testi sincronizzati nella HyperIsland"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun intToHexColor(colorInt: Int): String {
        return String.format("#%06X", 0xFFFFFF and colorInt)
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
        // Toccare la card espansa apre direttamente BitChord
        val openBitChordIntent = context.packageManager.getLaunchIntentForPackage("com.bitchord.app")
            ?: Intent(context, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            context, 1, openBitChordIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeText = currentLyric.ifBlank { title }
        val pillText = activeText.take(26)
        val accentHex = intToHexColor(palette.accent.toArgb())

        // Struttura multi-riga pulita per la card espansa della HyperIsland
        val expandedBody = buildString {
            if (prevLyric.isNotBlank()) appendLine(prevLyric)
            append("▶ $activeText")
            if (nextLyric.isNotBlank()) {
                appendLine()
                append(nextLyric)
            }
        }

        // Payload nativo HyperOS 3 / 4 Super Island (pillola chiusa + card espansa nativa)
        val focusJson = JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("protocol", 1)
                put("enableFloat", false)
                put("updatable", true)
                put("islandProperty", 1)
                put("ticker", pillText)
                put("tickerPic", "miui.focus.pic_cover")
                put("baseInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", activeText)
                    put("content", if (nextLyric.isNotBlank()) nextLyric else "$title — $artist")
                    put("subTitle", "$title • $artist")
                    put("colorTitle", accentHex)
                })
                put("hintInfo", JSONObject().apply {
                    put("type", 1)
                    put("title", activeText)
                    put("content", nextLyric.ifBlank { artist })
                    put("colorTitle", accentHex)
                })
                put("island", JSONObject().apply {
                    put("islandOrder", true)
                    put("highlightColor", accentHex)
                    put("bigIslandArea", JSONObject().apply {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", "miui.focus.pic_cover")
                            })
                            put("textInfo", JSONObject().apply {
                                put("title", pillText)
                                put("showHighlightColor", true)
                            })
                        })
                    })
                    put("smallIslandArea", JSONObject().apply {
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_cover")
                        })
                    })
                })
            })
        }

        val extras = Bundle().apply {
            putString("miui.focus.param", focusJson.toString())
            putString("miui.focus.ticker", pillText)
            putBoolean("android.requestPromotedOngoing", true)
            if (cover != null) {
                putBundle("miui.focus.pics", Bundle().apply {
                    putParcelable("miui.focus.pic_cover", Icon.createWithBitmap(cover))
                })
            }
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(activeText)
            .setContentText(if (nextLyric.isNotBlank()) nextLyric else "$title — $artist")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(expandedBody)
                    .setBigContentTitle("$title — $artist")
            )
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pendingOpen)
            .addExtras(extras)

        if (cover != null) builder.setLargeIcon(cover)

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, pillText)
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
