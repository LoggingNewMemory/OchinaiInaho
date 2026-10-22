package com.kanagawa.yamada.inaho.inahoservice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MediaNotificationManager(private val service: PlayerService) {

    companion object {
        const val CHANNEL_ID = "inaho_player"
        const val NOTIF_ID = 1
        
        const val ACTION_PREV = "com.kanagawa.yamada.inaho.PREV"
        const val ACTION_PLAY = "com.kanagawa.yamada.inaho.PLAY"
        const val ACTION_NEXT = "com.kanagawa.yamada.inaho.NEXT"
        const val ACTION_STOP = "com.kanagawa.yamada.inaho.STOP"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = service.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
