package com.kanagawa.yamada.inaho.inahoservice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import com.kanagawa.yamada.inaho.Song
import com.kanagawa.yamada.inaho.YamadaAudioEngine
import kotlinx.coroutines.*

class PlaybackEngine(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val onSongCompletion: (Boolean) -> Unit,
    private val onStateUpdate: (PlayerState.() -> PlayerState) -> Unit
) {
    var mediaPlayer: MediaPlayer? = null
    var currentSurface: Surface? = null
    private var currentSong: Song? = null
    
    val eqEngine = YamadaAudioEngine(context).apply {
        onVolumeMultiplierChanged = { mult ->
            if (mediaPlayer != null && !fadingOutPlayers.contains(mediaPlayer)) {
                try { mediaPlayer?.setVolume(mult, mult) } catch (_: Exception) {}
            }
        }
    }
    
    private val fadingOutPlayers = mutableSetOf<MediaPlayer>()
    private var crossfadeJob: Job? = null
    
    private var playGeneration = 0
    var isMainPreparing = false
    
    private var currentPlaybackSpeed = 1.0f
    private var currentPlaybackPitch = 1.0f
    private var currentPlayWhenReady = true

    fun setVideoSurface(surface: Surface?) {
        currentSurface = surface
        if (currentSong?.isVideo == true) {
            try { mediaPlayer?.setSurface(surface) } catch (_: Exception) {}
        }
    }
    
    fun setPlaybackSpeedAndPitch(speed: Float, pitch: Float) {
        currentPlaybackSpeed = speed
        currentPlaybackPitch = pitch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { mediaPlayer?.let { mp -> mp.playbackParams = mp.playbackParams.setSpeed(speed).setPitch(pitch) } } catch (_: Exception) {}
        }
    }

    fun togglePlayPause(isPlaying: Boolean) {
        try {
            val mp = mediaPlayer
            if (mp == null) {
                onStateUpdate { copy(isPlaying = false) }
                return
            }
            val currentlyPlaying = try { mp.isPlaying } catch (_: Exception) { isPlaying }
            if (currentlyPlaying) {
                try { mp.pause() } catch (e: Exception) { e.printStackTrace() }
                onStateUpdate { copy(isPlaying = false) }
            } else {
                try { mp.start() } catch (e: Exception) { e.printStackTrace() }
                onStateUpdate { copy(isPlaying = true) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mediaPlayer?.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
            } else {
                mediaPlayer?.seekTo(positionMs.toInt())
            }
            onStateUpdate { copy(positionMs = positionMs) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopPlayback() {
        serviceScope.coroutineContext.cancelChildren()
        fadingOutPlayers.forEach { safelyDestroyPlayer(it) }
        fadingOutPlayers.clear()
        
        eqEngine.release()

        safelyDestroyPlayer(mediaPlayer)
        mediaPlayer = null
        currentSong = null
    }
    
    fun prepareAndPlay(song: Song, isCrossfading: Boolean = false, currentState: PlayerState, playWhenReady: Boolean = true) {
        val prefs = context.getSharedPreferences("inaho_settings", Context.MODE_PRIVATE)
        val crossfadeSec = try { prefs.getFloat("crossfade_duration", 0f) } catch (e: Exception) { prefs.getInt("crossfade_duration", 0).toFloat() }
        val doCrossfade = isCrossfading || (crossfadeSec > 0 && mediaPlayer?.isPlaying == true)
        
        if (isCrossfading) {
            crossfadeJob?.cancel()
        }

        eqEngine.release()
        val uri = song.trackUri
        currentSong = song

        val generation = ++playGeneration
        currentPlayWhenReady = playWhenReady
        isMainPreparing = true

        if (doCrossfade) {
            fadeOutAndRelease(mediaPlayer, crossfadeSec)
        } else {
            safelyDestroyPlayer(mediaPlayer)
        }
        mediaPlayer = null

        mediaPlayer = MediaPlayer().apply {
            if (doCrossfade) setVolume(0f, 0f) else setVolume(eqEngine.currentVolumeMultiplier, eqEngine.currentVolumeMultiplier)
            try {
                val attrBuilder = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                setAudioAttributes(attrBuilder.build())

                if (song.isVideo && currentSurface?.isValid == true) {
                    setSurface(currentSurface)
                }

                eqEngine.analyzeAndSetTrack(song.path)
                setDataSource(context, uri)
                attachMainListeners(this, song, generation, doCrossfade, crossfadeSec, currentState)
                prepareAsync()
            } catch (e: Exception) {
                e.printStackTrace()
                isMainPreparing = false
                onStateUpdate { copy(isPlaying = false) }
            }
        }
    }

    private fun attachMainListeners(mp: MediaPlayer, song: Song, generation: Int, doCrossfade: Boolean, crossfadeSec: Float, currentState: PlayerState) {
        mp.setOnVideoSizeChangedListener { _, width, height ->
            if (generation == playGeneration && width > 0 && height > 0) {
                onStateUpdate { copy(videoWidth = width, videoHeight = height) }
            }
        }

        mp.setOnPreparedListener { 
            if (generation != playGeneration) {
                safelyDestroyPlayer(mp)
                return@setOnPreparedListener
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (generation == playGeneration) {
                    eqEngine.attach(mp)
                }
            }
            onStateUpdate { copy(
                audioSessionId = mp.audioSessionId,
                durationMs = mp.duration.toLong(),
                videoWidth = mp.videoWidth,
                videoHeight = mp.videoHeight
            ) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (currentPlaybackSpeed != 1.0f || currentPlaybackPitch != 1.0f)) {
                try { mp.playbackParams = mp.playbackParams.setSpeed(currentPlaybackSpeed).setPitch(currentPlaybackPitch) } catch (_: Exception) {}
            }

            isMainPreparing = false

            if (song.isVideo && currentSurface?.isValid == true) {
                try { mp.setSurface(currentSurface) } catch(_: Exception) {}
            }

            try {
                if (currentPlayWhenReady) {
                    mp.start()
                }
                onStateUpdate { copy(isPlaying = currentPlayWhenReady) }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (doCrossfade) fadeIn(mp, crossfadeSec)
            startCrossfadePoller(currentState)
        }

        mp.setOnCompletionListener {
            if (generation == playGeneration) {
                onSongCompletion(false)
            }
        }

        mp.setOnErrorListener { _, _, _ ->
            if (generation == playGeneration) {
                isMainPreparing = false
                onStateUpdate { copy(isPlaying = false) }
            }
            true
        }
    }

    private fun safelyDestroyPlayer(mp: MediaPlayer?) {
        if (mp == null) return
        try { mp.setOnPreparedListener(null) } catch (_: Exception) {}
        try { mp.setOnCompletionListener(null) } catch (_: Exception) {}
        try { mp.setOnErrorListener(null) } catch (_: Exception) {}
        try { mp.setOnVideoSizeChangedListener(null) } catch (_: Exception) {}
        try { mp.setSurface(null) } catch (_: Exception) {}
        
        CoroutineScope(Dispatchers.IO).launch {
            try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
    }

    private fun fadeOutAndRelease(mp: MediaPlayer?, durationSec: Float) {
        if (mp == null || !mp.isPlaying) {
            safelyDestroyPlayer(mp)
            return
        }
        
        try { mp.setOnCompletionListener(null) } catch (_: Exception) {}
        try { mp.setOnErrorListener(null) } catch (_: Exception) {}
        try { mp.setOnVideoSizeChangedListener(null) } catch (_: Exception) {}
        
        fadingOutPlayers.add(mp)
        serviceScope.launch {
            val steps = 20
            val interval = (durationSec * 1000 / steps).toLong()
            for (i in steps downTo 1) {
                if (!isActive) break
                val vol = (i.toFloat() / steps) * eqEngine.currentVolumeMultiplier
                try { mp.setVolume(vol, vol) } catch (_: Exception) {}
                delay(interval)
            }
            safelyDestroyPlayer(mp)
            fadingOutPlayers.remove(mp)
        }
    }

    private fun fadeIn(mp: MediaPlayer, durationSec: Float) {
        serviceScope.launch {
            val steps = 20
            val interval = (durationSec * 1000 / steps).toLong()
            for (i in 1..steps) {
                if (!isActive) break
                val vol = (i.toFloat() / steps) * eqEngine.currentVolumeMultiplier
                try { mp.setVolume(vol, vol) } catch (_: Exception) {}
                delay(interval)
            }
        }
    }

    private fun startCrossfadePoller(currentState: PlayerState) {
        crossfadeJob?.cancel()
        crossfadeJob = serviceScope.launch {
            while (isActive) {
                delay(500)
                val prefs = context.getSharedPreferences("inaho_settings", Context.MODE_PRIVATE)
                val crossfadeSec = try { prefs.getFloat("crossfade_duration", 0f) } catch (e: Exception) { prefs.getInt("crossfade_duration", 0).toFloat() }
                if (crossfadeSec > 0 && mediaPlayer?.isPlaying == true ) {
                    val duration = mediaPlayer?.duration ?: 0
                    val position = mediaPlayer?.currentPosition ?: 0
                    if (duration > 0 && (duration - position) <= (crossfadeSec * 1000).toInt() + 500) {
                        onSongCompletion(true)
                        break
                    }
                }
            }
        }
    }
}
