package com.aliahad.wovoice

import android.app.Application
import com.aliahad.wovoice.background.BubbleHealthJobService
import com.aliahad.wovoice.bubble.BubbleKeepAliveService
import com.aliahad.wovoice.settings.SettingsStore

/**
 * Application entry point that initializes notification channels and restores
 * background keep-alive protection whenever the floating dictation bubble is enabled.
 */
class LiVoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BubbleKeepAliveService.createChannel(this)
        val store = SettingsStore(this)
        BubbleHealthJobService.sync(this)
        if (store.floatingBubbleEnabled && store.keepAliveEnabled) {
            BubbleKeepAliveService.syncState(this)
        }
    }
}
