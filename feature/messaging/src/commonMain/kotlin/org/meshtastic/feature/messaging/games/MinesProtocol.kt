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

sealed interface MinesCommand {
    val gameId: String

    data class Invite(override val gameId: String) : MinesCommand

    data class Accept(override val gameId: String) : MinesCommand

    data class Decline(override val gameId: String) : MinesCommand

    data class Resign(override val gameId: String) : MinesCommand

    /** Requests reveals after [turn], the guest's last applied match turn (zero before any reveal). */
    data class Sync(override val gameId: String, val turn: Int) : MinesCommand

    data class Move(override val gameId: String, val turn: Int, val cell: Int) : MinesCommand

    data class Reveal(override val gameId: String, val turn: Int, val cell: Int, val value: Int) : MinesCommand
}

object MinesProtocol {
    private const val PREFIX = "MINES/1"

    fun encode(command: MinesCommand): String {
        require(command.gameId.isValidMinesGameId())
        val encoded =
            when (command) {
                is MinesCommand.Invite -> "$PREFIX|I|${command.gameId}"

                is MinesCommand.Accept -> "$PREFIX|A|${command.gameId}"

                is MinesCommand.Decline -> "$PREFIX|D|${command.gameId}"

                is MinesCommand.Resign -> "$PREFIX|R|${command.gameId}"

                is MinesCommand.Sync -> {
                    require(command.turn in 0..MINES_CELLS)
                    "$PREFIX|S|${command.gameId}|${command.turn}"
                }

                is MinesCommand.Move -> {
                    require(command.turn in 1..MINES_CELLS)
                    require(command.cell in 0 until MINES_CELLS)
                    "$PREFIX|M|${command.gameId}|${command.turn}|${command.cell}"
                }

                is MinesCommand.Reveal -> {
                    require(command.turn in 1..MINES_CELLS)
                    require(command.cell in 0 until MINES_CELLS)
                    require(command.value in MINED_CELL_VALUE..MAX_ADJACENT_MINES)
                    "$PREFIX|V|${command.gameId}|${command.turn}|${command.cell}|${command.value}"
                }
            }
        require(encoded.length < MAX_PACKET_BYTES)
        return encoded
    }

    @Suppress("ReturnCount")
    fun decode(message: String): MinesCommand? {
        if (!message.startsWith("$PREFIX|") || message.length >= MAX_PACKET_BYTES || !message.isAscii()) return null
        val fields = message.split('|')
        if (fields.size < SIMPLE_COMMAND_FIELD_COUNT || fields[PREFIX_FIELD] != PREFIX) return null
        val gameId = fields[GAME_ID_FIELD]
        if (!gameId.isValidMinesGameId()) return null
        return when (fields[COMMAND_TYPE_FIELD]) {
            "I" -> if (fields.size == SIMPLE_COMMAND_FIELD_COUNT) MinesCommand.Invite(gameId) else null
            "A" -> if (fields.size == SIMPLE_COMMAND_FIELD_COUNT) MinesCommand.Accept(gameId) else null
            "D" -> if (fields.size == SIMPLE_COMMAND_FIELD_COUNT) MinesCommand.Decline(gameId) else null
            "R" -> if (fields.size == SIMPLE_COMMAND_FIELD_COUNT) MinesCommand.Resign(gameId) else null
            "S" -> decodeSync(fields, gameId)
            "M" -> decodeMove(fields, gameId)
            "V" -> decodeReveal(fields, gameId)
            else -> null
        }
    }

    private fun decodeSync(fields: List<String>, gameId: String): MinesCommand.Sync? =
        if (fields.size == SYNC_FIELD_COUNT) {
            fields[TURN_FIELD]
                .toIntOrNull()
                ?.takeIf { it in 0..MINES_CELLS && it.toString() == fields[TURN_FIELD] }
                ?.let { MinesCommand.Sync(gameId, it) }
        } else {
            null
        }

    @Suppress("ReturnCount")
    private fun decodeMove(fields: List<String>, gameId: String): MinesCommand.Move? {
        if (fields.size != MOVE_FIELD_COUNT) return null
        val turn = fields[TURN_FIELD].toIntOrNull()?.takeIf { it in 1..MINES_CELLS } ?: return null
        val cell = fields[CELL_FIELD].toIntOrNull()?.takeIf { it in 0 until MINES_CELLS } ?: return null
        return MinesCommand.Move(gameId, turn, cell)
    }

    @Suppress("ReturnCount")
    private fun decodeReveal(fields: List<String>, gameId: String): MinesCommand.Reveal? {
        if (fields.size != REVEAL_FIELD_COUNT) return null
        val turn = fields[TURN_FIELD].toIntOrNull()?.takeIf { it in 1..MINES_CELLS } ?: return null
        val cell = fields[CELL_FIELD].toIntOrNull()?.takeIf { it in 0 until MINES_CELLS } ?: return null
        val value =
            fields[VALUE_FIELD].toIntOrNull()?.takeIf { it in MINED_CELL_VALUE..MAX_ADJACENT_MINES } ?: return null
        return MinesCommand.Reveal(gameId, turn, cell, value)
    }

    private fun String.isAscii(): Boolean = all { it.code <= ASCII_MAX }

    private fun String.isValidMinesGameId(): Boolean =
        length in MIN_GAME_ID_LENGTH..MAX_GAME_ID_LENGTH && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

private const val MAX_PACKET_BYTES = 200
private const val ASCII_MAX = 0x7f
private const val MIN_GAME_ID_LENGTH = 8
private const val MAX_GAME_ID_LENGTH = 32
private const val MINED_CELL_VALUE = -1
private const val MAX_ADJACENT_MINES = 8
private const val SIMPLE_COMMAND_FIELD_COUNT = 3
private const val SYNC_FIELD_COUNT = 4
private const val MOVE_FIELD_COUNT = 5
private const val REVEAL_FIELD_COUNT = 6
private const val PREFIX_FIELD = 0
private const val COMMAND_TYPE_FIELD = 1
private const val GAME_ID_FIELD = 2
private const val TURN_FIELD = 3
private const val CELL_FIELD = 4
private const val VALUE_FIELD = 5
