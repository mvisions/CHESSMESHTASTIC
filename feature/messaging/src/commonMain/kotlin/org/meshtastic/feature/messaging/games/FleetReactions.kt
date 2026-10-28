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
package org.meshtastic.feature.messaging.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.fleet_reaction_bravo
import org.meshtastic.core.resources.fleet_reaction_gg
import org.meshtastic.core.resources.fleet_reaction_hit
import org.meshtastic.core.resources.fleet_reaction_hit_short
import org.meshtastic.core.resources.fleet_reaction_nice_move
import org.meshtastic.core.resources.fleet_reaction_nice_move_short
import org.meshtastic.core.resources.fleet_reaction_opponent
import org.meshtastic.core.resources.fleet_reaction_wait
import org.meshtastic.core.resources.fleet_reaction_you
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark

@Composable
internal fun rememberFleetCountdown(lastActionAt: TimeMark?, interval: Duration): Int {
    fun remaining() = lastActionAt?.let { (interval - it.elapsedNow()).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO
    var secondsLeft by
        remember(lastActionAt, interval) {
            mutableIntStateOf(ceil(remaining().toDouble(DurationUnit.SECONDS)).toInt())
        }
    LaunchedEffect(lastActionAt, interval) {
        do {
            val duration = remaining()
            secondsLeft = ceil(duration.toDouble(DurationUnit.SECONDS)).toInt()
            if (secondsLeft > 0) delay(minOf(duration, 1.seconds))
        } while (secondsLeft > 0)
    }
    return secondsLeft
}

@Composable
internal fun FleetReactions(
    session: FleetGameSession,
    onReaction: (FleetReaction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondsLeft = rememberFleetCountdown(session.lastReactionAt, FLEET_REACTION_INTERVAL)
    Column(modifier) {
        session.latestReaction?.let {
            val text = stringResource(it.reaction.label())
            Text(
                if (it.fromLocal) {
                    stringResource(Res.string.fleet_reaction_you, text)
                } else {
                    stringResource(Res.string.fleet_reaction_opponent, session.opponentName, text)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FleetReaction.entries.forEach { reaction ->
                val description = stringResource(reaction.label())
                OutlinedButton(
                    onClick = { if (session.canSendReaction) onReaction(reaction) },
                    enabled = secondsLeft == 0 && session.canSendReaction,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape = MaterialTheme.shapes.small,
                    modifier =
                    Modifier.weight(1f).heightIn(min = 48.dp).aspectRatio(1f).semantics {
                        contentDescription = description
                    },
                ) {
                    Text(
                        stringResource(reaction.buttonLabel()),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (secondsLeft > 0) {
            Text(
                stringResource(Res.string.fleet_reaction_wait, secondsLeft),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun FleetReaction.label(): StringResource = when (this) {
    FleetReaction.HIT -> Res.string.fleet_reaction_hit
    FleetReaction.BRAVO -> Res.string.fleet_reaction_bravo
    FleetReaction.GG -> Res.string.fleet_reaction_gg
    FleetReaction.NICE_MOVE -> Res.string.fleet_reaction_nice_move
}

private fun FleetReaction.buttonLabel(): StringResource = when (this) {
    FleetReaction.HIT -> Res.string.fleet_reaction_hit_short
    FleetReaction.NICE_MOVE -> Res.string.fleet_reaction_nice_move_short
    else -> label()
}
