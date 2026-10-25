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
import org.meshtastic.core.common.util.CommonUri

/** Records a voice message from the microphone; implemented per platform. */
@Stable
interface VoiceRecorder {
    val isRecording: Boolean

    /** Wall-clock time the current recording started, for the elapsed-time counter. */
    val startedAtMillis: Long

    /** Starts recording, asking for the microphone permission first if needed. */
    fun start()

    /** Finishes the recording and hands it to the `onRecord` callback; very short taps are discarded. */
    fun stop()

    /** Stops and throws the recording away. */
    fun cancel()
}

/**
 * Remembers a voice recorder that calls [onRecord] with the finished audio file and its length. Returns null on
 * platforms without recording support, which hides the microphone button.
 */
@Composable expect fun rememberVoiceRecorder(onRecord: (CommonUri, Long) -> Unit): VoiceRecorder?
