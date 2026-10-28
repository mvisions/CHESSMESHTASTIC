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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.fleet_target_overview

internal data class FleetTargetRegion(
    val rows: IntRange = 0 until FLEET_BOARD_SIZE,
    val columns: IntRange = 0 until FLEET_BOARD_SIZE,
)

internal fun fleetTargetRegion(cell: Int): FleetTargetRegion {
    val row = (cell / FLEET_BOARD_SIZE - 1).coerceIn(0, FLEET_BOARD_SIZE - TARGET_REGION_SIZE)
    val column = (cell % FLEET_BOARD_SIZE - 1).coerceIn(0, FLEET_BOARD_SIZE - TARGET_REGION_SIZE)
    return FleetTargetRegion(row until row + TARGET_REGION_SIZE, column until column + TARGET_REGION_SIZE)
}

private const val TARGET_REGION_SIZE = 3

@Composable
internal fun FleetGameContent(
    session: FleetGameSession,
    onCellClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onReaction: (FleetReaction) -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    var aiming by rememberSaveable(session.gameId) { mutableStateOf(false) }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = aiming,
        onBackCompleted = { aiming = false },
    )
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!aiming) FleetSessionHeader(session)
        FleetBoards(session, onCellClick, aiming, { aiming = it }, Modifier.weight(1f))
        if (!aiming) {
            FleetReactions(session, onReaction)
            actions()
        }
    }
}

@Composable
internal fun FleetSessionHeader(session: FleetGameSession, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            session.opponentName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            statusText(session),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun FleetTargeting(
    session: FleetGameSession,
    canFire: Boolean,
    selectedCell: Int?,
    onSelectCell: (Int) -> Unit,
    onClose: () -> Unit,
    fireControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoomCell by rememberSaveable(session.gameId, session.turn) { mutableStateOf<Int?>(null) }
    val region = zoomCell?.let(::fleetTargetRegion) ?: FleetTargetRegion()
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text(stringResource(Res.string.close)) }
            fireControl()
        }
        if (zoomCell != null) {
            TextButton(onClick = { zoomCell = null }) {
                Text(stringResource(Res.string.fleet_target_overview))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val cellSize =
                minOf(
                    (maxWidth - 20.dp).coerceAtLeast(0.dp) / region.columns.count(),
                    (maxHeight - 20.dp).coerceAtLeast(0.dp) / region.rows.count(),
                )
            FleetBoardGrid(
                board = session.board,
                shots = session.incomingShots,
                shotResults = session.outgoingResults,
                outgoingShot = session.outgoingShot,
                ownBoard = false,
                enabled = zoomCell == null || canFire,
                onCellClick = { cell ->
                    if (zoomCell == null) zoomCell = cell else onSelectCell(cell)
                },
                selectedCell = selectedCell,
                cellSize = cellSize,
                region = region,
            )
        }
    }
}
