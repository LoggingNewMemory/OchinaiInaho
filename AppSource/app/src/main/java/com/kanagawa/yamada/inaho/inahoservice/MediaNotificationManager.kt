/*
 * Inaho Music Player - Media Notification Manager
 * Copyright (C) 2026 Kanagawa Yamada
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
