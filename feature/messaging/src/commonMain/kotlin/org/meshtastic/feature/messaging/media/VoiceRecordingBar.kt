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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.voice_cancel
import org.meshtastic.core.resources.voice_recording
import org.meshtastic.core.resources.voice_send
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Stop

private val RECORDING_RED = Color(0xFFE53935)
private const val TIMER_TICK_MILLIS = 250L
private const val PULSE_MILLIS = 700
private const val PULSE_MIN_ALPHA = 0.25f

/** Replaces the message field while a voice message is being recorded: cancel, a pulsing dot with the time, send. */
@Composable
fun VoiceRecordingBar(recorder: VoiceRecorder, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recorder.startedAtMillis) {
        while (true) {
            elapsed = nowMillis - recorder.startedAtMillis
            delay(TIMER_TICK_MILLIS)
        }
    }
    val pulse by
        rememberInfiniteTransition()
            .animateFloat(
                initialValue = 1f,
                targetValue = PULSE_MIN_ALPHA,
                animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
            )

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = recorder::cancel) {
                Icon(imageVector = MeshtasticIcons.Close, contentDescription = stringResource(Res.string.voice_cancel))
            }
            Box(Modifier.size(12.dp).alpha(pulse).background(RECORDING_RED, CircleShape))
            Text(
                text = "${stringResource(Res.string.voice_recording)} ${formatPlaybackTime(elapsed)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            FilledIconButton(onClick = recorder::stop) {
                Icon(imageVector = MeshtasticIcons.Stop, contentDescription = stringResource(Res.string.voice_send))
            }
        }
    }
}
