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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FleetProtocolTest {
    @Test
    fun `encodes first attack and combined result with next attack in one message`() {
        val first = FleetCommand.Turn(GAME_ID, turn = 1, result = null, attack = 37)
        val response =
            FleetCommand.Turn(
                GAME_ID,
                turn = 2,
                result = FleetShotResult(hit = true, sunkShipLength = 3),
                attack = 81,
            )

        assertEquals("FLEET/1|T|$GAME_ID|1|-|37", FleetProtocol.encode(first))
        assertEquals("FLEET/1|T|$GAME_ID|2|S3|81", FleetProtocol.encode(response))
        assertEquals(first, FleetProtocol.decode(FleetProtocol.encode(first)))
        assertEquals(response, FleetProtocol.decode(FleetProtocol.encode(response)))
    }

    @Test
    fun `round trips invitations terminal result and resignation`() {
        val commands =
            listOf(
                FleetCommand.Invite(GAME_ID),
                FleetCommand.Accept(GAME_ID),
                FleetCommand.Decline(GAME_ID),
                FleetCommand.Turn(GAME_ID, turn = 4, result = FleetShotResult(hit = false), attack = 19),
                FleetCommand.Turn(
                    GAME_ID,
                    turn = 5,
                    result = FleetShotResult(hit = true, sunkShipLength = 2, allShipsSunk = true),
                    attack = null,
                ),
                FleetCommand.Resign(GAME_ID),
            )

        commands.forEach { command -> assertEquals(command, FleetProtocol.decode(FleetProtocol.encode(command))) }
    }

    @Test
    fun `ignores regular messages and malformed commands`() {
        assertNull(FleetProtocol.decode("¿Quieres jugar a Hundir la flota?"))
        assertNull(FleetProtocol.decode("FLEET/2|I|$GAME_ID"))
        assertNull(FleetProtocol.decode("FLEET/1|T|$GAME_ID|0|-|10"))
        assertNull(FleetProtocol.decode("FLEET/1|T|$GAME_ID|1|-|100"))
        assertNull(FleetProtocol.decode("FLEET/1|T|$GAME_ID|1|S1|5"))
        assertNull(FleetProtocol.decode("FLEET/1|T|$GAME_ID|1|W|5"))
        assertNull(FleetProtocol.decode("FLEET/1|I|$GAME_ID|unexpected"))
    }

    private companion object {
        const val GAME_ID = "0123456789abcdef"
    }
}
