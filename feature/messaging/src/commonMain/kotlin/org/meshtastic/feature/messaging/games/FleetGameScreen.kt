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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.fleet_accept
import org.meshtastic.core.resources.fleet_board_cell
import org.meshtastic.core.resources.fleet_cell_hit
import org.meshtastic.core.resources.fleet_cell_miss
import org.meshtastic.core.resources.fleet_cell_pending
import org.meshtastic.core.resources.fleet_cell_ship
import org.meshtastic.core.resources.fleet_cell_unknown
import org.meshtastic.core.resources.fleet_cell_water
import org.meshtastic.core.resources.fleet_decline
import org.meshtastic.core.resources.fleet_game_loading
import org.meshtastic.core.resources.fleet_game_title
import org.meshtastic.core.resources.fleet_invitation_message
import org.meshtastic.core.resources.fleet_invitation_title
import org.meshtastic.core.resources.fleet_my_board
import org.meshtastic.core.resources.fleet_opponent_board
import org.meshtastic.core.resources.fleet_report_defeat
import org.meshtastic.core.resources.fleet_resign
import org.meshtastic.core.resources.fleet_resign_confirmation
import org.meshtastic.core.resources.fleet_result_hit
import org.meshtastic.core.resources.fleet_result_lost
import org.meshtastic.core.resources.fleet_result_miss
import org.meshtastic.core.resources.fleet_result_resigned
import org.meshtastic.core.resources.fleet_result_sunk
import org.meshtastic.core.resources.fleet_result_won
import org.meshtastic.core.resources.fleet_turn_waiting
import org.meshtastic.core.resources.fleet_turn_yours
import org.meshtastic.core.ui.component.MainAppBar

private val FLEET_CELL_SIZE = 48.dp

@Composable
fun FleetInvitationHost(onOpenGame: (String) -> Unit) {
    val coordinator = org.koin.compose.koinInject<FleetGameCoordinator>()
    val viewModel = org.koin.compose.viewmodel.koinViewModel<FleetGameViewModel>()
    val invitations by viewModel.invitations.collectAsStateWithLifecycle()
    val invitation = invitations.values.firstOrNull()

    LaunchedEffect(coordinator) { coordinator.observeDirectMessages() }

    invitation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.respondToInvitation(pending.gameId, accept = false) },
            title = { Text(stringResource(Res.string.fleet_invitation_title)) },
            text = { Text(stringResource(Res.string.fleet_invitation_message, pending.opponentName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.respondToInvitation(pending.gameId, accept = true, onAccepted = onOpenGame)
                    },
                ) {
                    Text(stringResource(Res.string.fleet_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondToInvitation(pending.gameId, accept = false) }) {
                    Text(stringResource(Res.string.fleet_decline))
                }
            },
        )
    }
}

@Composable
@Suppress("LongMethod")
fun FleetGameScreen(
    gameId: String,
    viewModel: FleetGameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SideEffect { viewModel.setGameId(gameId) }
    val game by viewModel.game.collectAsStateWithLifecycle()
    var showResignConfirmation by rememberSaveable(gameId) { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.fleet_game_title),
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateBack,
                onClickChip = {},
                actions = {},
            )
        },
    ) { padding ->
        val session = game
        if (session == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(Res.string.fleet_game_loading))
            }
        } else {
            Column(
                modifier =
                Modifier.fillMaxWidth().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(session.opponentName, style = MaterialTheme.typography.titleMedium)
                Text(statusText(session), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text(stringResource(Res.string.fleet_opponent_board), style = MaterialTheme.typography.titleSmall)
                FleetBoardGrid(
                    board = session.board,
                    shots = session.incomingShots,
                    shotResults = session.outgoingResults,
                    outgoingShot = session.outgoingShot,
                    ownBoard = false,
                    enabled =
                    session.phase == FleetGamePhase.PLAYING &&
                        session.localTurn &&
                        session.pendingResult?.allShipsSunk != true,
                    onCellClick = { viewModel.fireShot(gameId, it) },
                )
                Text(stringResource(Res.string.fleet_my_board), style = MaterialTheme.typography.titleSmall)
                FleetBoardGrid(
                    board = session.board,
                    shots = session.incomingShots,
                    shotResults = session.outgoingResults,
                    outgoingShot = session.outgoingShot,
                    ownBoard = true,
                    enabled = false,
                    onCellClick = {},
                )
                when {
                    session.pendingResult?.allShipsSunk == true && session.phase == FleetGamePhase.PLAYING -> {
                        TextButton(onClick = { viewModel.reportDefeat(gameId) }) {
                            Text(stringResource(Res.string.fleet_report_defeat))
                        }
                    }

                    session.phase == FleetGamePhase.PLAYING -> {
                        TextButton(onClick = { showResignConfirmation = true }) {
                            Text(stringResource(Res.string.fleet_resign))
                        }
                    }
                }
            }
        }
    }

    if (showResignConfirmation) {
        AlertDialog(
            onDismissRequest = { showResignConfirmation = false },
            text = { Text(stringResource(Res.string.fleet_resign_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResignConfirmation = false
                        viewModel.resign(gameId)
                    },
                ) {
                    Text(stringResource(Res.string.fleet_resign))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResignConfirmation = false }) {
                    Text(stringResource(Res.string.fleet_decline))
                }
            },
        )
    }
}

