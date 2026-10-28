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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.fleet_accept
import org.meshtastic.core.resources.fleet_aim
import org.meshtastic.core.resources.fleet_board_cell
import org.meshtastic.core.resources.fleet_cell_hit
import org.meshtastic.core.resources.fleet_cell_miss
import org.meshtastic.core.resources.fleet_cell_pending
import org.meshtastic.core.resources.fleet_cell_ship
import org.meshtastic.core.resources.fleet_cell_unknown
import org.meshtastic.core.resources.fleet_cell_water
import org.meshtastic.core.resources.fleet_cooldown
import org.meshtastic.core.resources.fleet_decline
import org.meshtastic.core.resources.fleet_fire
import org.meshtastic.core.resources.fleet_fire_at
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
import org.meshtastic.core.ui.theme.StatusColors.StatusBlue

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
            FleetGameContent(
                session = session,
                onCellClick = { viewModel.fireShot(gameId, it) },
                onReaction = { viewModel.sendReaction(gameId, it) },
                modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp),
            ) {
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
internal fun FleetBoards(
    session: FleetGameSession,
    onCellClick: (Int) -> Unit,
    aiming: Boolean,
    onAimingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCell by rememberSaveable(session.gameId, session.turn) { mutableStateOf<Int?>(null) }
    val remainingSeconds = rememberFleetCountdown(session.lastShotAt, FLEET_SHOT_INTERVAL)
    val canFire = remainingSeconds == 0 && session.canFireShot
    val fireControl: @Composable () -> Unit = {
        Button(
            enabled = canFire && selectedCell != null,
            onClick = {
                selectedCell
                    ?.takeIf { canFire }
                    ?.let {
                        selectedCell = null
                        onAimingChange(false)
                        onCellClick(it)
                    }
            },
        ) {
            Text(
                if (remainingSeconds > 0) {
                    stringResource(Res.string.fleet_cooldown, remainingSeconds)
                } else {
                    selectedCell?.let {
                        stringResource(Res.string.fleet_fire_at, fleetCoordinate(it))
                    } ?: stringResource(Res.string.fleet_fire)
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    if (aiming) {
        FleetTargeting(
            session,
            canFire,
            selectedCell,
            { selectedCell = it },
            { onAimingChange(false) },
            fireControl,
            modifier,
        )
        return
    }
    val aimControl: @Composable () -> Unit = {
        Button(onClick = { onAimingChange(true) }) {
            Text(stringResource(Res.string.fleet_aim), maxLines = 1, softWrap = false)
        }
    }
    FleetBoardPanel(session, true, modifier.fillMaxWidth(), action = aimControl)
}

@Composable
private fun FleetBoardPanel(
    session: FleetGameSession,
    ownBoard: Boolean,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(if (ownBoard) Res.string.fleet_my_board else Res.string.fleet_opponent_board),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            action()
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val cellSize =
                minOf(
                    maxWidth / FLEET_BOARD_SIZE,
                    maxHeight / FLEET_BOARD_SIZE,
                )
            FleetBoardGrid(
                board = session.board,
                shots = session.incomingShots,
                shotResults = session.outgoingResults,
                outgoingShot = session.outgoingShot,
                ownBoard = ownBoard,
                enabled = false,
                onCellClick = {},
                cellSize = cellSize,
                showCoordinates = false,
            )
        }
    }
}

@Composable
internal fun FleetBoardGrid(
    board: FleetBoard,
    shots: Set<Int>,
    shotResults: Map<Int, FleetShotResult>,
    outgoingShot: Int?,
    ownBoard: Boolean,
    enabled: Boolean,
    onCellClick: (Int) -> Unit,
    cellSize: Dp = FLEET_CELL_SIZE,
    selectedCell: Int? = null,
    cellWidth: Dp = cellSize,
    region: FleetTargetRegion = FleetTargetRegion(),
    showCoordinates: Boolean = true,
) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        if (showCoordinates) {
            Row {
                Box(modifier = Modifier.size(20.dp))
                region.columns.forEach { column ->
                    Text(
                        text = ('A'.code + column).toChar().toString(),
                        modifier = Modifier.size(width = cellWidth, height = 20.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        region.rows.forEach { row ->
            Row {
                if (showCoordinates) {
                    Text(
                        text = (row + 1).toString(),
                        modifier =
                        Modifier.size(width = 20.dp, height = cellSize)
                            .wrapContentHeight(align = Alignment.CenterVertically),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                region.columns.forEach { column ->
                    val cell = row * FLEET_BOARD_SIZE + column
                    FleetBoardCell(
                        cell = cell,
                        board = board,
                        shots = shots,
                        shotResults = shotResults,
                        outgoingShot = outgoingShot,
                        ownBoard = ownBoard,
                        enabled = enabled,
                        onCellClick = onCellClick,
                        cellSize = cellSize,
                        cellWidth = cellWidth,
                        isSelected = selectedCell == cell,
                    )
                }
            }
        }
    }
}

@Composable
private fun FleetBoardCell(
    cell: Int,
    board: FleetBoard,
    shots: Set<Int>,
    shotResults: Map<Int, FleetShotResult>,
    outgoingShot: Int?,
    ownBoard: Boolean,
    enabled: Boolean,
    onCellClick: (Int) -> Unit,
    cellSize: Dp,
    cellWidth: Dp,
    isSelected: Boolean,
) {
    val result = shotResults[cell]
    val isHit = cell in shots && cell in board.occupiedCells
    val isShot = cell in shots
    val isShip = cell in board.occupiedCells
    val presentation = fleetCellPresentation(ownBoard, isHit, isShot, isShip, result, cell == outgoingShot)
    val coordinate = fleetCoordinate(cell)
    val cellDescription = stringResource(Res.string.fleet_board_cell, coordinate, presentation.description)
    val isClickable = enabled && result == null && cell != outgoingShot
    Box(
        modifier =
        Modifier.size(width = cellWidth, height = cellSize)
            .background(presentation.background, RoundedCornerShape(1.dp))
            .border(
                if (isSelected) 2.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(1.dp),
            )
            .then(if (isClickable) Modifier.clickable(role = Role.Button) { onCellClick(cell) } else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = cellDescription
                selected = isSelected
                if (isClickable) role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        FleetSea(Modifier.matchParentSize())
        if (ownBoard && isShip) {
            val ship = board.ships.first { cell in it.cells }
            FleetShipSegment(ship, cell, Modifier.matchParentSize())
        }
        Text(
            text = presentation.glyph,
            modifier =
            if (ownBoard && isHit) {
                Modifier.background(MaterialTheme.colorScheme.errorContainer, CircleShape).padding(4.dp)
            } else {
                Modifier
            },
            style = MaterialTheme.typography.labelSmall,
            color =
            if (ownBoard && isHit) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
        )
    }
}

private fun fleetCoordinate(cell: Int): String =
    "${('A'.code + cell % FLEET_BOARD_SIZE).toChar()}${cell / FLEET_BOARD_SIZE + 1}"

@Composable
@Suppress("MagicNumber")
private fun FleetSea(modifier: Modifier = Modifier) {
    val waveColor = MaterialTheme.colorScheme.StatusBlue.copy(alpha = 0.3f)
    Canvas(modifier) {
        val waves =
            Path().apply {
                for (height in listOf(0.25f, 0.75f)) {
                    moveTo(size.width * 0.1f, size.height * height)
                    cubicTo(
                        size.width * 0.35f,
                        size.height * (height - 0.2f),
                        size.width * 0.65f,
                        size.height * (height + 0.2f),
                        size.width * 0.9f,
                        size.height * height,
                    )
                }
            }
        drawPath(waves, waveColor, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
@Suppress("MagicNumber")
private fun FleetShipSegment(ship: FleetShip, cell: Int, modifier: Modifier = Modifier) {
    val hullColor = MaterialTheme.colorScheme.primary
    val deckColor = MaterialTheme.colorScheme.onPrimary
    val first = ship.cells.min()
    val last = ship.cells.max()
    val vertical = last - first >= FLEET_BOARD_SIZE
    Canvas(modifier) {
        rotate(if (vertical) 90f else 0f) {
            val width = if (vertical) size.height else size.width
            val height = if (vertical) size.width else size.height
            val shiftX = (size.width - width) / 2f
            val shiftY = (size.height - height) / 2f
            drawContext.transform.translate(shiftX, shiftY)
            val stern = if (cell == first) width * 0.12f else 0f
            val hull =
                Path().apply {
                    moveTo(stern, height * 0.22f)
                    if (cell == last) {
                        lineTo(width * 0.6f, height * 0.22f)
                        quadraticTo(width * 0.85f, height * 0.28f, width * 0.96f, height * 0.5f)
                        quadraticTo(width * 0.85f, height * 0.72f, width * 0.6f, height * 0.78f)
                    } else {
                        lineTo(width, height * 0.22f)
                        lineTo(width, height * 0.78f)
                    }
                    lineTo(stern, height * 0.78f)
                    close()
                }
            drawPath(hull, hullColor)
            drawRect(
                color = deckColor,
                topLeft = Offset(width * 0.25f, height * 0.36f),
                size = Size(width * 0.3f, height * 0.28f),
            )
            drawContext.transform.translate(-shiftX, -shiftY)
        }
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
            "",
            MaterialTheme.colorScheme.StatusBlue.copy(alpha = 0.12f),
        )

    ownBoard ->
        FleetCellPresentation(
            stringResource(Res.string.fleet_cell_water),
            "",
            MaterialTheme.colorScheme.StatusBlue.copy(alpha = 0.12f),
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
            MaterialTheme.colorScheme.StatusBlue.copy(alpha = 0.12f),
        )
}

private data class FleetCellPresentation(val description: String, val glyph: String, val background: Color)

@Composable
internal fun statusText(game: FleetGameSession): String = when (game.phase) {
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
