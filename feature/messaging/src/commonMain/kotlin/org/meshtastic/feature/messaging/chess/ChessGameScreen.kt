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
package org.meshtastic.feature.messaging.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.chess_accept_draw
import org.meshtastic.core.resources.chess_check
import org.meshtastic.core.resources.chess_choose_promotion
import org.meshtastic.core.resources.chess_claim_fifty_move
import org.meshtastic.core.resources.chess_claim_threefold
import org.meshtastic.core.resources.chess_decline
import org.meshtastic.core.resources.chess_decline_draw
import org.meshtastic.core.resources.chess_draw_offer_received
import org.meshtastic.core.resources.chess_finish_agreed_draw
import org.meshtastic.core.resources.chess_finish_checkmate
import org.meshtastic.core.resources.chess_finish_declined
import org.meshtastic.core.resources.chess_finish_fifty_move
import org.meshtastic.core.resources.chess_finish_fivefold
import org.meshtastic.core.resources.chess_finish_insufficient_material
import org.meshtastic.core.resources.chess_finish_resigned
import org.meshtastic.core.resources.chess_finish_seventy_five_move
import org.meshtastic.core.resources.chess_finish_stalemate
import org.meshtastic.core.resources.chess_finish_threefold
import org.meshtastic.core.resources.chess_finish_timeout
import org.meshtastic.core.resources.chess_game_title
import org.meshtastic.core.resources.chess_loading
import org.meshtastic.core.resources.chess_offer_draw
import org.meshtastic.core.resources.chess_piece_bishop
import org.meshtastic.core.resources.chess_piece_king
import org.meshtastic.core.resources.chess_piece_knight
import org.meshtastic.core.resources.chess_piece_pawn
import org.meshtastic.core.resources.chess_piece_queen
import org.meshtastic.core.resources.chess_piece_rook
import org.meshtastic.core.resources.chess_play_as_black
import org.meshtastic.core.resources.chess_play_as_white
import org.meshtastic.core.resources.chess_promotion_bishop
import org.meshtastic.core.resources.chess_promotion_knight
import org.meshtastic.core.resources.chess_promotion_queen
import org.meshtastic.core.resources.chess_promotion_rook
import org.meshtastic.core.resources.chess_resign
import org.meshtastic.core.resources.chess_resign_confirmation
import org.meshtastic.core.resources.chess_side_black
import org.meshtastic.core.resources.chess_side_white
import org.meshtastic.core.resources.chess_square_empty
import org.meshtastic.core.resources.chess_square_piece
import org.meshtastic.core.resources.chess_turn_clock
import org.meshtastic.core.resources.chess_waiting_for_acceptance
import org.meshtastic.core.resources.chess_waiting_for_opponent
import org.meshtastic.core.resources.chess_you
import org.meshtastic.core.resources.chess_your_turn
import org.meshtastic.core.ui.component.MainAppBar

