/*
 * Copyright (c) 2026 Meshtastic LLC
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
package org.meshtastic.feature.messaging.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private const val POSITION_POLL_MILLIS = 100L

@Composable
actual fun rememberAudioPlayback(url: String): AudioPlayback? {
    val context = LocalContext.current
    val playback = remember(url) { AndroidAudioPlayback(context, url) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    LaunchedEffect(playback, playback.isPlaying) {
        while (playback.isPlaying) {
            playback.updatePosition()
            delay(POSITION_POLL_MILLIS)
        }
        playback.updatePosition()
    }
    return playback
}

/** ExoPlayer-backed playback, created on the first press of play so nothing is downloaded before that. */
private class AndroidAudioPlayback(private val context: Context, private val url: String) : AudioPlayback {
    override var isPlaying by mutableStateOf(false)
        private set

    override var isLoading by mutableStateOf(false)
        private set

    override var failed by mutableStateOf(false)
        private set

    override var positionMillis by mutableLongStateOf(0L)
        private set

    override var durationMillis by mutableLongStateOf(0L)
        private set

    override var speed by mutableFloatStateOf(1f)
        private set

    private var player: ExoPlayer? = null

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                val current = player ?: return
                isLoading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && current.duration > 0) durationMillis = current.duration
                if (state == Player.STATE_ENDED) {
                    current.pause()
                    current.seekTo(0)
                    positionMillis = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                isLoading = false
            }
        }

    override fun togglePlay() {
        val current = player ?: createPlayer()
        if (current.isPlaying) current.pause() else current.play()
    }

    override fun seekTo(fraction: Float) {
        val current = player ?: return
        if (durationMillis <= 0) return
        val target = (durationMillis * fraction).toLong()
        current.seekTo(target)
        positionMillis = target
    }

    override fun changeSpeed(speed: Float) {
        this.speed = speed
        player?.setPlaybackSpeed(speed)
    }

    fun updatePosition() {
        player?.let { positionMillis = it.currentPosition }
    }

    fun release() {
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
    }

    private fun createPlayer(): ExoPlayer {
        val attributes =
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        // handleAudioFocus pauses other voice messages and music while this one plays.
        return ExoPlayer.Builder(context).setAudioAttributes(attributes, true).build().also {
            player = it
            isLoading = true
            it.addListener(listener)
            it.setMediaItem(MediaItem.fromUri(url))
            it.setPlaybackSpeed(speed)
            it.prepare()
        }
    }
}
