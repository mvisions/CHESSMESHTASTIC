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

import dev.mokkery.MockMode
import dev.mokkery.mock
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChessGameCoordinatorTest {
    private val coordinator =
        ChessGameCoordinator(
            packetRepository = mock<PacketRepository>(MockMode.autofill),
            nodeRepository = mock<NodeRepository>(MockMode.autofill),
            sendMessageUseCase = mock<SendMessageUseCase>(MockMode.autofill),
        )

    @Test
    fun `draw claim is ignored when sender is not the side to move`() {
        val game =
            playingGame(
                position = position("4k3/8/8/8/8/8/8/4K2R w - - 100 51"),
                localSide = ChessSide.WHITE,
            )

        coordinator.processDrawClaim(
            game,
            ChessCommand.DrawClaim(game.gameId, ClaimableDraw.FIFTY_MOVE_RULE),
            ChessSide.BLACK,
        )

        assertNull(coordinator.games.value[game.gameId])
    }

    @Test
    fun `draw claim from side to move is applied`() {
        val game =
            playingGame(
                position = position("4k3/8/8/8/8/8/8/4K2R w - - 100 51"),
                localSide = ChessSide.WHITE,
            )

        coordinator.processDrawClaim(
            game,
            ChessCommand.DrawClaim(game.gameId, ClaimableDraw.FIFTY_MOVE_RULE),
            ChessSide.WHITE,
        )

        val result = assertNotNull(coordinator.games.value[game.gameId])
        assertEquals(ChessGamePhase.FINISHED, result.phase)
        assertEquals(ChessOutcome.DRAW_FIFTY_MOVE_RULE, result.position.outcome)
    }

    @Test
    fun `move received at deadline ends the game by timeout`() {
        val deadline = 1_000L
        val game =
            playingGame(
                position = ChessPosition.initial().play(assertNotNull(ChessMove.fromUci("e2e4")))!!,
                localSide = ChessSide.WHITE,
                ply = 1,
                deadline = deadline,
            )

        coordinator.processMove(
            game,
            ChessCommand.Move(game.gameId, ply = 2, move = assertNotNull(ChessMove.fromUci("e7e5"))),
            message(fromLocal = false, receivedTime = deadline),
        )

        val result = assertNotNull(coordinator.games.value[game.gameId])
        assertEquals(ChessGamePhase.FINISHED, result.phase)
        assertEquals(ChessGameResult(ChessFinishReason.TIMEOUT, ChessSide.BLACK), result.result)
        assertEquals(game.position, result.position)
    }

    @Test
    fun `move received before deadline is applied`() {
        val deadline = 1_000L
        val game =
            playingGame(
                position = ChessPosition.initial().play(assertNotNull(ChessMove.fromUci("e2e4")))!!,
                localSide = ChessSide.WHITE,
                ply = 1,
                deadline = deadline,
            )

        coordinator.processMove(
            game,
            ChessCommand.Move(game.gameId, ply = 2, move = assertNotNull(ChessMove.fromUci("e7e5"))),
            message(fromLocal = false, receivedTime = deadline - 1),
        )

        val result = assertNotNull(coordinator.games.value[game.gameId])
        assertEquals(ChessGamePhase.PLAYING, result.phase)
        assertEquals(2, result.ply)
        assertEquals(ChessSide.WHITE, result.position.sideToMove)
    }

    private fun playingGame(
        position: ChessPosition = ChessPosition.initial(),
        localSide: ChessSide,
        ply: Int = 0,
        deadline: Long = 60_000,
    ): ChessGameSession = ChessGameSession(
        gameId = "12345678",
        contactKey = "!12345678",
        opponentNodeId = "!87654321",
        opponentName = "Opponent",
        localSide = localSide,
        position = position,
        ply = ply,
        phase = ChessGamePhase.PLAYING,
        turnDeadlineMillis = deadline,
    )

    private fun message(fromLocal: Boolean, receivedTime: Long): Message = Message(
        uuid = 1,
        receivedTime = receivedTime,
        node = Node(num = 1),
        text = "",
        fromLocal = fromLocal,
        time = "",
        read = true,
        status = null,
        routingError = 0,
        packetId = 1,
        emojis = emptyList(),
        snr = null,
        rssi = null,
        hopsAway = 0,
        replyId = null,
    )

    private fun position(fen: String): ChessPosition = assertNotNull(ChessPosition.fromFen(fen), "Invalid FEN: $fen")
}
