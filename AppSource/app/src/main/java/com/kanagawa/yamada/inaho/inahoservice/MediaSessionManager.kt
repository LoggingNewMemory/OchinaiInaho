package com.kanagawa.yamada.inaho.inahoservice

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kanagawa.yamada.inaho.EqPreset
import com.kanagawa.yamada.inaho.MainActivity
import com.kanagawa.yamada.inaho.Song
import com.kanagawa.yamada.inaho.extractAndDownsample
import com.kanagawa.yamada.inaho.inahocar.AutoLibraryManager
import com.kanagawa.yamada.inaho.isArtResolved
import com.kanagawa.yamada.inaho.saveBitmapToDisk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class MediaSessionManager(
    private val service: PlayerService
) {
    val player: InahoPlayer
    val mediaLibrarySession: MediaLibrarySession

    init {
        player = InahoPlayer(service)

        val sessionActivityIntent = Intent(service, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            service, 0, sessionActivityIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId(AutoLibraryManager.ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val children = service.autoLibraryManager.getChildren(parentId)
                return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val item = service.autoLibraryManager.getItem(mediaId)
                return if (item != null) {
                    Futures.immediateFuture(LibraryResult.ofItem(item, null))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<List<MediaItem>> {
                for (item in mediaItems) {
                    val mediaId = item.mediaId
                    val searchQuery = item.requestMetadata.searchQuery
                    if (!searchQuery.isNullOrBlank()) {
                        service.playFromSearch(searchQuery)
                        return Futures.immediateFuture(emptyList())
                    }
                    if (mediaId == AutoLibraryManager.CATEGORY_EQ) {
                        if (PlayerService.playerState.value.currentSong == null) {
                            service.playRandomSong()
                        } else {
                            service.updateSessionAndNotification()
                        }
                        return Futures.immediateFuture(emptyList())
                    }
                    if (mediaId == "action_toggle_replaygain") {
                        val isEnabled = service.eqEngine.replayGainEnabled.value
                        service.eqEngine.setReplayGainEnabled(!isEnabled)
                        if (PlayerService.playerState.value.currentSong == null) {
                            service.playRandomSong()
                        } else {
                            service.updateSessionAndNotification()
                        }
                        service.notifyChildrenChanged(AutoLibraryManager.CATEGORY_EQ)
                        return Futures.immediateFuture(emptyList())
                    }
                    if (mediaId.startsWith(AutoLibraryManager.PREFIX_EQ)) {
                        val presetName = mediaId.removePrefix(AutoLibraryManager.PREFIX_EQ)
                        val preset = EqPreset.values().find { it.name == presetName }
                        if (preset != null) {
                            service.eqEngine.setPreset(preset)
                            if (PlayerService.playerState.value.currentSong == null) {
                                service.playRandomSong()
                            } else {
                                service.updateSessionAndNotification()
                            }
                            service.notifyChildrenChanged(AutoLibraryManager.CATEGORY_EQ)
                        }
                        return Futures.immediateFuture(emptyList())
                    }
                    val id = mediaId.toLongOrNull()
                    if (id != null) {
                        service.playFromMediaId(id)
                        return Futures.immediateFuture(emptyList())
                    }
                }
                return Futures.immediateFuture(mediaItems)
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(service, player, callback)
            .setId("InahoMediaSession")
            .setSessionActivity(pendingIntent)
            .build()
    }

    fun updateState(state: PlayerState, currentPosition: Long) {
        player.requestStateUpdate()
    }

    fun notifyChildrenChanged(parentId: String) {
        mediaLibrarySession.notifyChildrenChanged(parentId, Int.MAX_VALUE, null)
    }

    fun release() {
        mediaLibrarySession.release()
        player.release()
    }
}

@OptIn(UnstableApi::class)
class InahoPlayer(
    private val service: PlayerService,
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {

    fun requestStateUpdate() {
        invalidateState()
    }

    private val availableCommands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM
        )
        .build()

    private fun createMediaItem(song: Song): MediaItem {
        val artFile = File(service.cacheDir, "art/${song.id}.png")
        val artUri = if (artFile.exists()) Uri.fromFile(artFile) else null
        val artBytes = if (artFile.exists()) {
            try { artFile.readBytes() } catch (_: Exception) { null }
        } else {
            if (!isArtResolved(service, song.id)) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val bitmap = extractAndDownsample(service, song.trackUri, 512)
                        saveBitmapToDisk(service, song.id, bitmap)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                invalidateState()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            null
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setArtworkUri(artUri)
            .setIsPlayable(true)
            .setIsBrowsable(false)

        if (artBytes != null) {
            metadataBuilder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(song.trackUri)
                    .build()
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    override fun getState(): State {
        val state = PlayerService.playerState.value
        val queue = state.activeQueue
        val currentSong = state.currentSong

        val playlistData = if (queue.isNotEmpty()) {
            queue.map { song ->
                MediaItemData.Builder(song.id)
                    .setMediaItem(createMediaItem(song))
                    .setDurationUs(song.durationMs * 1000L)
                    .build()
            }
        } else if (currentSong != null) {
            listOf(
                MediaItemData.Builder(currentSong.id)
                    .setMediaItem(createMediaItem(currentSong))
                    .setDurationUs(currentSong.durationMs * 1000L)
                    .build()
            )
        } else {
            emptyList()
        }

        val playbackState = if (currentSong != null) Player.STATE_READY else Player.STATE_IDLE
        val currentIndex = state.currentIndex.coerceAtLeast(0)

        val builder = State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlayWhenReady(state.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setRepeatMode(
                when (state.repeatMode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
            )
            .setShuffleModeEnabled(state.isShuffled)
            .setContentPositionMs { service.getCurrentPosition() }

        if (playlistData.isNotEmpty()) {
            builder.setPlaylist(playlistData)
            builder.setCurrentMediaItemIndex(currentIndex.coerceIn(0, playlistData.size - 1))
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (PlayerService.playerState.value.currentSong == null) {
                service.playRandomSong()
            } else if (!PlayerService.playerState.value.isPlaying) {
                service.togglePlayPause()
            }
        } else {
            if (PlayerService.playerState.value.isPlaying) {
                service.togglePlayPause()
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                if (positionMs != C.TIME_UNSET) {
                    service.seekTo(positionMs)
                }
            }
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                if (mediaItemIndex != C.INDEX_UNSET) {
                    service.jumpToQueueIndex(mediaItemIndex)
                }
                if (positionMs != C.TIME_UNSET) {
                    service.seekTo(positionMs)
                }
            }
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                service.skipNext()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                service.skipPrev()
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        service.toggleRepeat()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled != PlayerService.playerState.value.isShuffled) {
            service.toggleShuffle()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        service.stopPlayback()
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
