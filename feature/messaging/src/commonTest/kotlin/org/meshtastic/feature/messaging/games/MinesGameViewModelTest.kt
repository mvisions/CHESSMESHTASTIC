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
import androidx.lifecycle.ViewModelStore
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.FileSystem
import okio.Path
import org.meshtastic.core.datastore.di.asCorePreferencesDataStore
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.usecase.SendMessageOutcome
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.core.ui.util.AlertManager
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.feature.messaging.MessagingUiTextResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class MinesGameViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModelStore = ViewModelStore()
    private val alerts = AlertManager()
    private val files = mutableListOf<Path>()
    private val originalResolver = MessagingUiTextResolver.resolve

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        MessagingUiTextResolver.resolve = { "Minesweeper message failed" }
    }

    @AfterTest
    fun tearDown() {
        viewModelStore.clear()
        files.forEach { FileSystem.SYSTEM.delete(it, mustExist = false) }
        MessagingUiTextResolver.resolve = originalResolver
        Dispatchers.resetMain()
    }

    @Test
    fun refusedAcceptanceOpensGameAndLeavesRetryAvailable() = runTest(dispatcher) {
        val scope = backgroundScope
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "mines-vm-${Uuid.random()}.preferences_pb"
        files.add(path)
        val store =
            MinesGameStore(
                PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path })
                    .asCorePreferencesDataStore(),
            )
        val nodes = mock<NodeRepository>(MockMode.autofill)
        every { nodes.ourNodeInfo } returns MutableStateFlow(null)
        val sender = Sender()
        val coordinator = MinesGameCoordinator(mock<PacketRepository>(MockMode.autofill), nodes, sender, store)
        val id = "0000000000000001"
        coordinator.receive("0!11111111", "Host", MinesCommand.Invite(id))
        val invitation = assertNotNull(coordinator.invitations.value[id])
        val viewModel = MinesGameViewModel(coordinator, SnackbarManager(), alerts)
        viewModelStore.put("mines", viewModel)
        var openedGameId: String? = null

        sender.refused = true
        viewModel.acceptInvitation(invitation) { openedGameId = it }

        assertEquals(id, openedGameId)
        assertEquals(MinesPhase.PLAYING, coordinator.games.value[id]?.phase)
        sender.refused = false
        viewModel.retry(id)
        assertTrue(sender.queue.any { it is MinesCommand.Accept })
        assertTrue(sender.queue.any { it is MinesCommand.Sync })
    }

    private class Sender : SendMessageUseCase {
        val queue = mutableListOf<MinesCommand>()
        var refused = false

        override suspend fun invoke(text: String, contactKey: String, replyId: Int?): SendMessageOutcome {
            if (refused) return SendMessageOutcome.Refused
            queue.add(assertNotNull(MinesProtocol.decode(text)))
            return SendMessageOutcome.Queued(queue.size)
        }

        override suspend fun resend(uuid: Long, contactKey: String): SendMessageOutcome =
            error("Game retries must encode the same logical command")
    }
}
