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

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import org.meshtastic.core.datastore.di.asCorePreferencesDataStore
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.usecase.SendMessageOutcome
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MinesGameCoordinatorTest {
    private val files = mutableListOf<Path>()
    private val contact = "0!22222222"
    private val guestContact = "0!11111111"
    private val guestNode =
        Node(
            2,
            user =
            User.Builder()
                .apply {
                    id = "!22222222"
                    long_name = "Guest"
                }
                .build(),
        )

    @AfterTest
    fun cleanup() {
        files.forEach { FileSystem.SYSTEM.delete(it, mustExist = false) }
    }

    @Test
    fun twoPeersAgreeAndOnlyRevealedInformationCrossesTheRadio() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = start(host, guest)
        val board = assertNotNull(host.coordinator.games.value[id]?.board)
        val safe = (0 until MINES_CELLS).first { it !in board.mines }
        host.coordinator.reveal(id, safe)
        host.deliver(guest, guestContact)
        assertEquals(host.coordinator.games.value[id]?.match, guest.coordinator.games.value[id]?.match)
        assertNull(guest.coordinator.games.value[id]?.board)
        val mine = board.mines.first()
        guest.coordinator.reveal(id, mine)
        assertEquals(mine, guest.coordinator.games.value[id]?.pendingCell)
        assertFailsWith<IllegalStateException> { guest.coordinator.reveal(id, safe) }
        guest.deliver(host, contact)
        host.deliver(guest, guestContact)
        val result = assertNotNull(guest.coordinator.games.value[id])
        assertEquals(listOf(1, 0), result.match.scores)
        assertEquals(listOf(3, 2), result.match.lives)
        assertEquals(host.coordinator.games.value[id]?.match, result.match)
        assertTrue(host.sender.sent.all { it.length < 100 && !it.contains("mines") })
    }

    @Test
    fun lostAndOutOfOrderResultsRecoverWithoutReplayingAMoveTwice() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = start(host, guest)
        val board = assertNotNull(host.coordinator.games.value[id]?.board)
        val safe = (0 until MINES_CELLS).filter { it !in board.mines }
        host.coordinator.reveal(id, safe[0])
        host.deliver(guest, guestContact)
        guest.coordinator.reveal(id, safe[1])
        guest.deliver(host, contact)
        host.sender.queue.clear() // The guest's result is lost.
        host.coordinator.reveal(id, safe[2])
        host.deliver(guest, guestContact) // Later host result arrives first and is rejected.
        assertEquals(1, guest.coordinator.games.value[id]?.match?.turn)
        guest.coordinator.retry(id)
        guest.deliver(host, contact)
        assertEquals(2, host.sender.queue.size)
        host.deliver(guest, guestContact)
        assertEquals(3, guest.coordinator.games.value[id]?.match?.turn)
        assertEquals(host.coordinator.games.value[id]?.match, guest.coordinator.games.value[id]?.match)
        guest.coordinator.retry(id) // Sync while waiting, without a pending cell.
        guest.deliver(host, contact)
        assertTrue(host.sender.queue.isEmpty())
    }

    @Test
    fun restartRestoresSecretBoardPendingMoveAndLatestReply() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = start(host, guest)
        host.coordinator.reveal(id, 0)
        host.deliver(guest, guestContact)
        guest.coordinator.reveal(id, 1)
        guest.sender.queue.clear()
        val restoredGuest = peer(backgroundScope, guest.store)
        restoredGuest.coordinator.retry(id)
        restoredGuest.deliver(host, contact)
        host.sender.queue.clear()
        val restoredHost = peer(backgroundScope, host.store)
        restoredGuest.coordinator.retry(id)
        restoredGuest.deliver(restoredHost, contact)
        restoredHost.deliver(restoredGuest, guestContact)
        val hostState = assertNotNull(restoredHost.coordinator.games.value[id])
        val guestState = assertNotNull(restoredGuest.coordinator.games.value[id])
        assertEquals(2, hostState.match.turn)
        assertEquals(hostState.match, guestState.match)
        assertNotNull(hostState.board)
        assertNull(guestState.board)
        assertNull(guestState.pendingCell)
    }

    @Test
    fun refusedResultStaysRetryableAndDoesNotAdvanceTheHostTwice() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = start(host, guest)
        host.sender.refused = true
        assertFailsWith<IllegalStateException> { host.coordinator.reveal(id, 0) }
        assertTrue(assertNotNull(host.coordinator.games.value[id]).replyPending)
        host.sender.refused = false
        host.coordinator.retry(id)
        host.deliver(guest, guestContact)
        val result = assertNotNull(host.coordinator.games.value[id])
        assertEquals(1, result.match.turn)
        assertFalse(result.replyPending)
        assertEquals(result.match, guest.coordinator.games.value[id]?.match)
    }

    @Test
    fun foreignConversationCannotAcceptMoveRevealOrResignAnotherGame() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = start(host, guest)
        val before = assertNotNull(host.coordinator.games.value[id])
        host.coordinator.receive("0!33333333", "Other", MinesCommand.Resign(id))
        host.coordinator.receive("0!33333333", "Other", MinesCommand.Move(id, 1, 0))
        assertEquals(before, host.coordinator.games.value[id])
        host.coordinator.resign(id)
        host.deliver(guest, guestContact)
        assertTrue(assertNotNull(guest.coordinator.games.value[id]).opponentResigned)
        assertFalse(assertNotNull(guest.coordinator.games.value[id]).canReveal)
    }

    @Test
    fun repeatInviteResumesGameAndDeclinesStayDeclined() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = host.coordinator.invite(guestNode)
        host.deliver(guest, guestContact)
        guest.coordinator.respond(id, accept = false)
        guest.deliver(host, contact)
        assertEquals(MinesPhase.DECLINED, host.coordinator.games.value[id]?.phase)
        val next = start(host, guest)
        assertEquals(next, host.coordinator.invite(guestNode))
        assertTrue(host.sender.queue.isEmpty())
        host.store.markSeen(contact, 12)
        host.store.markSeen(contact, 4)
        assertEquals(12L, host.store.watermarks()[contact])
    }

    @Test
    fun incomingInvitationsAreBoundedPerContactAndAcrossContacts() = runTest {
        val guest = peer(backgroundScope)
        repeat(10) { index ->
            guest.coordinator.receive(
                guestContact,
                "Host",
                MinesCommand.Invite(index.toString(16).padStart(16, '0')),
            )
        }
        assertEquals(8, guest.coordinator.invitations.value.size)

        repeat(100) { index ->
            guest.coordinator.receive(
                "0!${(index + 100).toString(16).padStart(8, '0')}",
                "Host",
                MinesCommand.Invite((index + 100).toString(16).padStart(16, '0')),
            )
        }
        assertEquals(100, guest.coordinator.invitations.value.size)
        guest.coordinator.receive("0!deadbeef", "Host", MinesCommand.Invite("00000000000000ff"))
        assertEquals(100, guest.coordinator.invitations.value.size)
    }

    @Test
    fun terminalGameHistoryIsPrunedWithoutRemovingActiveInvitations() = runTest {
        val guest = peer(backgroundScope)
        val activeId = "0000000000000001"
        guest.coordinator.receive(guestContact, "Host", MinesCommand.Invite(activeId))
        repeat(55) { index ->
            val id = (index + 2).toString(16).padStart(16, '0')
            val from = "0!${(index + 200).toString(16).padStart(8, '0')}"
            guest.coordinator.receive(from, "Host", MinesCommand.Invite(id))
            guest.coordinator.respond(id, accept = false)
        }
        assertEquals(51, guest.coordinator.games.value.size)
        assertNotNull(guest.coordinator.games.value[activeId])
        assertNull(guest.coordinator.games.value["0000000000000002"])
        assertNotNull(guest.coordinator.games.value["0000000000000038"])
        assertEquals(1, guest.coordinator.invitations.value.size)
    }

    @Test
    fun completeDuelEndsOnBothPeersAtTheThirdLostLife() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = start(host, guest)
        val board = assertNotNull(host.coordinator.games.value[id]?.board)
        val safe = (0 until MINES_CELLS).filter { it !in board.mines }
        val mines = board.mines.toList()
        repeat(MINES_LIVES) { index ->
            host.coordinator.reveal(id, safe[index])
            host.deliver(guest, guestContact)
            guest.coordinator.reveal(id, mines[index])
            guest.deliver(host, contact)
            host.deliver(guest, guestContact)
        }
        val winner = assertNotNull(host.coordinator.games.value[id])
        val loser = assertNotNull(guest.coordinator.games.value[id])
        assertEquals(MinesPhase.FINISHED, winner.phase)
        assertEquals(MinesPhase.FINISHED, loser.phase)
        assertEquals(0, winner.match.winner)
        assertEquals(winner.match, loser.match)
        assertFalse(winner.canReveal)
        assertFalse(loser.canReveal)
        assertFailsWith<IllegalStateException> { host.coordinator.reveal(id, safe[3]) }
    }

    @Test
    fun refusedAcceptanceAndResignationPersistAndRecoverAfterRestart() = runTest {
        val host = peer(backgroundScope)
        val guest = peer(backgroundScope)
        val id = host.coordinator.invite(guestNode)
        host.deliver(guest, guestContact)
        guest.sender.refused = true
        assertFailsWith<IllegalStateException> { guest.coordinator.respond(id, accept = true) }
        val restored = peer(backgroundScope, guest.store)
        restored.coordinator.retry(id)
        restored.deliver(host, contact)
        assertTrue(assertNotNull(host.coordinator.games.value[id]).canReveal)
        restored.sender.refused = true
        assertFailsWith<IllegalStateException> { restored.coordinator.resign(id) }
        val resigned = peer(backgroundScope, guest.store)
        resigned.coordinator.retry(id)
        resigned.deliver(host, contact)
        assertTrue(assertNotNull(resigned.coordinator.games.value[id]).localResigned)
        assertTrue(assertNotNull(host.coordinator.games.value[id]).opponentResigned)
    }

    private suspend fun start(host: Peer, guest: Peer): String {
        val id = host.coordinator.invite(guestNode)
        host.deliver(guest, guestContact)
        assertNotNull(guest.coordinator.invitations.value[id])
        guest.coordinator.respond(id, accept = true)
        guest.deliver(host, contact)
        assertTrue(assertNotNull(host.coordinator.games.value[id]).canReveal)
        return id
    }

    private fun peer(scope: CoroutineScope, existing: MinesGameStore? = null): Peer {
        val store =
            existing
                ?: run {
                    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "mines-${Uuid.random()}.preferences_pb"
                    files.add(path)
                    MinesGameStore(
                        PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path })
                            .asCorePreferencesDataStore(),
                    )
                }
        val nodes = mock<NodeRepository>(MockMode.autofill)
        every { nodes.ourNodeInfo } returns MutableStateFlow(null)
        val sender = Sender()
        return Peer(
            MinesGameCoordinator(mock<PacketRepository>(MockMode.autofill), nodes, sender, store),
            sender,
            store,
        )
    }

    private class Sender : SendMessageUseCase {
        val queue = mutableListOf<MinesCommand>()
        val sent = mutableListOf<String>()
        var refused = false

        override suspend fun invoke(text: String, contactKey: String, replyId: Int?): SendMessageOutcome {
            if (refused) return SendMessageOutcome.Refused
            sent.add(text)
            queue.add(assertNotNull(MinesProtocol.decode(text)))
            return SendMessageOutcome.Queued(sent.size)
        }

        override suspend fun resend(uuid: Long, contactKey: String): SendMessageOutcome =
            error("Game retries must encode the same logical command")
    }

    private data class Peer(val coordinator: MinesGameCoordinator, val sender: Sender, val store: MinesGameStore) {
        suspend fun deliver(other: Peer, contact: String) {
            val commands = sender.queue.toList()
            sender.queue.clear()
            commands.forEach { other.coordinator.receive(contact, "Opponent", it) }
        }
    }
}