@Composable
private fun FleetBoardGrid(
    board: FleetBoard,
    shots: Set<Int>,
    shotResults: Map<Int, FleetShotResult>,
    outgoingShot: Int?,
    ownBoard: Boolean,
    enabled: Boolean,
    onCellClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Box(modifier = Modifier.size(24.dp))
            repeat(FLEET_BOARD_SIZE) { column ->
                Text(
                    text = ('A'.code + column).toChar().toString(),
                    modifier = Modifier.size(FLEET_CELL_SIZE),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        repeat(FLEET_BOARD_SIZE) { row ->
            Row {
                Text(
                    text = (row + 1).toString(),
                    modifier =
                    Modifier.size(width = 24.dp, height = FLEET_CELL_SIZE)
                        .wrapContentHeight(align = Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                )
                repeat(FLEET_BOARD_SIZE) { column ->
                    val cell = row * FLEET_BOARD_SIZE + column
                    FleetBoardCell(
                        cell = cell,
                        row = row,
                        column = column,
                        board = board,
                        shots = shots,
                        shotResults = shotResults,
                        outgoingShot = outgoingShot,
                        ownBoard = ownBoard,
                        enabled = enabled,
                        onCellClick = onCellClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun FleetBoardCell(
    cell: Int,
    row: Int,
    column: Int,
    board: FleetBoard,
    shots: Set<Int>,
    shotResults: Map<Int, FleetShotResult>,
    outgoingShot: Int?,
    ownBoard: Boolean,
    enabled: Boolean,
    onCellClick: (Int) -> Unit,
) {
    val result = shotResults[cell]
    val isHit = cell in shots && cell in board.occupiedCells
    val isShot = cell in shots
    val isShip = cell in board.occupiedCells
    val presentation = fleetCellPresentation(ownBoard, isHit, isShot, isShip, result, cell == outgoingShot)
    val coordinate = "${('A'.code + column).toChar()}${row + 1}"
    val cellDescription = stringResource(Res.string.fleet_board_cell, coordinate, presentation.description)
    val isClickable = enabled && result == null && cell != outgoingShot
    Box(
        modifier =
        Modifier.size(FLEET_CELL_SIZE)
            .padding(1.dp)
            .background(presentation.background, MaterialTheme.shapes.extraSmall)
            .then(if (isClickable) Modifier.clickable(role = Role.Button) { onCellClick(cell) } else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = cellDescription
                if (isClickable) role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = presentation.glyph,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun fleetCellPresentation(
    ownBoard: Boolean,
    isHit: Boolean,
    isShot: Boolean,
    isShip: Boolean,
    result: FleetShotResult?,
    isOutgoingShot: Boolean,
): FleetCellPresentation = when {
    ownBoard && isHit ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_hit),
            "×",
            MaterialTheme.colorScheme.errorContainer,
        )

    ownBoard && isShot ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_miss),
            "·",
            MaterialTheme.colorScheme.surfaceVariant,
        )

    ownBoard && isShip ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_ship),
            "■",
            MaterialTheme.colorScheme.primaryContainer,
        )

    ownBoard ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_water),
            "",
            MaterialTheme.colorScheme.surface,
        )

    result?.hit == true ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_hit),
            "×",
            MaterialTheme.colorScheme.errorContainer,
        )

    result != null ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_miss),
            "·",
            MaterialTheme.colorScheme.surfaceVariant,
        )

    isOutgoingShot ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_pending),
            "…",
            MaterialTheme.colorScheme.tertiaryContainer,
        )

    else ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_unknown),
            "",
            MaterialTheme.colorScheme.surface,
        )
}

private data class FleetCellPresentation(val description: String, val glyph: String, val background: Color)

@Composable
private fun statusText(game: FleetGameSession): String = when (game.phase) {
    FleetGamePhase.INVITING -> stringResource(Res.string.fleet_turn_waiting, game.opponentName)

    FleetGamePhase.DECLINED -> stringResource(Res.string.fleet_decline)

    FleetGamePhase.FINISHED ->
        when (game.result) {
            FleetGameResult.WON -> stringResource(Res.string.fleet_result_won)
            FleetGameResult.LOST -> stringResource(Res.string.fleet_result_lost)
            FleetGameResult.RESIGNED -> stringResource(Res.string.fleet_result_resigned)
            null -> stringResource(Res.string.fleet_result_lost)
        }

    FleetGamePhase.PLAYING ->
        if (game.localTurn) {
            when {
                game.pendingResult?.allShipsSunk == true -> stringResource(Res.string.fleet_result_lost)

                game.pendingResult?.sunkShipLength != null ->
                    stringResource(Res.string.fleet_result_sunk, game.pendingResult.sunkShipLength)

                game.pendingResult?.hit == true -> stringResource(Res.string.fleet_result_hit)

                game.pendingResult != null -> stringResource(Res.string.fleet_result_miss)

                else -> stringResource(Res.string.fleet_turn_yours)
            }
        } else {
            stringResource(Res.string.fleet_turn_waiting, game.opponentName)
        }
}
