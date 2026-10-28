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

@Serializable
enum class MinesPhase {
    INVITING,
    PLAYING,
    DECLINED,
    FINISHED,
}

@Serializable
data class MinesSession(
    val gameId: String,
    val contactKey: String,
    val opponentName: String,
    val isHost: Boolean,
    val phase: MinesPhase,
    val match: MinesMatch = MinesMatch(),
    val pendingCell: Int? = null,
    val localResigned: Boolean = false,
    val opponentResigned: Boolean = false,
    val board: MinesBoard? = null,
    val lastReply: MinesReply? = null,
    val replyPending: Boolean = false,
    val replies: List<MinesReply> = emptyList(),
) {
    val localPlayer: Int
        get() = if (isHost) 0 else 1

    val canReveal: Boolean
        get() =
            phase == MinesPhase.PLAYING &&
                match.activePlayer == localPlayer &&
                pendingCell == null &&
                !replyPending &&
                !match.finished
}

@Serializable
data class MinesReply(val turn: Int, val cell: Int, val value: Int) {
    fun command(gameId: String) = MinesCommand.Reveal(gameId, turn, cell, value)
}

data class MinesInvitation(val gameId: String, val contactKey: String, val opponentName: String)
