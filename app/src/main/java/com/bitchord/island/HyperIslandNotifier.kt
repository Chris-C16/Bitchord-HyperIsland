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
import org.json.JSONObject

class HyperIslandNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "bitchord_hyperisland_live"
        const val NOTIFICATION_ID = 3601
        const val ACTION_TOGGLE_OVERLAY = "com.bitchord.island.TOGGLE_OVERLAY"
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BitChord HyperIsland Lyrics",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Testi sincronizzati in tempo reale nella HyperIsland"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(
        title: String,
        artist: String,
        currentLyric: String,
        cover: Bitmap?,
        isPlaying: Boolean
    ): Notification {
        val toggleIntent = Intent(ACTION_TOGGLE_OVERLAY).setPackage(context.packageName)
        val pendingToggle = PendingIntent.getBroadcast(
            context, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayLine = currentLyric.ifBlank { "$title • $artist" }
        val shortChip = displayLine.take(18)

        val focusJson = JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("protocol", 1)
                put("enableFloat", false)
                put("updatable", true)
                put("islandProperty", 1)
                put("baseInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", displayLine)
                    put("content", "$title — $artist")
                })
                put("island", JSONObject().apply {
                    put("islandOrder", true)
                    put("bigIslandArea", JSONObject().apply {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", "miui.focus.pic_cover")
                            })
                            put("textInfo", JSONObject().apply {
                                put("title", shortChip)
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
            putBoolean("android.requestPromotedOngoing", true)
            if (cover != null) {
                putBundle("miui.focus.pics", Bundle().apply {
                    putParcelable("miui.focus.pic_cover", Icon.createWithBitmap(cover))
                })
            }
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(displayLine)
            .setContentText("$title • $artist")
            .setSubText(if (isPlaying) "BitChord" else "In pausa")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pendingToggle)
            .addExtras(extras)

        if (cover != null) builder.setLargeIcon(cover)

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val mPromoted = Notification.Builder::class.java.getMethod(
                    "setRequestPromotedOngoing",
                    Boolean::class.javaPrimitiveType
                )
                mPromoted.invoke(builder, true)
                val mChip = Notification.Builder::class.java.getMethod(
                    "setShortCriticalText",
                    CharSequence::class.java
                )
                mChip.invoke(builder, shortChip)
            } catch (_: Throwable) {
            }
        }

        return builder.build()
    }

    fun update(title: String, artist: String, currentLyric: String, cover: Bitmap?, isPlaying: Boolean) {
        manager.notify(NOTIFICATION_ID, buildNotification(title, artist, currentLyric, cover, isPlaying))
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)
}
