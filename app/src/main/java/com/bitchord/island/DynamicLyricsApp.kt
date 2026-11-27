package com.bitchord.island

import android.app.Application
import android.os.Build

class DynamicLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                getProcessName()
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        if (!processName.endsWith(":crash")) {
            try {
                AppPreferences.init(this)
            } catch (_: Throwable) {
            }
        }
    }
}
