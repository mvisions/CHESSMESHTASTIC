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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.fleet_reaction_bravo
import org.meshtastic.core.resources.fleet_reaction_gg
import org.meshtastic.core.resources.fleet_reaction_hit
import org.meshtastic.core.resources.fleet_reaction_nice_move
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
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FleetReaction.entries.forEach { reaction ->
                SuggestionChip(
                    onClick = { if (session.canSendReaction) onReaction(reaction) },
                    enabled = secondsLeft == 0 && session.canSendReaction,
                    label = { Text(stringResource(reaction.label())) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
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
