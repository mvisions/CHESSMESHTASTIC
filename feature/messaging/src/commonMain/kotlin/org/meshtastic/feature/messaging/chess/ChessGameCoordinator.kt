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

import kotlinx.coroutines.flow.Flow
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
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.ContactKey
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.usecase.SendMessageOutcome
import org.meshtastic.core.repository.usecase.SendMessageUseCase

enum class ChessGamePhase {
    INVITING,
    PLAYING,
    DECLINED,
    FINISHED,
}

enum class ChessFinishReason {
    RESIGNATION,
    TIMEOUT,
    AGREED_DRAW,
}

data class ChessGameResult(val reason: ChessFinishReason, val loser: ChessSide? = null)

data class ChessGameSession(
    val gameId: String,
    val contactKey: String,
    val opponentNodeId: String,
    val opponentName: String,
    val localSide: ChessSide,
    val position: ChessPosition = ChessPosition.initial(),
    val ply: Int = 0,
    val phase: ChessGamePhase,
    val turnDeadlineMillis: Long? = null,
    val drawOfferedBy: ChessSide? = null,
    val result: ChessGameResult? = null,
)

data class ChessInvitation(val gameId: String, val contactKey: String, val opponentName: String)

/**
 * Coordinates chess sessions over existing encrypted direct-message conversations.
 *
 * The chat database is the durable event log: rebuilding from it restores invitations, moves, offers, and results when
 * the app process restarts.
 */
