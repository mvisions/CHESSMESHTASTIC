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

import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeBase64
import org.meshtastic.core.model.ConnectionLifecycle
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.AdminController
import org.meshtastic.core.repository.AdminEditScope
import org.meshtastic.core.repository.ConnectionIdentity
import org.meshtastic.core.repository.ConnectionStateProvider
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChessChannelInstallerTest {
    @Test
    fun addsOnlyOneSecondaryAndPreservesRadioConfiguration() = runTest {
        val fixture = Fixture()
        val primary = settings("primary")
        val existing = settings("friends")
        fixture.configure(listOf(primary, existing))
        val lora = fixture.repository.currentChannelSet.lora_config

        assertEquals(ChessChannelInstallResult.INSTALLED, fixture.install())

        val write = fixture.writes.single()
        assertEquals(2, write.index)
        assertEquals(Channel.Role.SECONDARY, write.role)
        val added = assertNotNull(write.settings)
        assertEquals("chess", added.name)
        assertEquals(1, added.psk.size)
        assertEquals(8, added.psk[0].toInt())
        assertEquals("CA==", added.psk.base64())
        assertFalse(added.uplink_enabled)
        assertFalse(added.downlink_enabled)
        assertEquals(listOf(primary, existing, added), fixture.repository.currentChannelSet.settings)
        assertEquals(lora, fixture.repository.currentChannelSet.lora_config)
        assertEquals(1, fixture.repository.channelSetUpdates.size)
        assertEquals(ChessChannelInstallResult.ALREADY_PRESENT, fixture.install())
        assertEquals(1, fixture.transactions)
    }

    @Test
    fun reusesAnEmptySlotWithoutMovingLaterChannels() = runTest {
        val fixture = Fixture()
        val later = settings("later")
        fixture.configure(listOf(settings("primary"), ChannelSettings.Builder().build(), later))

        fixture.install()

        assertEquals(1, fixture.writes.single().index)
        assertEquals(later, fixture.repository.currentChannelSet.settings[2])
    }

    @Test
    fun existingChannelWithExpandedEquivalentKeyIsNotDuplicated() = runTest {
        val fixture = Fixture()
        val chess = settings("chess")
        val expanded = org.meshtastic.core.model.Channel(chess, Config.LoRaConfig.Builder().build()).psk
        fixture.configure(listOf(settings("primary"), chess.newBuilder().also { it.psk = expanded }.build()))

        assertEquals(ChessChannelInstallResult.ALREADY_PRESENT, fixture.install())
        assertTrue(fixture.writes.isEmpty())
        assertEquals(0, fixture.transactions)
    }

    @Test
    fun fullChannelSetIsNotChanged() = runTest {
        val fixture = Fixture()
        val original = List(8) { settings("channel$it") }
        fixture.configure(original)

        assertEquals(ChessChannelInstallResult.NO_FREE_SLOT, fixture.install())
        assertEquals(original, fixture.repository.currentChannelSet.settings)
        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.repository.channelSetUpdates.isEmpty())
        assertEquals(0, fixture.transactions)
    }

    @Test
    fun conflictingChessKeyIsNeverOverwritten() = runTest {
        val fixture = Fixture()
        val original = listOf(settings("primary"), settings("chess", "AQ=="))
        fixture.configure(original)

        assertEquals(ChessChannelInstallResult.NAME_CONFLICT, fixture.install())
        assertEquals(original, fixture.repository.currentChannelSet.settings)
        assertTrue(fixture.writes.isEmpty())
        assertEquals(0, fixture.transactions)
    }

    @Test
    fun conflictingEncryptionModeIsReportedEvenWithTheSameKey() = runTest {
        val fixture = Fixture()
        val original = listOf(settings("primary"), settings("chess").newBuilder().also { it.use_aead = true }.build())
        fixture.configure(original)

        assertEquals(ChessChannelInstallResult.NAME_CONFLICT, fixture.install())
        assertEquals(original, fixture.repository.currentChannelSet.settings)
        assertEquals(0, fixture.transactions)
    }

    @Test
    fun missingPrimaryChannelDoesNotOpenATransaction() = runTest {
        val fixture = Fixture()

        assertFailsWith<IllegalStateException> { fixture.install() }

        assertTrue(fixture.writes.isEmpty())
        assertEquals(0, fixture.transactions)
    }

    @Test
    fun settingsAreReadAgainAfterBeginRatherThanOverwritingAnOccupiedSlot() = runTest {
        val fixture = Fixture()
        fixture.configure(listOf(settings("primary")))
        val occupied = List(8) { settings("channel$it") }
        fixture.onBegin = { fixture.configure(occupied) }

        assertEquals(ChessChannelInstallResult.NO_FREE_SLOT, fixture.install())
        assertEquals(occupied, fixture.repository.currentChannelSet.settings)
        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.repository.channelSetUpdates.isEmpty())
    }

    @Test
    fun licensedNodeIsNotModified() = runTest {
        val fixture = Fixture(licensed = true)
        fixture.configure(listOf(settings("primary")))

        assertEquals(ChessChannelInstallResult.LICENSED_NODE, fixture.install())
        assertTrue(fixture.writes.isEmpty())
        assertEquals(0, fixture.transactions)
    }

    @Test
    fun failedCommitDoesNotPublishChannelToCache() = runTest {
        val fixture = Fixture()
        val original = listOf(settings("primary"))
        fixture.configure(original)
        fixture.failCommit = true

        assertFailsWith<IllegalStateException> { fixture.install() }

        assertEquals(1, fixture.writes.size)
        assertEquals(original, fixture.repository.currentChannelSet.settings)
        assertTrue(fixture.repository.channelSetUpdates.isEmpty())
    }

    @Test
    fun nodeSwitchDuringBeginDoesNotWriteAChannel() = runTest {
        val fixture = Fixture()
        fixture.configure(listOf(settings("primary")))
        fixture.onBegin = { fixture.identity.value = fixture.identity.value!!.copy(sessionGeneration = 2) }

        assertFailsWith<IllegalStateException> { fixture.install() }

        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.repository.channelSetUpdates.isEmpty())
    }

    @Test
    fun disconnectDuringCommitDoesNotUpdateAnotherDevicesCache() = runTest {
        val fixture = Fixture()
        fixture.configure(listOf(settings("primary")))
        fixture.onCommit = { fixture.lifecycle.value = ConnectionLifecycle(version = 2) }

        assertFailsWith<IllegalStateException> { fixture.install() }

        assertEquals(1, fixture.writes.size)
        assertTrue(fixture.repository.channelSetUpdates.isEmpty())
    }

    @Test
    fun readinessIsRequiredBeforeAnyRadioTransaction() = runTest {
        val fixture = Fixture()
        fixture.configure(listOf(settings("primary")))
        fixture.ready.value = false

        assertFailsWith<IllegalStateException> { fixture.install() }

        assertEquals(0, fixture.transactions)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun observerWaitsForHandshakeAndDoesNotRewriteOnReconnect() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.configure(listOf(settings("primary")))
        fixture.ready.value = false
        val results = mutableListOf<ChessChannelInstallResult>()
        backgroundScope.launch { fixture.installer.observe { results += it } }
        assertTrue(fixture.writes.isEmpty())

        fixture.ready.value = true
        assertEquals(listOf(ChessChannelInstallResult.INSTALLED), results)
        fixture.ready.value = false
        fixture.ready.value = true
        assertEquals(1, fixture.writes.size)
        assertEquals(1, results.size)

        fixture.lifecycle.value = ConnectionLifecycle(version = 2)
        fixture.lifecycle.value = ConnectionLifecycle(version = 3, state = ConnectionState.Connected)
        assertEquals(ChessChannelInstallResult.ALREADY_PRESENT, results.last())
        assertEquals(1, fixture.writes.size)
    }

    @Test
    fun observerReportsFailureAndRetriesOnNewConnection() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.configure(listOf(settings("primary")))
        fixture.failCommit = true
        val results = mutableListOf<ChessChannelInstallResult>()
        backgroundScope.launch { fixture.installer.observe { results += it } }
        assertEquals(listOf(ChessChannelInstallResult.FAILED), results)
        assertTrue(fixture.repository.channelSetUpdates.isEmpty())

        fixture.failCommit = false
        fixture.lifecycle.value = ConnectionLifecycle(version = 2)
        fixture.lifecycle.value = ConnectionLifecycle(version = 3, state = ConnectionState.Connected)
        assertEquals(listOf(ChessChannelInstallResult.FAILED, ChessChannelInstallResult.INSTALLED), results)
        assertEquals(2, fixture.transactions)
    }

    private class Fixture(licensed: Boolean = false) {
        val repository = FakeRadioConfigRepository()
        val ready = MutableStateFlow(true)
        val identity = MutableStateFlow<ConnectionIdentity?>(ConnectionIdentity(1, "test", 42, null))
        val lifecycle = MutableStateFlow(ConnectionLifecycle(version = 1, state = ConnectionState.Connected))
        val writes = mutableListOf<Channel>()
        var transactions = 0
        var failCommit = false
        var onBegin: suspend () -> Unit = {}
        var onCommit: () -> Unit = {}
        private val nodeManager = mock<NodeManager>(MockMode.autofill)
        private val connectionStateProvider = mock<ConnectionStateProvider>(MockMode.autofill)
        private val adminController = mock<AdminController>(MockMode.autofill)
        private val editScope = mock<AdminEditScope>(MockMode.autofill)
        val installer = ChessChannelInstaller(adminController, repository, nodeManager, connectionStateProvider)

        init {
            every { nodeManager.isNodeDbReady } returns ready
            every { nodeManager.connectionIdentity } returns identity
            every { nodeManager.myNodeNum } returns MutableStateFlow(42)
            every { nodeManager.nodeDBbyNodeNum } returns
                mapOf(42 to Node(42, user = User.Builder().also { it.is_licensed = licensed }.build()))
            every { connectionStateProvider.connectionLifecycle } returns lifecycle
            everySuspend { editScope.setChannel(any()) } calls { writes += it.arg<Channel>(0) }
            everySuspend { adminController.editSettings(42, any()) } calls
                { call ->
                    transactions++
                    onBegin()
                    call.arg<suspend AdminEditScope.() -> Unit>(1).invoke(editScope)
                    onCommit()
                    check(!failCommit) { "Test commit failure" }
                }
        }

        suspend fun configure(settings: List<ChannelSettings>) {
            val lora = Config.LoRaConfig.Builder().also { it.region = Config.LoRaConfig.RegionCode.EU_868 }.build()
            repository.updateChannelSet(settings, lora)
            repository.channelSetUpdates.clear()
        }

        suspend fun install(): ChessChannelInstallResult =
            installer.install(assertNotNull(identity.value), lifecycle.value)
    }

    private companion object {
        fun settings(name: String, psk: String = "CH=="): ChannelSettings = ChannelSettings.Builder()
            .also {
                it.name = name
                it.psk = assertNotNull(psk.decodeBase64())
            }
            .build()
    }
}