@Composable
@Suppress("LongMethod")
fun ChessGameScreen(
    gameId: String,
    viewModel: ChessGameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SideEffect { viewModel.setGameId(gameId) }
    val game by viewModel.game.collectAsStateWithLifecycle()
    var currentTime by remember { mutableLongStateOf(nowMillis) }
    var selectedSquare by rememberSaveable(gameId, game?.ply) { mutableIntStateOf(NO_SQUARE_SELECTED) }
    var pendingPromotion by remember { mutableStateOf<List<ChessMove>?>(null) }
    var showResignConfirmation by rememberSaveable(gameId) { mutableStateOf(false) }

    LaunchedEffect(gameId, game?.turnDeadlineMillis, game?.phase) {
        while (true) {
            currentTime = nowMillis
            if (game?.turnDeadlineMillis?.let { currentTime >= it } == true) {
                viewModel.checkTurnTimeout(gameId, currentTime)
            }
            delay(CLOCK_TICK_MILLIS)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.chess_game_title),
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
                Text(stringResource(Res.string.chess_loading))
            }
        } else {
            val position = session.position
            val canMove =
                session.phase == ChessGamePhase.PLAYING &&
                    position.sideToMove == session.localSide &&
                    session.turnDeadlineMillis?.let { currentTime < it } != false
            val selectableMoves =
                if (selectedSquare == NO_SQUARE_SELECTED) emptyList() else position.legalMovesFrom(selectedSquare)

            Column(
                modifier = Modifier.fillMaxWidth().padding(padding).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = session.opponentName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text =
                    stringResource(
                        if (session.localSide == ChessSide.WHITE) {
                            Res.string.chess_play_as_white
                        } else {
                            Res.string.chess_play_as_black
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = session.statusText(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                session.turnDeadlineMillis?.let { deadline ->
                    val seconds = ((deadline - currentTime).coerceAtLeast(0L) / MILLIS_PER_SECOND).toInt()
                    val clock =
                        "${seconds / SECONDS_PER_MINUTE}:" + (seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')
                    Text(
                        text = stringResource(Res.string.chess_turn_clock, clock),
                        style = MaterialTheme.typography.titleMedium,
                        color =
                        if (seconds <= LOW_TIME_SECONDS) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                ChessBoard(
                    position = position,
                    localSide = session.localSide,
                    selectedSquare = selectedSquare,
                    possibleMoves = selectableMoves,
                    enabled = canMove,
                    onSquareClick = { square ->
                        val movesToSquare = selectableMoves.filter { it.to == square }
                        when {
                            movesToSquare.size > 1 -> pendingPromotion = movesToSquare

                            movesToSquare.size == 1 -> {
                                viewModel.playMove(gameId, movesToSquare.single())
                                selectedSquare = NO_SQUARE_SELECTED
                            }

                            position.pieceAt(square)?.side == session.localSide && canMove -> selectedSquare = square

                            else -> selectedSquare = NO_SQUARE_SELECTED
                        }
                    },
                )
                if (session.phase == ChessGamePhase.PLAYING) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (session.drawOfferedBy == null) {
                            TextButton(onClick = { viewModel.offerDraw(gameId) }) {
                                Text(stringResource(Res.string.chess_offer_draw))
                            }
                        }
                        if (position.sideToMove == session.localSide && position.canClaimThreefoldRepetition()) {
                            TextButton(
                                onClick = {
                                    viewModel.claimDraw(gameId, ClaimableDraw.THREEFOLD_REPETITION)
                                },
                            ) {
                                Text(stringResource(Res.string.chess_claim_threefold))
                            }
                        }
                        if (position.sideToMove == session.localSide && position.canClaimFiftyMoveDraw()) {
                            TextButton(onClick = { viewModel.claimDraw(gameId, ClaimableDraw.FIFTY_MOVE_RULE) }) {
                                Text(stringResource(Res.string.chess_claim_fifty_move))
                            }
                        }
                        TextButton(onClick = { showResignConfirmation = true }) {
                            Text(stringResource(Res.string.chess_resign), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            pendingPromotion?.let { promotionMoves ->
                AlertDialog(
                    onDismissRequest = { pendingPromotion = null },
                    title = { Text(stringResource(Res.string.chess_choose_promotion)) },
                    confirmButton = {},
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            promotionMoves.forEach { move ->
                                TextButton(
                                    onClick = {
                                        viewModel.playMove(gameId, move)
                                        pendingPromotion = null
                                        selectedSquare = NO_SQUARE_SELECTED
                                    },
                                ) {
                                    Text(stringResource(move.promotion.toPromotionStringResource()))
                                }
                            }
                        }
                    },
                )
            }

            if (session.drawOfferedBy == session.localSide.opposite) {
                AlertDialog(
                    onDismissRequest = { viewModel.respondToDraw(gameId, accept = false) },
                    title = { Text(stringResource(Res.string.chess_offer_draw)) },
                    text = { Text(stringResource(Res.string.chess_draw_offer_received, session.opponentName)) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.respondToDraw(gameId, accept = true) }) {
                            Text(stringResource(Res.string.chess_accept_draw))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.respondToDraw(gameId, accept = false) }) {
                            Text(stringResource(Res.string.chess_decline_draw))
                        }
                    },
                )
            }
            if (showResignConfirmation) {
                AlertDialog(
                    onDismissRequest = { showResignConfirmation = false },
                    title = { Text(stringResource(Res.string.chess_resign)) },
                    text = { Text(stringResource(Res.string.chess_resign_confirmation)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showResignConfirmation = false
                                viewModel.resign(gameId)
                            },
                        ) {
                            Text(stringResource(Res.string.chess_resign), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResignConfirmation = false }) {
                            Text(stringResource(Res.string.chess_decline))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ChessBoard(
    position: ChessPosition,
    localSide: ChessSide,
    selectedSquare: Int,
    possibleMoves: List<ChessMove>,
    enabled: Boolean,
    onSquareClick: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Fit the whole board to the screen; a fixed minimum width clipped the last file on narrow phones.
        val boardWidth = maxWidth.coerceAtMost(MAX_BOARD_WIDTH)
        val pieceSize = with(LocalDensity.current) { (boardWidth / BOARD_WIDTH * PIECE_SCALE).toSp() }
        Column(modifier = Modifier.width(boardWidth)) {
            val ranks = if (localSide == ChessSide.WHITE) BOARD_INDEX downTo 0 else 0..BOARD_INDEX
            val files = if (localSide == ChessSide.WHITE) 0..BOARD_INDEX else BOARD_INDEX downTo 0
            ranks.forEach { rank ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    files.forEach { file ->
                        val square = rank * BOARD_WIDTH + file
                        ChessSquare(
                            square = square,
                            piece = position.pieceAt(square),
                            isLight = (file + rank) % 2 == 0,
                            isSelected = square == selectedSquare,
                            isPossibleMove = possibleMoves.any { it.to == square },
                            enabled = enabled,
                            onClick = { onSquareClick(square) },
                            pieceSize = pieceSize,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChessSquare(
    square: Int,
    piece: ChessPiece?,
    isLight: Boolean,
    isSelected: Boolean,
    isPossibleMove: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    pieceSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val background =
        when {
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            isPossibleMove -> MaterialTheme.colorScheme.tertiaryContainer
            isLight -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    val description =
        if (piece == null) {
            stringResource(Res.string.chess_square_empty, chessSquareName(square))
        } else {
            stringResource(
                Res.string.chess_square_piece,
                stringResource(
                    if (piece.side == ChessSide.WHITE) Res.string.chess_side_white else Res.string.chess_side_black,
                ),
                stringResource(piece.type.toPieceStringResource()),
                chessSquareName(square),
            )
        }
    Box(
        modifier =
        modifier
            .background(background, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        piece?.let {
            Text(
                text = it.glyph(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = pieceSize,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ChessGameSession.statusText(): String = when (phase) {
    ChessGamePhase.INVITING -> stringResource(Res.string.chess_waiting_for_acceptance, opponentName)

    ChessGamePhase.DECLINED -> stringResource(Res.string.chess_finish_declined)

    ChessGamePhase.FINISHED -> resultText()

    ChessGamePhase.PLAYING ->
        when (position.outcome) {
            ChessOutcome.CHECK -> stringResource(Res.string.chess_check)

            else ->
                if (position.sideToMove == localSide) {
                    stringResource(Res.string.chess_your_turn)
                } else {
                    stringResource(Res.string.chess_waiting_for_opponent, opponentName)
                }
        }
}

@Composable
private fun ChessGameSession.resultText(): String {
    result?.let { gameResult ->
        return when (gameResult.reason) {
            ChessFinishReason.RESIGNATION ->
                stringResource(
                    Res.string.chess_finish_resigned,
                    gameResult.loser?.let { playerName(it) } ?: opponentName,
                )

            ChessFinishReason.TIMEOUT ->
                stringResource(
                    Res.string.chess_finish_timeout,
                    gameResult.loser?.let { playerName(it) } ?: opponentName,
                )

            ChessFinishReason.AGREED_DRAW -> stringResource(Res.string.chess_finish_agreed_draw)
        }
    }
    return when (position.outcome) {
        ChessOutcome.CHECKMATE ->
            stringResource(Res.string.chess_finish_checkmate, playerName(position.sideToMove.opposite))

        ChessOutcome.STALEMATE -> stringResource(Res.string.chess_finish_stalemate)

        ChessOutcome.DRAW_THREEFOLD_REPETITION -> stringResource(Res.string.chess_finish_threefold)

        ChessOutcome.DRAW_FIFTY_MOVE_RULE -> stringResource(Res.string.chess_finish_fifty_move)

        ChessOutcome.DRAW_FIVEFOLD_REPETITION -> stringResource(Res.string.chess_finish_fivefold)

        ChessOutcome.DRAW_SEVENTY_FIVE_MOVE_RULE -> stringResource(Res.string.chess_finish_seventy_five_move)

        ChessOutcome.DRAW_INSUFFICIENT_MATERIAL -> stringResource(Res.string.chess_finish_insufficient_material)

        ChessOutcome.ACTIVE,
        ChessOutcome.CHECK,
        -> stringResource(Res.string.chess_game_title)
    }
}

@Composable
private fun ChessGameSession.playerName(side: ChessSide): String = if (side == localSide) {
    stringResource(Res.string.chess_you)
} else {
    opponentName
}

private fun ChessPieceType.toPieceStringResource() = when (this) {
    ChessPieceType.KING -> Res.string.chess_piece_king
    ChessPieceType.QUEEN -> Res.string.chess_piece_queen
    ChessPieceType.ROOK -> Res.string.chess_piece_rook
    ChessPieceType.BISHOP -> Res.string.chess_piece_bishop
    ChessPieceType.KNIGHT -> Res.string.chess_piece_knight
    ChessPieceType.PAWN -> Res.string.chess_piece_pawn
}

private fun ChessPieceType?.toPromotionStringResource() = when (this) {
    ChessPieceType.QUEEN -> Res.string.chess_promotion_queen

    ChessPieceType.ROOK -> Res.string.chess_promotion_rook

    ChessPieceType.BISHOP -> Res.string.chess_promotion_bishop

    ChessPieceType.KNIGHT -> Res.string.chess_promotion_knight

    ChessPieceType.KING,
    ChessPieceType.PAWN,
    null,
    -> Res.string.chess_piece_queen
}

private fun ChessPiece.glyph(): String = when (side) {
    ChessSide.WHITE ->
        when (type) {
            ChessPieceType.KING -> "♔"
            ChessPieceType.QUEEN -> "♕"
            ChessPieceType.ROOK -> "♖"
            ChessPieceType.BISHOP -> "♗"
            ChessPieceType.KNIGHT -> "♘"
            ChessPieceType.PAWN -> "♙"
        }

    ChessSide.BLACK ->
        when (type) {
            ChessPieceType.KING -> "♚"
            ChessPieceType.QUEEN -> "♛"
            ChessPieceType.ROOK -> "♜"
            ChessPieceType.BISHOP -> "♝"
            ChessPieceType.KNIGHT -> "♞"
            ChessPieceType.PAWN -> "♟"
        }
}

private const val BOARD_WIDTH = 8
private const val BOARD_INDEX = BOARD_WIDTH - 1
private const val NO_SQUARE_SELECTED = -1
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
private const val LOW_TIME_SECONDS = 30
private const val CLOCK_TICK_MILLIS = 1_000L
private val MAX_BOARD_WIDTH = 480.dp
private const val PIECE_SCALE = 0.7f
