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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/** Playback state of one voice message; implemented per platform. */
@Stable
interface AudioPlayback {
    val isPlaying: Boolean

    /** True while the first bytes are being fetched after pressing play. */
    val isLoading: Boolean

    /** True when the audio could not be played (no internet, expired link). */
    val failed: Boolean
    val positionMillis: Long

    /** Length reported by the player, or 0 until it is known. */
    val durationMillis: Long
    val speed: Float

    fun togglePlay()

    fun seekTo(fraction: Float)

    fun changeSpeed(speed: Float)
}

/**
 * Remembers a player for the voice message at [url]. Nothing is downloaded until play is pressed. Returns null on
 * platforms without in-app audio playback, where the plain link is shown instead.
 */
@Composable expect fun rememberAudioPlayback(url: String): AudioPlayback?
