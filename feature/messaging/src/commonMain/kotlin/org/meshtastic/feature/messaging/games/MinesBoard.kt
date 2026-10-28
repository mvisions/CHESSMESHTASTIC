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

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class MinesBoard(val mines: Set<Int>) {
    init {
        require(mines.size == MINES_COUNT)
        require(mines.all { it in 0 until MINES_CELLS })
    }

    fun valueAt(cell: Int): Int {
        require(cell in 0 until MINES_CELLS)
        if (cell in mines) return MINED_CELL_VALUE
        val row = cell / MINES_SIZE
        val column = cell % MINES_SIZE
        return mines.count { mine ->
            val rowDistance = (mine / MINES_SIZE - row).absoluteValue()
            val columnDistance = (mine % MINES_SIZE - column).absoluteValue()
            rowDistance <= 1 && columnDistance <= 1
        }
    }

    companion object {
        fun random(random: Random = Random.Default): MinesBoard =
            MinesBoard((0 until MINES_CELLS).shuffled(random).take(MINES_COUNT).toSet())
    }
}

@Serializable
data class MinesMatch(
    val revealed: Map<Int, Int> = emptyMap(),
    val turn: Int = 0,
    val scores: List<Int> = listOf(0, 0),
    val lives: List<Int> = listOf(MINES_LIVES, MINES_LIVES),
) {
    init {
        require(turn in 0..MINES_CELLS)
        require(revealed.size == turn)
        require(revealed.keys.all { it in 0 until MINES_CELLS })
        require(revealed.values.all { it in MINED_CELL_VALUE..MAX_ADJACENT_MINES })
        require(scores.size == PLAYER_COUNT && scores.all { it >= 0 })
        require(lives.size == PLAYER_COUNT && lives.all { it in 0..MINES_LIVES })
        require(scores.sum() == revealed.values.count { it != MINED_CELL_VALUE })
        require(lives.sum() == PLAYER_COUNT * MINES_LIVES - revealed.values.count { it == MINED_CELL_VALUE })
        require(scores.sum() <= SAFE_CELL_COUNT && revealed.values.count { it == MINED_CELL_VALUE } <= MINES_COUNT)
    }

    val activePlayer: Int
        get() = turn % PLAYER_COUNT

    val finished: Boolean
        get() = lives.any { it == 0 } || scores.sum() == SAFE_CELL_COUNT

    val winner: Int?
        get() =
            when {
                !finished -> null
                lives[PLAYER_ZERO] == 0 -> PLAYER_ONE
                lives[PLAYER_ONE] == 0 -> PLAYER_ZERO
                scores[PLAYER_ZERO] == scores[PLAYER_ONE] -> null
                scores[PLAYER_ZERO] > scores[PLAYER_ONE] -> PLAYER_ZERO
                else -> PLAYER_ONE
            }

    fun reveal(cell: Int, value: Int): MinesMatch {
        require(!finished)
        require(cell in 0 until MINES_CELLS)
        require(cell !in revealed)
        require(value in MINED_CELL_VALUE..MAX_ADJACENT_MINES)
        require(value == MINED_CELL_VALUE || scores.sum() < SAFE_CELL_COUNT)
        require(value != MINED_CELL_VALUE || revealed.values.count { it == MINED_CELL_VALUE } < MINES_COUNT)
        val nextScores = scores.toMutableList()
        val nextLives = lives.toMutableList()
        if (value == MINED_CELL_VALUE) {
            nextLives[activePlayer]--
        } else {
            nextScores[activePlayer]++
        }
        return copy(revealed = revealed + (cell to value), turn = turn + 1, scores = nextScores, lives = nextLives)
    }
}

fun minesCoordinate(cell: Int): String {
    require(cell in 0 until MINES_CELLS)
    val column = 'A' + cell % MINES_SIZE
    val row = cell / MINES_SIZE + FIRST_ROW_NUMBER
    return "$column$row"
}

private fun Int.absoluteValue(): Int = if (this < 0) -this else this

private const val PLAYER_COUNT = 2
private const val PLAYER_ZERO = 0
private const val PLAYER_ONE = 1
private const val FIRST_ROW_NUMBER = 1
private const val MINED_CELL_VALUE = -1
private const val MAX_ADJACENT_MINES = 8
const val MINES_SIZE = 6
const val MINES_CELLS = MINES_SIZE * MINES_SIZE
const val MINES_COUNT = 8
const val MINES_LIVES = 3

private const val SAFE_CELL_COUNT = MINES_CELLS - MINES_COUNT
