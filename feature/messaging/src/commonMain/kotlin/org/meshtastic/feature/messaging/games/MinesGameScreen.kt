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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.mines_board_cell
import org.meshtastic.core.resources.mines_cell_hidden
import org.meshtastic.core.resources.mines_cell_mine
import org.meshtastic.core.resources.mines_cell_number
import org.meshtastic.core.resources.mines_cell_pending
import org.meshtastic.core.resources.mines_cell_zero
import org.meshtastic.core.resources.mines_game_loading
import org.meshtastic.core.resources.mines_game_title
import org.meshtastic.core.resources.mines_player_stats
import org.meshtastic.core.resources.mines_resign
import org.meshtastic.core.resources.mines_result_draw
import org.meshtastic.core.resources.mines_result_lost
import org.meshtastic.core.resources.mines_result_won
import org.meshtastic.core.resources.mines_retry
import org.meshtastic.core.resources.mines_reveal
import org.meshtastic.core.resources.mines_reveal_at
import org.meshtastic.core.resources.mines_status_declined
import org.meshtastic.core.resources.mines_status_inviting
import org.meshtastic.core.resources.mines_turn_waiting
import org.meshtastic.core.resources.mines_turn_yours
import org.meshtastic.core.resources.mines_you
import org.meshtastic.core.ui.component.MainAppBar

private val MINES_BOARD_MINIMUM = 274.dp
private val MINES_BOARD_MAXIMUM = 420.dp
private val MINES_CELL_GAP = 2.dp

@Composable
fun MinesGameScreen(
    gameId: String,
    viewModel: MinesGameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SideEffect(gameId) { viewModel.setGameId(gameId) }
    val game by viewModel.game.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.mines_game_title),
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateBack,
                onClickChip = {},
                actions = {},
            )
        },
    ) { padding ->
        val session = game?.takeIf { it.gameId == gameId }
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.mines_game_loading))
            }
        } else {
            MinesGameContent(
                session = session,
                onReveal = { viewModel.reveal(gameId, it) },
                onRetry = { viewModel.retry(gameId) },
                onResign = { viewModel.resign(gameId) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
internal fun MinesGameContent(
    session: MinesSession,
    onReveal: (Int) -> Unit,
    onRetry: () -> Unit,
    onResign: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCell by rememberSaveable(session.gameId, session.match.turn) { mutableStateOf<Int?>(null) }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        // Keep real touch targets on short windows; the surrounding column can scroll instead.
        val heightBudget = (maxHeight - (108.dp * fontScale + 64.dp)).coerceAtLeast(MINES_BOARD_MINIMUM)
        val boardSize = minOf(maxWidth - 16.dp, MINES_BOARD_MAXIMUM, heightBudget)
        Column(
            modifier =
            Modifier.widthIn(max = MINES_BOARD_MAXIMUM + 16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MinesScoreboard(session)
            Text(
                text = minesStatus(session),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            MinesBoardGrid(session, selectedCell, { selectedCell = it }, Modifier.size(boardSize))
            MinesActions(session, selectedCell, onReveal, onRetry, onResign)
        }
    }
}

@Composable
private fun MinesBoardGrid(
    session: MinesSession,
    selectedCell: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val cellSize = (maxWidth - MINES_CELL_GAP * (MINES_SIZE - 1)) / MINES_SIZE
        Column(verticalArrangement = Arrangement.spacedBy(MINES_CELL_GAP)) {
            repeat(MINES_SIZE) { row ->
                MinesBoardRow(session, row, selectedCell, onSelect, cellSize)
            }
        }
    }
}

@Composable
private fun MinesBoardRow(
    session: MinesSession,
    row: Int,
    selectedCell: Int?,
    onSelect: (Int) -> Unit,
    cellSize: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MINES_CELL_GAP)) {
        repeat(MINES_SIZE) { column ->
            val cell = row * MINES_SIZE + column
            MinesCell(cell, session, selectedCell == cell, { onSelect(cell) }, Modifier.size(cellSize))
        }
    }
}

@Composable
private fun MinesScoreboard(session: MinesSession) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) { player ->
            val name = if (player == session.localPlayer) stringResource(Res.string.mines_you) else session.opponentName
            val score = session.match.scores[player]
            val lives = session.match.lives[player]
            val description = stringResource(Res.string.mines_player_stats, name, score, lives)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description }) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "$score  ${"♥".repeat(lives)}${"♡".repeat(MINES_LIVES - lives)}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun minesStatus(session: MinesSession): String = stringResource(
    when {
        session.localResigned -> Res.string.mines_result_lost

        session.opponentResigned -> Res.string.mines_result_won

        session.match.finished || session.phase == MinesPhase.FINISHED ->
            when (session.match.winner) {
                session.localPlayer -> Res.string.mines_result_won
                null -> Res.string.mines_result_draw
                else -> Res.string.mines_result_lost
            }

        session.phase == MinesPhase.INVITING -> Res.string.mines_status_inviting

        session.phase == MinesPhase.DECLINED -> Res.string.mines_status_declined

        session.pendingCell != null -> Res.string.mines_cell_pending

        session.match.activePlayer == session.localPlayer -> Res.string.mines_turn_yours

        else -> Res.string.mines_turn_waiting
    },
)

