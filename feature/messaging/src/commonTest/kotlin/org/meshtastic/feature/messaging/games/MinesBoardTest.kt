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

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinesBoardTest {
    @Test
    fun `board requires exactly eight distinct in-range cells`() {
        assertFailsWith<IllegalArgumentException> { MinesBoard(emptySet()) }
        assertFailsWith<IllegalArgumentException> { MinesBoard((0 until MINES_COUNT - 1).toSet()) }
        assertFailsWith<IllegalArgumentException> { MinesBoard((0..MINES_COUNT).toSet()) }
        assertFailsWith<IllegalArgumentException> { MinesBoard(setOf(-1) + (0 until MINES_COUNT - 1)) }
        assertFailsWith<IllegalArgumentException> { MinesBoard(setOf(MINES_CELLS) + (0 until MINES_COUNT - 1)) }
    }

    @Test
    fun `seeded random boards are reproducible and contain eight mines`() {
        val firstBoard = MinesBoard.random(Random(RANDOM_SEED))
        val secondBoard = MinesBoard.random(Random(RANDOM_SEED))

        assertEquals(firstBoard, secondBoard)
        assertEquals(MINES_COUNT, firstBoard.mines.size)
        assertTrue(firstBoard.mines.all { it in 0 until MINES_CELLS })
    }

    @Test
    fun `neighbor counts cover every cell including edges and corners`() {
        val board = MinesBoard(setOf(0, 1, 2, 6, 8, 12, 13, 14))

        for (cell in 0 until MINES_CELLS) {
            val row = cell / MINES_SIZE
            val column = cell % MINES_SIZE
            val expected =
                if (cell in board.mines) {
                    MINED_CELL_VALUE
                } else {
                    board.mines.count { mine ->
                        val rowDistance = abs(mine / MINES_SIZE - row)
                        val columnDistance = abs(mine % MINES_SIZE - column)
                        rowDistance <= MAX_NEIGHBOR_DISTANCE && columnDistance <= MAX_NEIGHBOR_DISTANCE
                    }
                }
            assertEquals(expected, board.valueAt(cell), "cell $cell")
        }
        assertEquals(MAX_ADJACENT_MINES, board.valueAt(7))
        assertEquals(MINED_CELL_VALUE, board.valueAt(0))
    }

    @Test
    fun `value lookup rejects cells outside the board`() {
        val board = MinesBoard((0 until MINES_COUNT).toSet())

        assertFailsWith<IllegalArgumentException> { board.valueAt(-1) }
        assertFailsWith<IllegalArgumentException> { board.valueAt(MINES_CELLS) }
    }

    @Test
    fun `coordinates map row major cells to letters and one based rows`() {
        assertEquals("A1", minesCoordinate(0))
        assertEquals("F1", minesCoordinate(MINES_SIZE - 1))
        assertEquals("A2", minesCoordinate(MINES_SIZE))
        assertEquals("F6", minesCoordinate(MINES_CELLS - 1))
        assertFailsWith<IllegalArgumentException> { minesCoordinate(-1) }
        assertFailsWith<IllegalArgumentException> { minesCoordinate(MINES_CELLS) }
    }

    @Test
    fun `match starts with host active and updates scores lives and turns`() {
        val initial = MinesMatch()
        val afterSafeReveal = initial.reveal(0, SAFE_VALUE)
        val afterMineReveal = afterSafeReveal.reveal(1, MINED_CELL_VALUE)

        assertEquals(PLAYER_ZERO, initial.activePlayer)
        assertFalse(initial.finished)
        assertNull(initial.winner)
        assertEquals(PLAYER_ONE, afterSafeReveal.activePlayer)
        assertEquals(listOf(1, 0), afterSafeReveal.scores)
        assertEquals(listOf(MINES_LIVES, MINES_LIVES), afterSafeReveal.lives)
        assertEquals(PLAYER_ZERO, afterMineReveal.activePlayer)
        assertEquals(listOf(1, 0), afterMineReveal.scores)
        assertEquals(listOf(MINES_LIVES, MINES_LIVES - 1), afterMineReveal.lives)
        assertEquals(2, afterMineReveal.turn)
    }

    @Test
    fun `three mistakes finish the game and higher scoring opponent wins`() {
        val match =
            listOf(
                MINED_CELL_VALUE,
                SAFE_VALUE,
                MINED_CELL_VALUE,
                SAFE_VALUE,
                MINED_CELL_VALUE,
            )
                .foldIndexed(MinesMatch()) { index, state, value -> state.reveal(index, value) }

        assertTrue(match.finished)
        assertEquals(listOf(0, 2), match.scores)
        assertEquals(listOf(0, MINES_LIVES), match.lives)
        assertEquals(PLAYER_ONE, match.winner)
        assertFailsWith<IllegalArgumentException> { match.reveal(5, SAFE_VALUE) }
    }

    @Test
    fun `all safe cells finish a tied match as a draw`() {
        val match = (0 until SAFE_CELL_COUNT).fold(MinesMatch()) { state, cell -> state.reveal(cell, SAFE_VALUE) }

        assertTrue(match.finished)
        assertEquals(SAFE_CELL_COUNT, match.scores.sum())
        assertEquals(listOf(SAFE_CELL_COUNT / PLAYER_COUNT, SAFE_CELL_COUNT / PLAYER_COUNT), match.scores)
        assertNull(match.winner)
    }

    @Test
    fun `equal scores at first elimination award opponent victory and prevent another reveal`() {
        val terminalTurn = PLAYER_COUNT * MINES_LIVES - 1
        val match =
            (0 until terminalTurn).fold(MinesMatch()) { state, cell ->
                state.reveal(cell, MINED_CELL_VALUE)
            }

        assertTrue(match.finished)
        assertEquals(listOf(0, 0), match.scores)
        assertEquals(listOf(0, 1), match.lives)
        assertEquals(PLAYER_ONE, match.winner)
        assertFailsWith<IllegalArgumentException> { match.reveal(terminalTurn, MINED_CELL_VALUE) }
    }

    @Test
    fun `elimination overrides a higher score in reconstructed matches for either player`() {
        for (player in 0 until PLAYER_COUNT) {
            val turn = PLAYER_COUNT * MINES_LIVES + player
            val safeCount = turn - (MINES_LIVES - 1)
            val match =
                MinesMatch(
                    revealed = (0 until turn).associateWith { if (it < safeCount) SAFE_VALUE else MINED_CELL_VALUE },
                    turn = turn,
                    scores = List(PLAYER_COUNT) { if (it == player) safeCount else 0 },
                    lives = List(PLAYER_COUNT) { if (it == player) 1 else MINES_LIVES },
                )
            val eliminated = match.reveal(turn, MINED_CELL_VALUE)

            assertFalse(match.finished)
            assertNull(match.winner)
            assertTrue(eliminated.finished)
            assertTrue(eliminated.scores[player] > eliminated.scores[1 - player])
            assertEquals(1 - player, eliminated.winner)
        }
    }

    @Test
    fun `reveal rejects invalid repeated and impossible reveals`() {
        val initial = MinesMatch()
        val afterFirst = initial.reveal(0, SAFE_VALUE)

        assertFailsWith<IllegalArgumentException> { initial.reveal(-1, SAFE_VALUE) }
        assertFailsWith<IllegalArgumentException> { initial.reveal(MINES_CELLS, SAFE_VALUE) }
        assertFailsWith<IllegalArgumentException> { initial.reveal(0, MAX_ADJACENT_MINES + 1) }
        assertFailsWith<IllegalArgumentException> { afterFirst.reveal(0, SAFE_VALUE) }
        assertFailsWith<IllegalArgumentException> { initial.reveal(0, MINED_CELL_VALUE - 1) }
        val terminal = (0 until SAFE_CELL_COUNT).fold(MinesMatch()) { state, cell -> state.reveal(cell, SAFE_VALUE) }
        assertFailsWith<IllegalArgumentException> { terminal.reveal(SAFE_CELL_COUNT, SAFE_VALUE) }
        assertFailsWith<IllegalArgumentException> {
            MinesMatch(
                revealed = (0..MINES_COUNT).associateWith { MINED_CELL_VALUE },
                turn = MINES_COUNT + 1,
                lives = listOf(0, 0),
            )
        }
    }
}

private const val PLAYER_COUNT = 2
private const val PLAYER_ZERO = 0
private const val PLAYER_ONE = 1
private const val RANDOM_SEED = 42
private const val SAFE_VALUE = 0
private const val MINED_CELL_VALUE = -1
private const val MAX_NEIGHBOR_DISTANCE = 1
private const val MAX_ADJACENT_MINES = 8
private const val SAFE_CELL_COUNT = MINES_CELLS - MINES_COUNT
