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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.media_report_description
import org.meshtastic.core.resources.voice_message
import org.meshtastic.core.resources.voice_pause
import org.meshtastic.core.resources.voice_play
import org.meshtastic.core.resources.voice_speed
import org.meshtastic.core.ui.icon.Flag
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Pause
import org.meshtastic.core.ui.icon.PlayArrow
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private val PLAYBACK_SPEEDS = listOf(1f, 1.5f, 2f)
private const val WAVEFORM_BARS = 36
private const val MIN_BAR_HEIGHT = 0.18f
private const val BAR_SPACING_RATIO = 0.45f
private const val WAVE_PERIOD_MILLIS = 900
private const val WAVE_DEPTH = 0.35f
private const val WAVE_STEP = 0.55f
private const val UNPLAYED_ALPHA = 0.35f
private const val HALF = 0.5f
private val PLAYER_WIDTH = 260.dp

/** Next speed in the 1x → 1.5x → 2x cycle. */
fun nextPlaybackSpeed(current: Float): Float =
    PLAYBACK_SPEEDS.getOrElse(PLAYBACK_SPEEDS.indexOfFirst { it >= current } + 1) { PLAYBACK_SPEEDS.first() }

/** "1x", "1.5x", "2x". */
fun formatPlaybackSpeed(speed: Float): String {
    val whole = speed.toInt()
    return if (speed == whole.toFloat()) "${whole}x" else "${speed}x"
}

/**
 * Inline voice-message player: play/pause, a waveform that fills as it plays (tap or drag to seek), the elapsed and
 * total time and a 1x/1.5x/2x speed toggle. Reports through [onLoadFailure] when the audio can't be played so the
 * bubble falls back to the plain link.
 */
@Composable
fun AudioMessage(link: MediaLink, onLoadFailure: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val playback = rememberAudioPlayback(link.fetchUrl)
    val failed = playback == null || playback.failed
    val latestOnLoadFailure by rememberUpdatedState(onLoadFailure)
    DisposableEffect(failed) {
        latestOnLoadFailure(failed)
        onDispose {}
    }
    if (playback == null || playback.failed) return

    val duration = playback.durationMillis.takeIf { it > 0 } ?: link.durationMillis ?: 0L
    val progress = if (duration > 0) (playback.positionMillis.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val voiceLabel = stringResource(Res.string.voice_message)

    Row(
        modifier = modifier.width(PLAYER_WIDTH).semantics { contentDescription = voiceLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayPauseButton(playback)
        Column(modifier = Modifier.weight(1f)) {
            Waveform(
                seed = link.fetchUrl.hashCode(),
                progress = progress,
                animate = playback.isPlaying,
                onSeek = playback::seekTo,
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = if (playback.isPlaying || playback.positionMillis > 0) playback.positionMillis else duration
                Text(
                    text = formatPlaybackTime(shown),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                SpeedChip(speed = playback.speed, onClick = { playback.changeSpeed(nextPlaybackSpeed(playback.speed)) })
            }
        }
        rememberReportMedia(link.fetchUrl)?.let { report ->
            IconButton(onClick = report, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = MeshtasticIcons.Flag,
                    contentDescription = stringResource(Res.string.media_report_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(playback: AudioPlayback) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        if (playback.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = playback::togglePlay) {
                Icon(
                    imageVector = if (playback.isPlaying) MeshtasticIcons.Pause else MeshtasticIcons.PlayArrow,
                    contentDescription =
                    stringResource(if (playback.isPlaying) Res.string.voice_pause else Res.string.voice_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun SpeedChip(speed: Float, onClick: () -> Unit) {
    val label = "${stringResource(Res.string.voice_speed)} ${formatPlaybackSpeed(speed)}"
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(
            text = formatPlaybackSpeed(speed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/**
 * Decorative waveform: bar heights are derived from the link so every message has its own stable shape. Played bars use
 * the primary colour; while playing the bars ripple gently.
 */
@Composable
private fun Waveform(
    seed: Int,
    progress: Float,
    animate: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val heights =
        remember(seed) {
            val random = Random(seed)
            List(WAVEFORM_BARS) { MIN_BAR_HEIGHT + random.nextFloat() * (1f - MIN_BAR_HEIGHT) }
        }
    val phase =
        if (animate) {
            val transition = rememberInfiniteTransition()
            val value by
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2 * PI).toFloat(),
                    animationSpec =
                    infiniteRepeatable(tween(WAVE_PERIOD_MILLIS, easing = LinearEasing), RepeatMode.Restart),
                )
            value
        } else {
            null
        }
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNPLAYED_ALPHA)
    Canvas(
        modifier =
        modifier
            .pointerInput(onSeek) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(onSeek) {
                detectHorizontalDragGestures { change, _ ->
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val slot = size.width / WAVEFORM_BARS
        val barWidth = slot * (1f - BAR_SPACING_RATIO)
        heights.forEachIndexed { index, base ->
            val ripple = phase?.let { 1f - WAVE_DEPTH * (HALF + HALF * sin(it + index * WAVE_STEP)) } ?: 1f
            val barHeight = (size.height * base * ripple).coerceAtLeast(barWidth)
            val color = if ((index + HALF) / WAVEFORM_BARS <= progress) played else unplayed
            drawRoundRect(
                color = color,
                topLeft = Offset(index * slot + (slot - barWidth) / 2, (size.height - barHeight) / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}
