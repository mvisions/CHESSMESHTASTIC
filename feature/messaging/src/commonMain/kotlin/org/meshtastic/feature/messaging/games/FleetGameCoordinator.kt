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
import org.meshtastic.core.model.ContactKey
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.usecase.SendMessageOutcome
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.feature.messaging.chess.privateMessageContactKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class FleetGamePhase {
    INVITING,
    PLAYING,
    DECLINED,
    FINISHED,
}

enum class FleetGameResult {
    WON,
    LOST,
    RESIGNED,
}

data class FleetGameSession(
    val gameId: String,
    val contactKey: String,
    val opponentNodeId: String,
    val opponentName: String,
    val board: FleetBoard,
    val phase: FleetGamePhase,
    val localTurn: Boolean,
    val turn: Int = 0,
    val incomingShots: Set<Int> = emptySet(),
    val outgoingShot: Int? = null,
    val outgoingResults: Map<Int, FleetShotResult> = emptyMap(),
    val pendingResult: FleetShotResult? = null,
    val result: FleetGameResult? = null,
    val lastShotAt: TimeMark? = null,
    val lastReactionAt: TimeMark? = null,
    val lastOpponentReactionAt: TimeMark? = null,
    val localReactionSequence: Int = 0,
    val opponentReactionSequence: Int = 0,
    val latestReaction: FleetGameReaction? = null,
) {
    val shotCooldownRemaining: Duration
        get() =
            lastShotAt?.let { (FLEET_SHOT_INTERVAL - it.elapsedNow()).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO

    val canFireShot: Boolean
        get() =
            phase == FleetGamePhase.PLAYING &&
                localTurn &&
                outgoingShot == null &&
                pendingResult?.allShipsSunk != true &&
                shotCooldownRemaining == Duration.ZERO

    val reactionCooldownRemaining: Duration
        get() =
            lastReactionAt?.let { (FLEET_REACTION_INTERVAL - it.elapsedNow()).coerceAtLeast(Duration.ZERO) }
                ?: Duration.ZERO

    val canSendReaction: Boolean
        get() =
            (phase == FleetGamePhase.PLAYING || phase == FleetGamePhase.FINISHED) &&
                reactionCooldownRemaining == Duration.ZERO
}

internal val FLEET_SHOT_INTERVAL = 30.seconds
internal val FLEET_REACTION_INTERVAL = 60.seconds

data class FleetGameReaction(val reaction: FleetReaction, val fromLocal: Boolean)

data class FleetInvitation(val gameId: String, val contactKey: String, val opponentName: String)

/** Coordinates fleet games using the same encrypted direct-message conversations as Chess. */
@Single
@Suppress("TooManyFunctions")
class FleetGameCoordinator(
    private val packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val boardStore: FleetBoardStore,
) {
    private val mutex = Mutex()
    private val lastSeenMessageByContact = mutableMapOf<String, Long>()
    private val _games = MutableStateFlow<Map<String, FleetGameSession>>(emptyMap())
    private val _invitations = MutableStateFlow<Map<String, FleetInvitation>>(emptyMap())

    val games = _games.asStateFlow()
    val invitations = _invitations.asStateFlow()

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

    fun directMessageContactKey(node: Node): String = privateMessageContactKey(node, nodeRepository.ourNodeInfo.value)

    suspend fun invite(node: Node, opponentName: String): String = mutex.withLock {
        val nodeId = node.user.id
        require(nodeId.startsWith(NODE_ID_PREFIX) && nodeId.length > NODE_ID_PREFIX.length) {
            "A valid Meshtastic node ID is required to start a fleet game."
        }
        val gameId = newGameId()
        val contactKey = privateMessageContactKey(node, nodeRepository.ourNodeInfo.value)
        val board = boardStore.getOrCreate(gameId)
        sendCommand(contactKey, FleetCommand.Invite(gameId))
        putGame(
            FleetGameSession(
                gameId = gameId,
                contactKey = contactKey,
                opponentNodeId = nodeId,
                opponentName = opponentName,
                board = board,
                phase = FleetGamePhase.INVITING,
                localTurn = false,
            ),
        )
        gameId
    }

    suspend fun acceptInvitation(gameId: String): Boolean = mutex.withLock {
        val invitation = _invitations.value[gameId] ?: return@withLock false
        val board = boardStore.getOrCreate(gameId)
        sendCommand(invitation.contactKey, FleetCommand.Accept(gameId))
        putGame(
            FleetGameSession(
                gameId = gameId,
                contactKey = invitation.contactKey,
                opponentNodeId = NodeAddress.fromString(ContactKey(invitation.contactKey).addressString).toIdString(),
                opponentName = invitation.opponentName,
                board = board,
                phase = FleetGamePhase.PLAYING,
                localTurn = false,
            ),
        )
        _invitations.value = _invitations.value - gameId
        true
    }

    suspend fun declineInvitation(gameId: String): Boolean = mutex.withLock {
        val invitation = _invitations.value[gameId] ?: return@withLock false
        sendCommand(invitation.contactKey, FleetCommand.Decline(gameId))
        _invitations.value = _invitations.value - gameId
        true
    }

    suspend fun fireShot(gameId: String, cell: Int): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (!game.canFireShot || cell !in 0 until FLEET_BOARD_CELLS) {
            Logger.w { "Fleet shot blocked: turn or cooldown is not ready" }
            return@withLock false
        }
        if (cell in game.outgoingResults || cell == game.outgoingShot) return@withLock false
        val command = FleetCommand.Turn(gameId, game.turn + 1, game.pendingResult, cell)
        sendCommand(game.contactKey, command)
        putGame(
            game.copy(
                localTurn = false,
                turn = command.turn,
                outgoingShot = cell,
                pendingResult = null,
                lastShotAt = TimeSource.Monotonic.markNow(),
            ),
        )
        true
    }

    suspend fun resign(gameId: String): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (game.phase != FleetGamePhase.PLAYING) return@withLock false
        sendCommand(game.contactKey, FleetCommand.Resign(gameId))
        putGame(game.copy(phase = FleetGamePhase.FINISHED, result = FleetGameResult.RESIGNED))
        true
    }

    suspend fun sendReaction(gameId: String, reaction: FleetReaction): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        if (!game.canSendReaction) {
            Logger.w { "Fleet reaction blocked: game or cooldown is not ready" }
            return@withLock false
        }
        val command = FleetCommand.Reaction(gameId, game.localReactionSequence + 1, reaction)
        sendCommand(game.contactKey, command)
        putGame(requireNotNull(game.applyReaction(command, fromLocal = true)))
        true
    }

    suspend fun reportDefeat(gameId: String): Boolean = mutex.withLock {
        val game = _games.value[gameId] ?: return@withLock false
        val result = game.pendingResult?.takeIf(FleetShotResult::allShipsSunk) ?: return@withLock false
        val command = FleetCommand.Turn(gameId, game.turn + 1, result, null)
        sendCommand(game.contactKey, command)
        putGame(
            game.copy(
                phase = FleetGamePhase.FINISHED,
                localTurn = false,
                turn = command.turn,
                result = FleetGameResult.LOST,
            ),
        )
        true
    }

    private fun messageFlows(contactKeys: List<String>): Flow<Pair<String, List<Message>>> {
        if (contactKeys.isEmpty()) return emptyFlow()
        return contactKeys
            .map { contactKey ->
                packetRepository
                    .getMessagesFrom(
                        contactKey,
                        getNode = { userId -> nodeRepository.getNode(userId.orEmpty()) },
                    )
                    .map { messages -> contactKey to messages }
            }
            .merge()
    }

    private suspend fun processMessage(contactKey: String, message: Message) {
        val command = FleetProtocol.decode(message.text) ?: return
        val game = _games.value[command.gameId]
        when (command) {
            is FleetCommand.Invite -> processInvite(contactKey, message, command)

            is FleetCommand.Accept -> processAccept(contactKey, message, command, game)

            is FleetCommand.Decline -> processDecline(command, message, game)

            is FleetCommand.Turn -> processTurn(game, command, message)

            is FleetCommand.Reaction -> {
                if (game?.contactKey == contactKey) {
                    game.applyReaction(command, message.fromLocal)?.let(::putGame)
                }
            }

            is FleetCommand.Resign ->
                if (game?.phase == FleetGamePhase.PLAYING) {
                    putGame(
                        game.copy(
                            phase = FleetGamePhase.FINISHED,
                            result = if (message.fromLocal) FleetGameResult.RESIGNED else FleetGameResult.WON,
                        ),
                    )
                }
        }
    }

    private suspend fun processInvite(contactKey: String, message: Message, command: FleetCommand.Invite) {
        if (_games.value.containsKey(command.gameId) || _invitations.value.containsKey(command.gameId)) return
        val opponentId = message.node.user.id.ifBlank { ContactKey(contactKey).addressString }
        val opponentName =
            message.node.user.long_name.ifBlank {
                message.node.user.short_name.ifBlank { opponentId }
            }
        if (message.fromLocal) {
            putGame(
                FleetGameSession(
                    gameId = command.gameId,
                    contactKey = contactKey,
                    opponentNodeId = opponentId,
                    opponentName = opponentName,
                    board = boardStore.getOrCreate(command.gameId),
                    phase = FleetGamePhase.INVITING,
                    localTurn = false,
                ),
            )
        } else {
            _invitations.value =
                _invitations.value + (command.gameId to FleetInvitation(command.gameId, contactKey, opponentName))
        }
    }

    private suspend fun processAccept(
        contactKey: String,
        message: Message,
        command: FleetCommand.Accept,
        game: FleetGameSession?,
    ) {
        if (!message.fromLocal && game?.phase == FleetGamePhase.INVITING) {
            putGame(game.copy(phase = FleetGamePhase.PLAYING, localTurn = true))
        } else if (message.fromLocal && game == null) {
            val invitation = _invitations.value[command.gameId] ?: return
            putGame(
                FleetGameSession(
                    gameId = invitation.gameId,
                    contactKey = contactKey,
                    opponentNodeId = ContactKey(contactKey).addressString,
                    opponentName = invitation.opponentName,
                    board = boardStore.getOrCreate(invitation.gameId),
                    phase = FleetGamePhase.PLAYING,
                    localTurn = false,
                ),
            )
            _invitations.value = _invitations.value - invitation.gameId
        }
    }

    private fun processDecline(command: FleetCommand.Decline, message: Message, game: FleetGameSession?) {
        _invitations.value = _invitations.value - command.gameId
        if (!message.fromLocal && game?.phase == FleetGamePhase.INVITING) {
            putGame(game.copy(phase = FleetGamePhase.DECLINED))
        }
    }

    private fun processTurn(game: FleetGameSession?, command: FleetCommand.Turn, message: Message) {
        val updated = game?.applyTurn(command, message.fromLocal) ?: return
        putGame(updated)
    }

    private suspend fun sendCommand(contactKey: String, command: FleetCommand) {
        when (sendMessageUseCase.invoke(FleetProtocol.encode(command), contactKey)) {
            is SendMessageOutcome.Queued -> Unit

            SendMessageOutcome.Refused ->
                error("The fleet-game message was refused by the direct-message conversation.")
        }
    }

    private fun putGame(game: FleetGameSession) {
        _games.value = _games.value + (game.gameId to game)
    }

    private fun isDirectConversation(contactKey: String): Boolean {
        val key = ContactKey(contactKey)
        return !key.isRetired && key.addressString.startsWith(NODE_ID_PREFIX)
    }

    private fun newGameId(): String =
        kotlin.random.Random.nextLong().toULong().toString(HEX_RADIX).padStart(GAME_ID_LENGTH, '0')

    private companion object {
        const val NODE_ID_PREFIX = "!"
        const val HEX_RADIX = 16
        const val GAME_ID_LENGTH = 16
    }
}

