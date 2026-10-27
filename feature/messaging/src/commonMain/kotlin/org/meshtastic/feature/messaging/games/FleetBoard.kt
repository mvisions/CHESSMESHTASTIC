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

data class FleetShip(val length: Int, val cells: Set<Int>)

data class FleetShotResult(val hit: Boolean, val sunkShipLength: Int? = null, val allShipsSunk: Boolean = false)

data class FleetBoard(val ships: List<FleetShip>) {
    val occupiedCells: Set<Int> = ships.flatMapTo(mutableSetOf()) { it.cells }

    fun receiveShot(cell: Int, previousHits: Set<Int>): FleetShotResult {
        require(cell in 0 until FLEET_BOARD_CELLS)
        val ship = ships.firstOrNull { cell in it.cells } ?: return FleetShotResult(hit = false)
        val hits = previousHits + cell
        val sunk = ship.cells.all { it in hits }
        return FleetShotResult(
            hit = true,
            sunkShipLength = ship.length.takeIf { sunk },
            allShipsSunk = sunk && ships.all { candidate -> candidate.cells.all { it in hits } },
        )
    }

    companion object {
        fun random(random: Random = Random.Default): FleetBoard {
            val ships = placeShips(0, emptySet(), emptyList(), random)
            return FleetBoard(ships)
        }

        @Suppress("ReturnCount")
        private fun placeShips(
            shipIndex: Int,
            occupied: Set<Int>,
            ships: List<FleetShip>,
            random: Random,
        ): List<FleetShip> {
            if (shipIndex == FLEET_SHIP_LENGTHS.size) return ships

            val length = FLEET_SHIP_LENGTHS[shipIndex]
            val candidates = buildList {
                for (row in 0 until FLEET_BOARD_SIZE) {
                    for (column in 0 until FLEET_BOARD_SIZE) {
                        if (column + length <= FLEET_BOARD_SIZE) {
                            add((0 until length).map { row * FLEET_BOARD_SIZE + column + it }.toSet())
                        }
                        if (row + length <= FLEET_BOARD_SIZE) {
                            add((0 until length).map { (row + it) * FLEET_BOARD_SIZE + column }.toSet())
                        }
                    }
                }
            }
                .shuffled(random)

            for (cells in candidates) {
                if (cells.any { it in occupied }) continue
                val nextShip = FleetShip(length, cells)
                val result = placeShips(shipIndex + 1, occupied + cells, ships + nextShip, random)
                if (result.isNotEmpty()) return result
            }
            return emptyList()
        }
    }
}

const val FLEET_BOARD_SIZE = 10
const val FLEET_BOARD_CELLS = FLEET_BOARD_SIZE * FLEET_BOARD_SIZE
val FLEET_SHIP_LENGTHS = listOf(5, 4, 3, 3, 2)
