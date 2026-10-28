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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinesProtocolTest {
    @Test
    fun `round trips invitations and game lifecycle commands`() {
        val commands =
            listOf(
                MinesCommand.Invite(GAME_ID),
                MinesCommand.Accept(GAME_ID),
                MinesCommand.Decline(GAME_ID),
                MinesCommand.Resign(GAME_ID),
            )

        commands.forEach { command -> assertEquals(command, MinesProtocol.decode(MinesProtocol.encode(command))) }
        assertEquals("MINES/1|I|$GAME_ID", MinesProtocol.encode(MinesCommand.Invite(GAME_ID)))
        assertEquals("MINES/1|A|$GAME_ID", MinesProtocol.encode(MinesCommand.Accept(GAME_ID)))
        assertEquals("MINES/1|D|$GAME_ID", MinesProtocol.encode(MinesCommand.Decline(GAME_ID)))
        assertEquals("MINES/1|R|$GAME_ID", MinesProtocol.encode(MinesCommand.Resign(GAME_ID)))
    }

    @Test
    fun `round trips moves and reveals including mine and maximum values`() {
        val commands =
            listOf(
                MinesCommand.Move(GAME_ID, FIRST_TURN, FIRST_CELL),
                MinesCommand.Move(GAME_ID, MINES_CELLS, MINES_CELLS - 1),
                MinesCommand.Reveal(GAME_ID, FIRST_TURN, FIRST_CELL, MINED_CELL_VALUE),
                MinesCommand.Reveal(GAME_ID, MINES_CELLS, MINES_CELLS - 1, MAX_ADJACENT_MINES),
            )

        commands.forEach { command -> assertEquals(command, MinesProtocol.decode(MinesProtocol.encode(command))) }
        assertEquals("MINES/1|M|$GAME_ID|1|0", MinesProtocol.encode(commands[0]))
        assertEquals("MINES/1|V|$GAME_ID|1|0|-1", MinesProtocol.encode(commands[2]))
    }

    @Test
    fun `accepts supported hex identifier lengths and casing`() {
        val identifiers = listOf(MIN_ID, GAME_ID, MAX_ID, UPPERCASE_ID)

        identifiers.forEach { id ->
            val command = MinesCommand.Invite(id)
            assertEquals(command, MinesProtocol.decode(MinesProtocol.encode(command)))
        }
    }

    @Test
    fun `sync round trips every last applied turn including zero without board data`() {
        for (turn in 0..MINES_CELLS) {
            val command = MinesCommand.Sync(MAX_ID, turn)
            val encoded = MinesProtocol.encode(command)

            assertEquals("MINES/1|S|$MAX_ID|$turn", encoded)
            assertEquals(command, MinesProtocol.decode(encoded))
            assertTrue(encoded.encodeToByteArray().size < MAX_PACKET_BYTES)
        }
    }

    @Test
    fun `sync encode rejects invalid ids and out of range turns`() {
        listOf("", "abc", INVALID_HEX_ID, TOO_LONG_ID, "$GAME_ID\n").forEach { id ->
            assertFailsWith<IllegalArgumentException> { MinesProtocol.encode(MinesCommand.Sync(id, 0)) }
        }
        listOf(Int.MIN_VALUE, -1, MINES_CELLS + 1, Int.MAX_VALUE).forEach { turn ->
            assertFailsWith<IllegalArgumentException> { MinesProtocol.encode(MinesCommand.Sync(GAME_ID, turn)) }
        }
    }

    @Test
    fun `sync decode rejects wrong fields malformed turns versions and ids`() {
        val malformed =
            listOf(
                "MINES/1|S|$GAME_ID",
                "MINES/1|S|$GAME_ID|",
                "MINES/1|S|$GAME_ID|0|",
                "MINES/1|S|$GAME_ID|0|1",
                "MINES/2|S|$GAME_ID|0",
                "MINES/1|s|$GAME_ID|0",
                "MINES/1|S|$INVALID_HEX_ID|0",
                "MINES/1|S|$TOO_LONG_ID|0",
                "MINES/1|S||0",
            )
        val invalidTurns = listOf("-1", "37", "2147483648", "one", "1.0", " 1", "1 ", "+1", "-0", "01", "\u0661")

        (malformed + invalidTurns.map { "MINES/1|S|$GAME_ID|$it" }).forEach { packet ->
            assertNull(MinesProtocol.decode(packet), packet)
        }
    }

    @Test
    fun `encode rejects invalid identifiers turns cells and values`() {
        assertFailsWith<IllegalArgumentException> { MinesProtocol.encode(MinesCommand.Invite("abc")) }
        assertFailsWith<IllegalArgumentException> { MinesProtocol.encode(MinesCommand.Invite(INVALID_HEX_ID)) }
        assertFailsWith<IllegalArgumentException> { MinesProtocol.encode(MinesCommand.Invite(TOO_LONG_ID)) }
        assertFailsWith<IllegalArgumentException> { MinesProtocol.encode(MinesCommand.Move(GAME_ID, 0, FIRST_CELL)) }
        assertFailsWith<IllegalArgumentException> {
            MinesProtocol.encode(MinesCommand.Move(GAME_ID, MINES_CELLS + 1, FIRST_CELL))
        }
        assertFailsWith<IllegalArgumentException> { MinesProtocol.encode(MinesCommand.Move(GAME_ID, FIRST_TURN, -1)) }
        assertFailsWith<IllegalArgumentException> {
            MinesProtocol.encode(MinesCommand.Move(GAME_ID, FIRST_TURN, MINES_CELLS))
        }
        assertFailsWith<IllegalArgumentException> {
            MinesProtocol.encode(MinesCommand.Reveal(GAME_ID, FIRST_TURN, FIRST_CELL, MINED_CELL_VALUE - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            MinesProtocol.encode(MinesCommand.Reveal(GAME_ID, FIRST_TURN, FIRST_CELL, MAX_ADJACENT_MINES + 1))
        }
    }

    @Test
    fun `decode ignores ordinary chat and malformed protocol packets`() {
        val malformed =
            listOf(
                "ordinary chat",
                "MINES/2|I|$GAME_ID",
                "MINES/1|X|$GAME_ID",
                "MINES/1|I|$GAME_ID|extra",
                "MINES/1|M|$GAME_ID|1",
                "MINES/1|M|$GAME_ID|1|0|extra",
                "MINES/1|V|$GAME_ID|1|0",
                "MINES/1|V|$GAME_ID|1|0|0|extra",
                "MINES/1|M|bad-id|1|0",
                "MINES/1|M|$INVALID_HEX_ID|1|0",
                "MINES/1|M|$GAME_ID|0|0",
                "MINES/1|M|$GAME_ID|37|0",
                "MINES/1|M|$GAME_ID|1|-1",
                "MINES/1|M|$GAME_ID|1|36",
                "MINES/1|V|$GAME_ID|1|0|-2",
                "MINES/1|V|$GAME_ID|1|0|9",
                "MINES/1|M|$GAME_ID|one|0",
            )

        malformed.forEach { packet -> assertNull(MinesProtocol.decode(packet), packet) }
    }

    @Test
    fun `rejects non ascii and packets at or above the size limit`() {
        assertNull(MinesProtocol.decode("MINES/1|I|$GAME_ID\u00e9"))
        assertNull(MinesProtocol.decode("MINES/1|I|$GAME_ID" + "x".repeat(MAX_PACKET_BYTES)))
        val encoded =
            MinesProtocol.encode(MinesCommand.Reveal(GAME_ID, MINES_CELLS, MINES_CELLS - 1, MAX_ADJACENT_MINES))
        assertTrue(encoded.encodeToByteArray().size < MAX_PACKET_BYTES)
    }

    private companion object {
        const val GAME_ID = "0123456789abcdef"
        const val MIN_ID = "01234567"
        const val MAX_ID = "0123456789abcdef0123456789abcdef"
        const val UPPERCASE_ID = "ABCDEF0123456789"
        const val INVALID_HEX_ID = "01234567xyzabcde"
        const val TOO_LONG_ID = "0123456789abcdef0123456789abcdef0"
        const val FIRST_TURN = 1
        const val FIRST_CELL = 0
        const val MINED_CELL_VALUE = -1
        const val MAX_ADJACENT_MINES = 8
        const val MAX_PACKET_BYTES = 200
    }
}