@Single
@Suppress("TooManyFunctions")
class ChessGameCoordinator(
    private val packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    private val sendMessageUseCase: SendMessageUseCase,
) {
    private val mutex = Mutex()
    private val lastSeenMessageByContact = mutableMapOf<String, Long>()
    private val _games = MutableStateFlow<Map<String, ChessGameSession>>(emptyMap())
    private val _invitations = MutableStateFlow<Map<String, ChessInvitation>>(emptyMap())

    val games = _games.asStateFlow()
    val invitations = _invitations.asStateFlow()

    /** Collect for the lifetime of the app shell so invitations are noticed outside the chat screen. */
    suspend fun observeDirectMessages() {
        packetRepository
            .getContacts()
            .map { contacts -> contacts.keys.filter(::isDirectConversation).sorted() }
            .distinctUntilChanged()
            .flatMapLatest(::messageFlows)
            .collect { (contactKey, messages) ->
                mutex.withLock {
                    for (message in messages.sortedBy(Message::uuid)) {
                        val lastSeen = lastSeenMessageByContact[contactKey] ?: 0L
                        if (message.uuid <= lastSeen) continue
                        lastSeenMessageByContact[contactKey] = message.uuid
                        processMessage(contactKey, message)
                    }
                }
            }
    }

    /** The same conversation "Send private message" opens, so games travel exactly like a direct message. */
    fun directMessageContactKey(node: Node): String = privateMessageContactKey(node, nodeRepository.ourNodeInfo.value)

    suspend fun invite(node: Node, opponentName: String): String = mutex.withLock {
        val nodeId = node.user.id
        require(nodeId.startsWith(NODE_ID_PREFIX) && nodeId.length > NODE_ID_PREFIX.length) {
            "A valid Meshtastic node ID is required to start a chess game."
        }
        val gameId = newGameId()
        val contactKey = privateMessageContactKey(node, nodeRepository.ourNodeInfo.value)
        sendCommand(contactKey, ChessCommand.Invite(gameId))
        putGame(
            ChessGameSession(
                gameId = gameId,
                contactKey = contactKey,
                opponentNodeId = nodeId,
                opponentName = opponentName,
                localSide = ChessSide.WHITE,
                phase = ChessGamePhase.INVITING,
            ),
        )
        gameId
    }

    suspend fun acceptInvitation(gameId: String): Boolean = mutex.withLock {
        val invitation = _invitations.value[gameId] ?: return@withLock false
        sendCommand(invitation.contactKey, ChessCommand.Accept(gameId))
        val opponentId = NodeAddress.fromString(ContactKey(invitation.contactKey).addressString).toIdString()
        putGame(
            ChessGameSession(
                gameId = gameId,
                contactKey = invitation.contactKey,
                opponentNodeId = opponentId,
                opponentName = invitation.opponentName,
                localSide = ChessSide.BLACK,
                phase = ChessGamePhase.PLAYING,
                turnDeadlineMillis = nowMillis + TURN_LIMIT_MILLIS,
            ),
        )
        _invitations.value = _invitations.value - gameId
        true
    }

    suspend fun declineInvitation(gameId: String): Boolean = mutex.withLock {
        val invitation = _invitations.value[gameId] ?: return@withLock false
        sendCommand(invitation.contactKey, ChessCommand.Decline(gameId))
        _invitations.value = _invitations.value - gameId
        true
    }

    suspend fun playMove(gameId: String, move: ChessMove): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (game.phase != ChessGamePhase.PLAYING || game.position.sideToMove != game.localSide) return@withLock false
        if (isExpired(game, nowMillis)) return@withLock false
        val nextPosition = game.position.play(move) ?: return@withLock false
        val nextPly = game.ply + 1
        sendCommand(game.contactKey, ChessCommand.Move(gameId, nextPly, move))
        putGame(game.afterMove(nextPosition, nextPly, nowMillis))
        true
    }

    suspend fun resign(gameId: String): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (game.phase != ChessGamePhase.PLAYING) return@withLock false
        sendCommand(game.contactKey, ChessCommand.Resign(gameId))
        finish(game, ChessFinishReason.RESIGNATION, game.localSide)
        true
    }

    suspend fun offerDraw(gameId: String): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (game.phase != ChessGamePhase.PLAYING || game.drawOfferedBy != null) return@withLock false
        sendCommand(game.contactKey, ChessCommand.DrawOffer(gameId))
        putGame(game.copy(drawOfferedBy = game.localSide))
        true
    }

    suspend fun respondToDraw(gameId: String, accept: Boolean): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (game.phase != ChessGamePhase.PLAYING || game.drawOfferedBy != game.localSide.opposite) return@withLock false
        sendCommand(
            game.contactKey,
            if (accept) ChessCommand.DrawAccept(gameId) else ChessCommand.DrawDecline(gameId),
        )
        if (accept) {
            finish(game, ChessFinishReason.AGREED_DRAW)
        } else {
            putGame(game.copy(drawOfferedBy = null))
        }
        true
    }

    suspend fun claimDraw(gameId: String, outcome: ClaimableDraw): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (game.phase != ChessGamePhase.PLAYING || game.position.sideToMove != game.localSide) return@withLock false
        val chessOutcome =
            when (outcome) {
                ClaimableDraw.THREEFOLD_REPETITION -> ChessOutcome.DRAW_THREEFOLD_REPETITION
                ClaimableDraw.FIFTY_MOVE_RULE -> ChessOutcome.DRAW_FIFTY_MOVE_RULE
            }
        val claimedPosition = game.position.claimDraw(chessOutcome) ?: return@withLock false
        sendCommand(game.contactKey, ChessCommand.DrawClaim(gameId, outcome))
        putGame(game.copy(position = claimedPosition, phase = ChessGamePhase.FINISHED, turnDeadlineMillis = null))
        true
    }

    suspend fun checkTurnTimeout(gameId: String, timeMillis: Long = nowMillis): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (game.phase != ChessGamePhase.PLAYING || !isExpired(game, timeMillis)) return@withLock false
        val loser = game.position.sideToMove
        sendCommand(game.contactKey, ChessCommand.Timeout(gameId, game.ply, loser))
        finish(game, ChessFinishReason.TIMEOUT, loser)
        true
    }

    private fun messageFlows(contactKeys: List<String>): Flow<Pair<String, List<Message>>> {
        if (contactKeys.isEmpty()) return emptyFlow()
        return contactKeys
            .map { contactKey ->
                packetRepository
                    .getMessagesFrom(
                        contactKey,
                        getNode = { userId ->
                            nodeRepository.getNode(userId.orEmpty())
                        },
                    )
                    .map { messages -> contactKey to messages }
            }
            .merge()
    }

    private fun processMessage(contactKey: String, message: Message) {
        val command = ChessProtocol.decode(message.text) ?: return
        val game = _games.value[command.gameId]
        val senderSide = game?.let { if (message.fromLocal) it.localSide else it.localSide.opposite }
        when (command) {
            is ChessCommand.Invite -> processInvite(contactKey, message, command)

            is ChessCommand.Accept -> processAccept(contactKey, message, command, game)

            is ChessCommand.Decline -> processDecline(command, message, game)

            is ChessCommand.Move -> processMove(game, command, message)

            is ChessCommand.Resign ->
                if (game?.phase == ChessGamePhase.PLAYING && senderSide != null) {
                    finish(game, ChessFinishReason.RESIGNATION, senderSide)
                }

            is ChessCommand.DrawOffer ->
                if (game?.phase == ChessGamePhase.PLAYING && senderSide != null) {
                    putGame(game.copy(drawOfferedBy = senderSide))
                }

            is ChessCommand.DrawAccept -> processDrawAccept(game, senderSide)

            is ChessCommand.DrawDecline -> processDrawDecline(game, senderSide)

            is ChessCommand.DrawClaim -> processDrawClaim(game, command, senderSide)

            is ChessCommand.Timeout -> processTimeout(game, command)
        }
    }

    private fun processInvite(contactKey: String, message: Message, command: ChessCommand.Invite) {
        if (_games.value.containsKey(command.gameId)) return
        val opponentId = message.node.user.id.ifBlank { ContactKey(contactKey).addressString }
        val opponentName =
            message.node.user.long_name.ifBlank {
                message.node.user.short_name.ifBlank { opponentId }
            }
        if (message.fromLocal) {
            putGame(
                ChessGameSession(
                    gameId = command.gameId,
                    contactKey = contactKey,
                    opponentNodeId = opponentId,
                    opponentName = opponentName,
                    localSide = ChessSide.WHITE,
                    phase = ChessGamePhase.INVITING,
                ),
            )
        } else {
            _invitations.value =
                _invitations.value + (command.gameId to ChessInvitation(command.gameId, contactKey, opponentName))
        }
    }

    private fun processAccept(
        contactKey: String,
        message: Message,
        command: ChessCommand.Accept,
        game: ChessGameSession?,
    ) {
        val invitation = _invitations.value[command.gameId]
        if (message.fromLocal && game == null && invitation != null) {
            putGame(
                ChessGameSession(
                    gameId = command.gameId,
                    contactKey = contactKey,
                    opponentNodeId = ContactKey(contactKey).addressString,
                    opponentName = invitation.opponentName,
                    localSide = ChessSide.BLACK,
                    phase = ChessGamePhase.PLAYING,
                    turnDeadlineMillis = message.receivedTime + TURN_LIMIT_MILLIS,
                ),
            )
            _invitations.value = _invitations.value - command.gameId
        } else if (!message.fromLocal && game?.phase == ChessGamePhase.INVITING) {
            putGame(
                game.copy(
                    turnDeadlineMillis = message.receivedTime + TURN_LIMIT_MILLIS,
                    phase = ChessGamePhase.PLAYING,
                ),
            )
        }
    }

    private fun processDecline(command: ChessCommand.Decline, message: Message, game: ChessGameSession?) {
        _invitations.value = _invitations.value - command.gameId
        if (!message.fromLocal && game?.phase == ChessGamePhase.INVITING) {
            putGame(game.copy(phase = ChessGamePhase.DECLINED))
        }
    }

    internal fun processMove(game: ChessGameSession?, command: ChessCommand.Move, message: Message) {
        game
            ?.takeIf {
                it.phase == ChessGamePhase.PLAYING &&
                    command.ply == it.ply + 1 &&
                    message.fromLocal == (it.position.sideToMove == it.localSide)
            }
            ?.let { activeGame ->
                if (isExpired(activeGame, message.receivedTime)) {
                    finish(activeGame, ChessFinishReason.TIMEOUT, activeGame.position.sideToMove)
                    return
                }
                activeGame.position.play(command.move)?.let { position ->
                    putGame(activeGame.afterMove(position, command.ply, message.receivedTime))
                }
            }
    }

    private fun processDrawAccept(game: ChessGameSession?, senderSide: ChessSide?) {
        if (game?.phase == ChessGamePhase.PLAYING && game.drawOfferedBy == senderSide?.opposite) {
            finish(game, ChessFinishReason.AGREED_DRAW)
        }
    }

    private fun processDrawDecline(game: ChessGameSession?, senderSide: ChessSide?) {
        if (game?.phase == ChessGamePhase.PLAYING && game.drawOfferedBy == senderSide?.opposite) {
            putGame(game.copy(drawOfferedBy = null))
        }
    }

    internal fun processDrawClaim(game: ChessGameSession?, command: ChessCommand.DrawClaim, senderSide: ChessSide?) {
        if (game?.phase != ChessGamePhase.PLAYING || senderSide != game.position.sideToMove) return
        val outcome =
            when (command.outcome) {
                ClaimableDraw.THREEFOLD_REPETITION -> ChessOutcome.DRAW_THREEFOLD_REPETITION
                ClaimableDraw.FIFTY_MOVE_RULE -> ChessOutcome.DRAW_FIFTY_MOVE_RULE
            }
        game.position.claimDraw(outcome)?.let { position ->
            putGame(game.copy(position = position, phase = ChessGamePhase.FINISHED, turnDeadlineMillis = null))
        }
    }

    private fun processTimeout(game: ChessGameSession?, command: ChessCommand.Timeout) {
        if (
            game?.phase == ChessGamePhase.PLAYING &&
            command.ply == game.ply &&
            command.loser == game.position.sideToMove
        ) {
            finish(game, ChessFinishReason.TIMEOUT, command.loser)
        }
    }

    private suspend fun sendCommand(contactKey: String, command: ChessCommand) {
        when (sendMessageUseCase.invoke(ChessProtocol.encode(command), contactKey)) {
            is SendMessageOutcome.Queued -> Unit
            SendMessageOutcome.Refused -> error("The chess message was refused by the direct-message conversation.")
        }
    }

    private fun putGame(game: ChessGameSession) {
        _games.value = _games.value + (game.gameId to game)
    }

    private fun finish(game: ChessGameSession, reason: ChessFinishReason, loser: ChessSide? = null) {
        putGame(
            game.copy(
                phase = ChessGamePhase.FINISHED,
                turnDeadlineMillis = null,
                drawOfferedBy = null,
                result = ChessGameResult(reason, loser),
            ),
        )
    }

    private fun ChessGameSession.afterMove(position: ChessPosition, ply: Int, timeMillis: Long): ChessGameSession {
        val terminalOutcome = position.outcome.isTerminal()
        return copy(
            position = position,
            ply = ply,
            phase = if (terminalOutcome) ChessGamePhase.FINISHED else ChessGamePhase.PLAYING,
            turnDeadlineMillis = if (terminalOutcome) null else timeMillis + TURN_LIMIT_MILLIS,
            drawOfferedBy = null,
        )
    }

    private fun isExpired(game: ChessGameSession, timeMillis: Long): Boolean =
        game.turnDeadlineMillis?.let { timeMillis >= it } == true

    private fun isDirectConversation(contactKey: String): Boolean {
        val key = ContactKey(contactKey)
        return !key.isRetired && key.addressString.startsWith(NODE_ID_PREFIX)
    }

    private fun newGameId(): String =
        kotlin.random.Random.nextLong().toULong().toString(HEX_RADIX).padStart(GAME_ID_LENGTH, '0')

    private fun ChessOutcome.isTerminal(): Boolean = this != ChessOutcome.ACTIVE && this != ChessOutcome.CHECK

    private companion object {
        const val NODE_ID_PREFIX = "!"
        const val TURN_LIMIT_MILLIS = 5 * 60 * 1000L
        const val HEX_RADIX = 16
        const val GAME_ID_LENGTH = 16
    }
}

/**
 * Mirrors the node detail "Message" action: public-key encryption when both nodes hold keys, otherwise the channel the
 * node was heard on.
 */
fun privateMessageContactKey(node: Node, ourNode: Node?): String {
    val channel = if (ourNode?.hasPKC == true && node.hasPKC) NodeAddress.PKC_CHANNEL_INDEX else node.channel
    return NodeAddress.ById(node.user.id).toContactKey(channel).value
}
