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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleetBoardTest {
    @Test
    fun `random board places the standard fleet without overlaps`() {
        val board = FleetBoard.random(Random(42))

        assertEquals(FLEET_SHIP_LENGTHS, board.ships.map(FleetShip::length))
        assertEquals(FLEET_SHIP_LENGTHS.sum(), board.occupiedCells.size)
        assertTrue(board.ships.all { ship -> ship.cells.size == ship.length })
        assertTrue(board.occupiedCells.all { it in 0 until FLEET_BOARD_CELLS })
    }

    @Test
    fun `shot result distinguishes water hits sunk ships and a defeated fleet`() {
        val board =
            FleetBoard(
                ships =
                listOf(
                    FleetShip(2, setOf(0, 1)),
                    FleetShip(1, setOf(2)),
                ),
            )

        assertFalse(board.receiveShot(8, emptySet()).hit)
        assertEquals(FleetShotResult(hit = true), board.receiveShot(0, emptySet()))
        assertEquals(FleetShotResult(hit = true, sunkShipLength = 2), board.receiveShot(1, setOf(0)))
        assertEquals(
            FleetShotResult(hit = true, sunkShipLength = 1, allShipsSunk = true),
            board.receiveShot(2, setOf(0, 1)),
        )
    }
}