@Suppress("ReturnCount")
internal fun FleetGameSession.applyTurn(command: FleetCommand.Turn, fromLocal: Boolean): FleetGameSession? {
    if (phase != FleetGamePhase.PLAYING || command.turn != turn + 1 || fromLocal != localTurn) return null

    if (fromLocal) {
        if (command.result != pendingResult) return null
        if (command.result?.allShipsSunk == true) {
            if (command.attack != null) return null
            return copy(
                phase = FleetGamePhase.FINISHED,
                localTurn = false,
                turn = command.turn,
                result = FleetGameResult.LOST,
            )
        }
        val attack = command.attack ?: return null
        if (attack in outgoingResults || attack == outgoingShot) return null
        return copy(turn = command.turn, localTurn = false, outgoingShot = attack, pendingResult = null)
    }

    if ((outgoingShot == null) != (command.result == null)) return null
    val outgoingResults =
        outgoingShot?.let { shot -> outgoingResults + (shot to requireNotNull(command.result)) } ?: outgoingResults
    if (command.result?.allShipsSunk == true) {
        if (command.attack != null) return null
        return copy(
            phase = FleetGamePhase.FINISHED,
            localTurn = false,
            turn = command.turn,
            outgoingResults = outgoingResults,
            result = FleetGameResult.WON,
        )
    }

    val attack = command.attack ?: return null
    if (attack in incomingShots) return null
    val incomingShots = incomingShots + attack
    val result = board.receiveShot(attack, this.incomingShots)
    return copy(
        turn = command.turn,
        localTurn = true,
        incomingShots = incomingShots,
        outgoingResults = outgoingResults,
        outgoingShot = null,
        pendingResult = result,
    )
}

@Suppress("ReturnCount")
internal fun FleetGameSession.applyReaction(
    command: FleetCommand.Reaction,
    fromLocal: Boolean,
    now: TimeMark = TimeSource.Monotonic.markNow(),
): FleetGameSession? {
    if (command.gameId != gameId || (phase != FleetGamePhase.PLAYING && phase != FleetGamePhase.FINISHED)) return null
    val previousSequence = if (fromLocal) localReactionSequence else opponentReactionSequence
    if (command.sequence <= previousSequence) return null
    val lastSent = if (fromLocal) lastReactionAt else lastOpponentReactionAt
    if (lastSent != null && lastSent.elapsedNow() < FLEET_REACTION_INTERVAL) {
        Logger.w { "Fleet reaction ignored during cooldown" }
        return null
    }
    return if (fromLocal) {
        copy(
            lastReactionAt = now,
            localReactionSequence = command.sequence,
            latestReaction = FleetGameReaction(command.reaction, true),
        )
    } else {
        copy(
            lastOpponentReactionAt = now,
            opponentReactionSequence = command.sequence,
            latestReaction = FleetGameReaction(command.reaction, false),
        )
    }
}
