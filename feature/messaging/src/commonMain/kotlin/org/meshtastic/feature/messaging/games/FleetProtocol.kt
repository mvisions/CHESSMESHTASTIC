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

sealed interface FleetCommand {
    val gameId: String

    data class Invite(override val gameId: String) : FleetCommand

    data class Accept(override val gameId: String) : FleetCommand

    data class Decline(override val gameId: String) : FleetCommand

    data class Turn(override val gameId: String, val turn: Int, val result: FleetShotResult?, val attack: Int?) :
        FleetCommand

    data class Resign(override val gameId: String) : FleetCommand

    data class Reaction(override val gameId: String, val sequence: Int, val reaction: FleetReaction) : FleetCommand
}

enum class FleetReaction {
    HIT,
    BRAVO,
    GG,
    NICE_MOVE,
}

object FleetProtocol {
    private const val PREFIX = "FLEET/1"

    fun encode(command: FleetCommand): String {
        require(command.gameId.isValidFleetGameId())
        return when (command) {
            is FleetCommand.Invite -> "$PREFIX|I|${command.gameId}"

            is FleetCommand.Accept -> "$PREFIX|A|${command.gameId}"

            is FleetCommand.Decline -> "$PREFIX|D|${command.gameId}"

            is FleetCommand.Resign -> "$PREFIX|R|${command.gameId}"

            is FleetCommand.Reaction -> {
                require(command.sequence > 0)
                "$PREFIX|E|${command.gameId}|${command.sequence}|${command.reaction.name}"
            }

            is FleetCommand.Turn -> {
                require(command.turn > 0)
                require(command.attack == null || command.attack in 0 until FLEET_BOARD_CELLS)
                require(command.result?.allShipsSunk != true || command.attack == null)
                require(command.attack != null || command.result?.allShipsSunk == true)
                "$PREFIX|T|${command.gameId}|${command.turn}|${command.result.toWireCode()}|${command.attack ?: "-"}"
            }
        }
    }

    @Suppress("ReturnCount")
    fun decode(message: String): FleetCommand? {
        if (!message.startsWith("$PREFIX|")) return null
        val parts = message.split('|')
        if (
            parts.size < MIN_COMMAND_FIELDS ||
            parts[PREFIX_FIELD] != PREFIX ||
            !parts[GAME_ID_FIELD].isValidFleetGameId()
        ) {
            return null
        }
        val gameId = parts[GAME_ID_FIELD]
        return when (parts[COMMAND_TYPE_FIELD]) {
            "I" -> if (parts.size == SIMPLE_COMMAND_FIELDS) FleetCommand.Invite(gameId) else null

            "A" -> if (parts.size == SIMPLE_COMMAND_FIELDS) FleetCommand.Accept(gameId) else null

            "D" -> if (parts.size == SIMPLE_COMMAND_FIELDS) FleetCommand.Decline(gameId) else null

            "R" -> if (parts.size == SIMPLE_COMMAND_FIELDS) FleetCommand.Resign(gameId) else null

            "T" -> decodeTurn(parts, gameId)

            "E" -> {
                if (parts.size != REACTION_FIELD_COUNT) return null
                val sequence = parts[TURN_FIELD].toIntOrNull()?.takeIf { it > 0 } ?: return null
                val reaction = FleetReaction.entries.firstOrNull { it.name == parts[RESULT_FIELD] } ?: return null
                FleetCommand.Reaction(gameId, sequence, reaction)
            }

            else -> null
        }
    }

    @Suppress("ReturnCount")
    private fun decodeTurn(parts: List<String>, gameId: String): FleetCommand.Turn? {
        if (parts.size != TURN_FIELD_COUNT) return null
        val turn = parts[TURN_FIELD].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val resultCode = parts[RESULT_FIELD]
        val result = if (resultCode == "-") null else resultCode.fromWireCode() ?: return null
        val attack =
            if (parts[ATTACK_FIELD] == "-") {
                null
            } else {
                parts[ATTACK_FIELD].toIntOrNull()?.takeIf { it in 0 until FLEET_BOARD_CELLS } ?: return null
            }
        if (result?.allShipsSunk == true && attack != null) return null
        if (attack == null && result?.allShipsSunk != true) return null
        return FleetCommand.Turn(gameId, turn, result, attack)
    }

    private fun FleetShotResult?.toWireCode(): String = when {
        this == null -> "-"
        allShipsSunk -> sunkShipLength?.let { "W$it" } ?: "W"
        sunkShipLength != null -> "S$sunkShipLength"
        hit -> "H"
        else -> "M"
    }

    private fun String.fromWireCode(): FleetShotResult? = when {
        this == "-" -> null

        this == "M" -> FleetShotResult(hit = false)

        this == "H" -> FleetShotResult(hit = true)

        startsWith("W") -> {
            val length = drop(1).takeIf(String::isNotEmpty)?.toIntOrNull()?.takeIf { it in FLEET_SHIP_LENGTHS }
            if (length == null && this != "W") return null
            FleetShotResult(hit = true, sunkShipLength = length, allShipsSunk = true)
        }

        startsWith("S") -> {
            val length = drop(1).toIntOrNull()?.takeIf { it in FLEET_SHIP_LENGTHS } ?: return null
            FleetShotResult(hit = true, sunkShipLength = length)
        }

        else -> null
    }

    private fun String.isValidFleetGameId(): Boolean =
        length in MIN_GAME_ID_LENGTH..MAX_GAME_ID_LENGTH && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

private const val MIN_GAME_ID_LENGTH = 8
private const val MAX_GAME_ID_LENGTH = 32
private const val MIN_COMMAND_FIELDS = 3
private const val SIMPLE_COMMAND_FIELDS = 3
private const val TURN_FIELD_COUNT = 6
private const val REACTION_FIELD_COUNT = 5
private const val PREFIX_FIELD = 0
private const val COMMAND_TYPE_FIELD = 1
private const val GAME_ID_FIELD = 2
private const val TURN_FIELD = 3
private const val RESULT_FIELD = 4
private const val ATTACK_FIELD = 5