@Composable
private fun MinesActions(
    session: MinesSession,
    selectedCell: Int?,
    onReveal: (Int) -> Unit,
    onRetry: () -> Unit,
    onResign: () -> Unit,
) {
    val retryAvailable =
        session.pendingCell != null ||
            session.replyPending ||
            session.localResigned ||
            session.phase == MinesPhase.INVITING ||
            (
                session.phase == MinesPhase.PLAYING &&
                    !session.isHost &&
                    session.match.activePlayer != session.localPlayer
                )
    val active =
        session.phase == MinesPhase.PLAYING &&
            !session.match.finished &&
            !session.localResigned &&
            !session.opponentResigned
    val target = selectedCell?.takeIf { it !in session.match.revealed }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                if (retryAvailable) {
                    onRetry()
                } else if (session.canReveal && target != null) {
                    onReveal(target)
                }
            },
            enabled = retryAvailable || (active && session.canReveal && target != null),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when {
                    retryAvailable -> stringResource(Res.string.mines_retry)
                    target != null -> stringResource(Res.string.mines_reveal_at, minesCoordinate(target))
                    else -> stringResource(Res.string.mines_reveal)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (session.phase == MinesPhase.PLAYING || session.phase == MinesPhase.INVITING) {
            TextButton(onClick = onResign) { Text(stringResource(Res.string.mines_resign)) }
        }
    }
}

@Composable
private fun MinesCell(
    cell: Int,
    session: MinesSession,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = session.match.revealed[cell]
    val pending = session.pendingCell == cell
    val state = minesCellState(value, pending)
    val description = stringResource(Res.string.mines_board_cell, minesCoordinate(cell), state)
    val colors = MaterialTheme.colorScheme
    val background =
        when {
            pending -> colors.tertiaryContainer
            value == -1 -> colors.errorContainer
            isSelected && value == null -> colors.primaryContainer
            value != null -> colors.surfaceContainer
            else -> colors.surfaceContainerHighest
        }

    val foreground =
        when {
            pending -> colors.onTertiaryContainer
            value == -1 -> colors.onErrorContainer
            isSelected && value == null -> colors.onPrimaryContainer
            else -> colors.onSurface
        }
    val interactive = session.canReveal && value == null
    Surface(
        modifier =
        modifier
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .then(if (interactive) Modifier.clickable(role = Role.Button, onClick = onSelect) else Modifier),
        shape = RoundedCornerShape(4.dp),
        color = background,
        contentColor = foreground,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.primary else colors.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text =
                when {
                    pending -> "…"
                    value == null -> "■"
                    value == -1 -> "✹"
                    value == 0 -> "○"
                    else -> value.toString()
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun minesCellState(value: Int?, pending: Boolean): String = when {
    pending -> stringResource(Res.string.mines_cell_pending)
    value == null -> stringResource(Res.string.mines_cell_hidden)
    value == -1 -> stringResource(Res.string.mines_cell_mine)
    value == 0 -> stringResource(Res.string.mines_cell_zero)
    else -> stringResource(Res.string.mines_cell_number, value)
}
