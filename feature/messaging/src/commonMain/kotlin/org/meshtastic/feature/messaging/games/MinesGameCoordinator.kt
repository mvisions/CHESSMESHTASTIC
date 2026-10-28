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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.safeCatching
import org.meshtastic.core.model.ContactKey
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.usecase.SendMessageOutcome
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.feature.messaging.chess.privateMessageContactKey
import kotlin.random.Random

@Single
@Suppress("TooManyFunctions")
class MinesGameCoordinator(
    private val packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val store: MinesGameStore,
) {
    private val mutex = Mutex()
    private val observerMutex = Mutex()
    private var loaded = false
    private val seen = mutableMapOf<String, Long>()
    private val _games = MutableStateFlow<Map<String, MinesSession>>(emptyMap())
    private val _invitations = MutableStateFlow<Map<String, MinesInvitation>>(emptyMap())
    val games = _games.asStateFlow()
    val invitations = _invitations.asStateFlow()

    suspend fun observeDirectMessages() = observerMutex.withLock {
        mutex.withLock { load() }
        packetRepository
            .getContacts()
            .map { contacts -> contacts.keys.filter(::isMinesDirectContact).sorted() }
            .distinctUntilChanged()
            .flatMapLatest { keys ->
                if (keys.isEmpty()) {
                    emptyFlow()
                } else {
                    keys
                        .map { key ->
                            packetRepository
                                .getMessagesFrom(
                                    key,
                                    getNode = { userId -> nodeRepository.getNode(userId.orEmpty()) },
                                )
                                .map { messages -> key to messages }
                        }
                        .merge()
                }
            }
            .collect { (contact, messages) ->
                for (message in messages.sortedBy(Message::uuid)) {
                    val command =
                        message
                            .takeUnless { it.fromLocal || it.uuid <= (seen[contact] ?: 0L) }
                            ?.let { MinesProtocol.decode(it.text) } ?: continue
                    // Radio retries use new message IDs; command turns, not message IDs, deduplicate moves.
                    safeCatching { receive(contact, message.node.effectiveLongName, command) }
                        .onSuccess {
                            store.markSeen(contact, message.uuid)
                            seen[contact] = message.uuid
                        }
                        .onFailure { Logger.w(it) { "Minesweeper message processing failed; retry is available" } }
                }
            }
    }

    suspend fun invite(node: Node): String = mutex.withLock {
        load()
        require(node.user.id.startsWith("!")) { "A direct node is required for Minesweeper" }
        val contact = privateMessageContactKey(node, nodeRepository.ourNodeInfo.value)
        _games.value.values
            .lastOrNull {
                it.contactKey == contact && it.phase in setOf(MinesPhase.INVITING, MinesPhase.PLAYING)
            }
            ?.let {
                return@withLock it.gameId
            }
        val id = Random.nextLong().toULong().toString(HEX_RADIX).padStart(GAME_ID_LENGTH, '0')
        val session =
            MinesSession(
                id,
                contact,
                node.effectiveLongName,
                isHost = true,
                phase = MinesPhase.INVITING,
                board = MinesBoard.random(),
            )
        put(session)
        send(session, MinesCommand.Invite(id))
        id
    }

    suspend fun respond(gameId: String, accept: Boolean) = mutex.withLock {
        load()
        val game = requireNotNull(_games.value[gameId]) { "Invitation is no longer available" }
        check(!game.isHost && game.phase == MinesPhase.INVITING) { "Invitation is no longer pending" }
        put(game.copy(phase = if (accept) MinesPhase.PLAYING else MinesPhase.DECLINED))
        send(game, if (accept) MinesCommand.Accept(gameId) else MinesCommand.Decline(gameId))
    }

    suspend fun reveal(gameId: String, cell: Int) = mutex.withLock {
        load()
        val game = requireNotNull(_games.value[gameId]) { "Game not found" }
        check(game.canReveal) { "It is not your turn to reveal a cell" }
        require(cell in 0 until MINES_CELLS && cell !in game.match.revealed) { "Cell is not available" }
        if (game.isHost) {
            hostReveal(game, cell)
        } else {
            val pending = game.copy(pendingCell = cell)
            put(pending)
            send(pending, MinesCommand.Move(gameId, game.match.turn + 1, cell))
        }
    }

    suspend fun retry(gameId: String) = mutex.withLock {
        load()
        val game = requireNotNull(_games.value[gameId]) { "Game not found" }
        when {
            game.localResigned -> send(game, MinesCommand.Resign(gameId))

            game.phase == MinesPhase.INVITING && game.isHost -> send(game, MinesCommand.Invite(gameId))

            game.replyPending -> sendReply(game)

            !game.isHost && game.pendingCell != null ->
                send(game, MinesCommand.Move(gameId, game.match.turn + 1, game.pendingCell))

            !game.isHost && game.phase == MinesPhase.PLAYING -> {
                if (game.match.turn == 0) send(game, MinesCommand.Accept(gameId))
                send(game, MinesCommand.Sync(gameId, game.match.turn))
            }

            game.isHost && game.lastReply != null -> sendReply(game)

            else -> error("There is no pending Minesweeper message")
        }
    }

    suspend fun resign(gameId: String) = mutex.withLock {
        load()
        val game = requireNotNull(_games.value[gameId]) { "Game not found" }
        check(game.phase == MinesPhase.PLAYING) { "Game is not active" }
        put(game.copy(phase = MinesPhase.FINISHED, localResigned = true, pendingCell = null, replyPending = false))
        send(game, MinesCommand.Resign(gameId))
    }

    internal suspend fun receive(contact: String, opponent: String, command: MinesCommand) = mutex.withLock {
        load()
        val game = _games.value[command.gameId]
        if (game != null && game.contactKey != contact) {
            Logger.w { "Minesweeper message rejected from another conversation" }
            return@withLock
        }
        if (command is MinesCommand.Invite && game == null) {
            val pendingInvitations = _games.value.values.filter { !it.isHost && it.phase == MinesPhase.INVITING }
            val pendingFromContact = pendingInvitations.count { it.contactKey == contact }
            if (
                pendingFromContact >= MAX_PENDING_INVITATIONS_PER_CONTACT ||
                pendingInvitations.size >= MAX_PENDING_INVITATIONS_TOTAL
            ) {
                Logger.w { "Minesweeper invitation rejected: pending invitation limit reached" }
                return@withLock
            }
            put(MinesSession(command.gameId, contact, opponent, false, MinesPhase.INVITING))
        } else if (game != null) {
            receiveForGame(game, command)
        } else {
            Logger.w { "Minesweeper message has no matching invitation" }
        }
    }

    private suspend fun receiveForGame(game: MinesSession, command: MinesCommand) {
        when (command) {
            is MinesCommand.Invite -> {
                if (!game.isHost && game.phase == MinesPhase.PLAYING) send(game, MinesCommand.Accept(game.gameId))
                if (!game.isHost && game.phase == MinesPhase.DECLINED) send(game, MinesCommand.Decline(game.gameId))
            }

            is MinesCommand.Accept -> {
                if (game.isHost && game.phase == MinesPhase.INVITING) put(game.copy(phase = MinesPhase.PLAYING))
            }

            is MinesCommand.Decline -> {
                if (game.isHost && game.phase == MinesPhase.INVITING) put(game.copy(phase = MinesPhase.DECLINED))
            }

            is MinesCommand.Move -> receiveMove(game, command)

            is MinesCommand.Reveal -> receiveReveal(game, command)

            is MinesCommand.Sync -> {
                if (game.localResigned) {
                    send(game, MinesCommand.Resign(game.gameId))
                } else if (game.isHost) {
                    resendResults(game, game.replies.filter { it.turn > command.turn }.take(MAX_SYNC_REPLIES))
                }
            }

            is MinesCommand.Resign -> {
                if (game.phase == MinesPhase.PLAYING) {
                    put(game.copy(phase = MinesPhase.FINISHED, opponentResigned = true, pendingCell = null))
                }
            }
        }
    }

    private suspend fun receiveMove(game: MinesSession, command: MinesCommand.Move) {
        if (!game.isHost) {
            Logger.w { "Minesweeper move sent to a non-host" }
            return
        }
        val previous = game.replies.firstOrNull { it.turn == command.turn && it.cell == command.cell }
        if (previous != null) {
            resendResults(game, game.replies.filter { it.turn >= previous.turn }.take(MAX_SYNC_REPLIES))
        } else if (game.canProcessGuestMove(command)) {
            hostReveal(game, command.cell)
        } else {
            Logger.w { "Minesweeper move rejected: stale turn or unavailable cell" }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun receiveReveal(game: MinesSession, command: MinesCommand.Reveal) {
        if (game.localResigned) {
            send(game, MinesCommand.Resign(game.gameId))
            return
        }
        if (game.isHost || command.turn <= game.match.turn) return
        if (!game.acceptsReply(command)) {
            Logger.w { "Minesweeper result rejected: unexpected turn or cell" }
            return
        }
        val match = game.match.reveal(command.cell, command.value)
        put(game.copy(match = match, pendingCell = null, phase = match.minesPhase()))
    }

    private suspend fun hostReveal(game: MinesSession, cell: Int) {
        val value = checkNotNull(game.board) { "The host's mine board is missing" }.valueAt(cell)
        val match = game.match.reveal(cell, value)
        val reply = MinesReply(match.turn, cell, value)
        val updated =
            game.copy(
                match = match,
                phase = match.minesPhase(),
                lastReply = reply,
                replyPending = true,
                pendingCell = cell,
                replies = game.replies + reply,
            )
        put(updated)
        sendReply(updated)
    }

    private suspend fun sendReply(game: MinesSession) {
        val reply = checkNotNull(game.lastReply) { "No result to resend" }
        send(game, reply.command(game.gameId))
        if (game.replyPending) put(game.copy(replyPending = false, pendingCell = null))
    }

    private suspend fun resendResults(game: MinesSession, replies: List<MinesReply>) {
        for (reply in replies) send(game, reply.command(game.gameId))
        if (game.replyPending && game.lastReply in replies) {
            put(game.copy(replyPending = false, pendingCell = null))
        }
    }

    private suspend fun send(game: MinesSession, command: MinesCommand) {
        when (sendMessageUseCase(MinesProtocol.encode(command), game.contactKey)) {
            is SendMessageOutcome.Queued -> Unit
            SendMessageOutcome.Refused -> error("Minesweeper message was refused")
        }
    }

    private suspend fun put(game: MinesSession) {
        _games.value = store.save(game)
        updateInvitations()
    }

    private suspend fun load() {
        if (loaded) return
        _games.value = store.load()
        seen.putAll(store.watermarks())
        updateInvitations()
        loaded = true
    }

    private fun updateInvitations() {
        _invitations.value =
            _games.value
                .filterValues { !it.isHost && it.phase == MinesPhase.INVITING }
                .mapValues { (_, game) -> MinesInvitation(game.gameId, game.contactKey, game.opponentName) }
    }

    private companion object {
        const val HEX_RADIX = 16
        const val GAME_ID_LENGTH = 16
        const val MAX_SYNC_REPLIES = 2
        const val MAX_PENDING_INVITATIONS_PER_CONTACT = 8
        const val MAX_PENDING_INVITATIONS_TOTAL = 100
    }
}

private fun isMinesDirectContact(contact: String): Boolean {
    val key = ContactKey(contact)
    return !key.isRetired && key.addressString.startsWith("!")
}

internal fun MinesSession.acceptsReply(command: MinesCommand.Reveal): Boolean = phase == MinesPhase.PLAYING &&
    !match.finished &&
    command.turn == match.turn + 1 &&
    command.cell !in match.revealed &&
    (match.activePlayer == 0 || pendingCell == command.cell)

private fun MinesMatch.minesPhase() = if (finished) MinesPhase.FINISHED else MinesPhase.PLAYING

private fun MinesSession.canProcessGuestMove(command: MinesCommand.Move): Boolean {
    val waitingForGuest = phase == MinesPhase.PLAYING && !match.finished && match.activePlayer == 1
    val validMove = command.turn == match.turn + 1 && command.cell !in match.revealed
    return waitingForGuest && validMove && !replyPending
}
