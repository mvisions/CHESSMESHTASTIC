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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChessProtocolTest {
    @Test
    fun roundTripsAllCommands() {
        val commands =
            listOf(
                ChessCommand.Invite(GAME_ID),
                ChessCommand.Accept(GAME_ID),
                ChessCommand.Decline(GAME_ID),
                ChessCommand.Move(GAME_ID, 7, ChessMove.fromUci("e7e8q")!!),
                ChessCommand.Resign(GAME_ID),
                ChessCommand.DrawOffer(GAME_ID),
                ChessCommand.DrawAccept(GAME_ID),
                ChessCommand.DrawDecline(GAME_ID),
                ChessCommand.DrawClaim(GAME_ID, ClaimableDraw.THREEFOLD_REPETITION),
                ChessCommand.DrawClaim(GAME_ID, ClaimableDraw.FIFTY_MOVE_RULE),
                ChessCommand.Timeout(GAME_ID, 12, ChessSide.BLACK),
            )

        commands.forEach { command -> assertEquals(command, ChessProtocol.decode(ChessProtocol.encode(command))) }
    }

    @Test
    fun ignoresNormalMessagesAndRejectsMalformedCommands() {
        assertNull(ChessProtocol.decode("¿Aceptas jugar al ajedrez?"))
        assertNull(ChessProtocol.decode("CHESS/2|I|$GAME_ID"))
        assertNull(ChessProtocol.decode("CHESS/1|I|bad"))
        assertNull(ChessProtocol.decode("CHESS/1|M|$GAME_ID|0|e2e4"))
        assertNull(ChessProtocol.decode("CHESS/1|M|$GAME_ID|1|e2e9"))
        assertNull(ChessProtocol.decode("CHESS/1|T|$GAME_ID|-1|w"))
        assertNull(ChessProtocol.decode("CHESS/1|I|$GAME_ID|unexpected"))
    }

    private companion object {
        const val GAME_ID = "0123456789abcdef"
    }
}
