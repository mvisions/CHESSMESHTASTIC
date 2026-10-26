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

sealed interface ChessCommand {
    val gameId: String

    data class Invite(override val gameId: String) : ChessCommand

    data class Accept(override val gameId: String) : ChessCommand

    data class Decline(override val gameId: String) : ChessCommand

    data class Move(override val gameId: String, val ply: Int, val move: ChessMove) : ChessCommand

    data class Resign(override val gameId: String) : ChessCommand

    data class DrawOffer(override val gameId: String) : ChessCommand

    data class DrawAccept(override val gameId: String) : ChessCommand

    data class DrawDecline(override val gameId: String) : ChessCommand

    data class DrawClaim(override val gameId: String, val outcome: ClaimableDraw) : ChessCommand

    data class Timeout(override val gameId: String, val ply: Int, val loser: ChessSide) : ChessCommand
}

enum class ClaimableDraw {
    THREEFOLD_REPETITION,
    FIFTY_MOVE_RULE,
}

/** A compact, versioned plaintext protocol carried only inside encrypted direct messages. */
object ChessProtocol {
    private const val PREFIX = "CHESS/1"
    private const val DELIMITER = "|"

    fun encode(command: ChessCommand): String {
        require(command.gameId.isValidGameId())
        return when (command) {
            is ChessCommand.Invite -> "$PREFIX|I|${command.gameId}"

            is ChessCommand.Accept -> "$PREFIX|A|${command.gameId}"

            is ChessCommand.Decline -> "$PREFIX|D|${command.gameId}"

            is ChessCommand.Move ->
                "$PREFIX|M|${command.gameId}|${command.ply}|${command.move.toUci()}"
                    .also {
                        require(command.ply > 0)
                    }

            is ChessCommand.Resign -> "$PREFIX|R|${command.gameId}"

            is ChessCommand.DrawOffer -> "$PREFIX|O|${command.gameId}"

            is ChessCommand.DrawAccept -> "$PREFIX|Y|${command.gameId}"

            is ChessCommand.DrawDecline -> "$PREFIX|N|${command.gameId}"

            is ChessCommand.DrawClaim ->
                "$PREFIX|C|${command.gameId}|${if (command.outcome == ClaimableDraw.THREEFOLD_REPETITION) "3" else "50"}"

            is ChessCommand.Timeout ->
                "$PREFIX|T|${command.gameId}|${command.ply}|${command.loser.wireCode}"
                    .also {
                        require(command.ply >= 0)
                    }
        }
    }

    @Suppress("ReturnCount")
    fun decode(message: String): ChessCommand? {
        if (!message.startsWith("$PREFIX|")) return null
        val parts = message.split(DELIMITER)
        if (parts.size < MIN_COMMAND_FIELDS || parts[VERSION_FIELD] != PREFIX) return null
        val gameId = parts[GAME_ID_FIELD].takeIf { it.isValidGameId() } ?: return null
        return when (parts[COMMAND_TYPE_FIELD]) {
            "I" -> if (parts.size == SIMPLE_COMMAND_FIELDS) ChessCommand.Invite(gameId) else null

            "A" -> if (parts.size == SIMPLE_COMMAND_FIELDS) ChessCommand.Accept(gameId) else null

            "D" -> if (parts.size == SIMPLE_COMMAND_FIELDS) ChessCommand.Decline(gameId) else null

            "M" -> {
                if (parts.size != MOVE_COMMAND_FIELDS) return null
                val ply = parts[PLY_FIELD].toIntOrNull()?.takeIf { it > 0 } ?: return null
                val move = ChessMove.fromUci(parts[MOVE_FIELD]) ?: return null
                ChessCommand.Move(gameId, ply, move)
            }

            "R" -> if (parts.size == SIMPLE_COMMAND_FIELDS) ChessCommand.Resign(gameId) else null

            "O" -> if (parts.size == SIMPLE_COMMAND_FIELDS) ChessCommand.DrawOffer(gameId) else null

            "Y" -> if (parts.size == SIMPLE_COMMAND_FIELDS) ChessCommand.DrawAccept(gameId) else null

            "N" -> if (parts.size == SIMPLE_COMMAND_FIELDS) ChessCommand.DrawDecline(gameId) else null

            "C" -> {
                if (parts.size != CLAIM_COMMAND_FIELDS) return null
                val outcome =
                    when (parts[PLY_FIELD]) {
                        "3" -> ClaimableDraw.THREEFOLD_REPETITION
                        "50" -> ClaimableDraw.FIFTY_MOVE_RULE
                        else -> return null
                    }
                ChessCommand.DrawClaim(gameId, outcome)
            }

            "T" -> {
                if (parts.size != TIMEOUT_COMMAND_FIELDS) return null
                val ply = parts[PLY_FIELD].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val loser = parseSide(parts[MOVE_FIELD]) ?: return null
                ChessCommand.Timeout(gameId, ply, loser)
            }

            else -> null
        }
    }

    private fun String.isValidGameId(): Boolean =
        length in MIN_GAME_ID_LENGTH..MAX_GAME_ID_LENGTH && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private val ChessSide.wireCode: String
        get() = if (this == ChessSide.WHITE) "w" else "b"

    private fun parseSide(code: String): ChessSide? = when (code) {
        "w" -> ChessSide.WHITE
        "b" -> ChessSide.BLACK
        else -> null
    }
}

private const val MIN_GAME_ID_LENGTH = 8
private const val MAX_GAME_ID_LENGTH = 32
private const val MIN_COMMAND_FIELDS = 3
private const val SIMPLE_COMMAND_FIELDS = 3
private const val MOVE_COMMAND_FIELDS = 5
private const val CLAIM_COMMAND_FIELDS = 4
private const val TIMEOUT_COMMAND_FIELDS = 5
private const val VERSION_FIELD = 0
private const val COMMAND_TYPE_FIELD = 1
private const val GAME_ID_FIELD = 2
private const val PLY_FIELD = 3
private const val MOVE_FIELD = 4
