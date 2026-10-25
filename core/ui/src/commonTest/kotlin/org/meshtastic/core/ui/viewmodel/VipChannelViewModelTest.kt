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
package org.meshtastic.core.ui.viewmodel

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.resources.UiText
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.LocalConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VipChannelViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val radio = FakeRadioController()
    private val config = FakeRadioConfigRepository()
    private val nodes = FakeNodeRepository()
    private lateinit var viewModel: VipChannelViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        nodes.setMyNodeInfo(TestDataFactory.createMyNodeInfo())
        setChannels(channel("Main"))
        viewModel = VipChannelViewModel(radio, config, nodes)
        store.put("vip", viewModel)
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun asksOnlyAfterConnectionAndDoesNotWriteWithoutConsent() = runTest {
        advanceUntilIdle()
        assertFalse(viewModel.showPrompt.value)
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        assertTrue(viewModel.showPrompt.value)
        assertTrue(radio.channelWrites.isEmpty())
    }

    @Test
    fun acceptingWritesOnlyOneSecondaryWithExactNameAndKey() = runTest {
        setChannels(channel("Main"), channel("Other"))
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        viewModel.join()
        viewModel.join()
        advanceUntilIdle()

        val written = radio.localChannels.single()
        assertEquals(2, written.index)
        assertEquals(Channel.Role.SECONDARY, written.role)
        assertEquals("VIP", written.settings?.name)
        assertEquals(byteArrayOf(0x54).toByteString(), written.settings?.psk)
        assertEquals("VA==", written.settings?.psk?.base64())
        assertTrue(radio.configWrites.isEmpty())
        assertFalse(viewModel.showPrompt.value)
        assertEquals(listOf("Main", "Other"), config.currentChannelSet.settings.map { it.name })
    }

    @Test
    fun existingVipSecondaryDoesNotPromptOrOverwriteItsKey() = runTest {
        setChannels(channel("Main"), channel("VIP"))
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        assertFalse(viewModel.showPrompt.value)
        viewModel.join()
        advanceUntilIdle()
        assertTrue(radio.channelWrites.isEmpty())
    }

    @Test
    fun waitsForNodeInformationAndChannelSync() = runTest {
        setChannels()
        nodes.setMyNodeInfo(null)
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        assertFalse(viewModel.showPrompt.value)
        nodes.setMyNodeInfo(TestDataFactory.createMyNodeInfo())
        advanceUntilIdle()
        assertFalse(viewModel.showPrompt.value)
        setChannels(channel("Main"))
        advanceUntilIdle()
        assertTrue(viewModel.showPrompt.value)
    }

    @Test
    fun channelAddedWhilePromptIsOpenIsNotDuplicated() = runTest {
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        setChannels(channel("Main"), channel("VIP"))
        viewModel.join()
        advanceUntilIdle()
        assertTrue(radio.channelWrites.isEmpty())
        assertFalse(viewModel.showPrompt.value)
    }

    @Test
    fun decliningDoesNotAskAgainUntilNextConnection() = runTest {
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        viewModel.dismiss()
        setChannels(channel("Main"), channel("Other"))
        advanceUntilIdle()
        assertFalse(viewModel.showPrompt.value)
        assertTrue(radio.channelWrites.isEmpty())
        radio.setConnectionState(ConnectionState.Disconnected)
        advanceUntilIdle()
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        assertTrue(viewModel.showPrompt.value)
    }

    @Test
    fun disconnectedConfirmationCannotWriteToDevice() = runTest {
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        radio.setConnectionState(ConnectionState.Disconnected)
        viewModel.join()
        advanceUntilIdle()
        assertTrue(radio.channelWrites.isEmpty())
        assertFalse(viewModel.showPrompt.value)
    }

    @Test
    fun replacedTransportCannotUseOldConsent() = runTest {
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        radio.setSessionGeneration(2)
        viewModel.join()
        advanceUntilIdle()
        assertTrue(radio.channelWrites.isEmpty())
    }

    @Test
    fun rechecksChannelsAtConfirmation() = runTest {
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        setChannels(channel("Main"), channel("Other"))
        viewModel.join()
        advanceUntilIdle()
        assertEquals(2, radio.localChannels.single().index)
    }

    @Test
    fun managedDeviceDoesNotPrompt() = runTest {
        config.setLocalConfigDirect(
            LocalConfig.Builder()
                .also {
                    it.security =
                        Config.SecurityConfig.Builder().also { security -> security.is_managed = true }.build()
                }
                .build(),
        )
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        assertFalse(viewModel.showPrompt.value)
    }

    @Test
    fun noFreeSlotSurfacesErrorAndPreservesAllChannels() = runTest {
        setChannels(*Array(8) { channel("Channel$it") })
        val errors = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errors.collect { errors += it }
        }
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        viewModel.join()
        advanceUntilIdle()
        assertEquals(1, errors.size)
        assertTrue(radio.channelWrites.isEmpty())
        assertEquals(8, config.currentChannelSet.settings.size)
    }

    @Test
    fun radioRejectionSurfacesErrorWithoutClaimingSuccess() = runTest {
        val errors = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errors.collect { errors += it }
        }
        radio.rejectLocalChannelWritesRemaining = 1
        radio.setConnectionState(ConnectionState.Connected)
        advanceUntilIdle()
        viewModel.join()
        advanceUntilIdle()
        assertEquals(1, errors.size)
        assertTrue(radio.channelWrites.isEmpty())
    }

    @Test
    fun fillsEmptySecondarySlotWithoutMovingOtherChannels() {
        val primary = channel("Main")
        val other = channel("Other")
        val settings = listOf(primary, ChannelSettings.Builder().build(), other)
        assertEquals(1, assertNotNull(vipChannel(settings, 8)).index)
        assertEquals(listOf(primary, ChannelSettings.Builder().build(), other), settings)
    }

    @Test
    fun respectsDeviceCapacityAndRequiresPrimary() {
        assertNull(vipChannel(emptyList(), 8))
        assertNull(vipChannel(listOf(channel("Main")), 1))
        assertNull(vipChannel(listOf(channel("Main"), channel("Other")), 2))
        assertEquals(1, assertNotNull(vipChannel(listOf(channel("Main")), 2)).index)
    }

    private fun setChannels(vararg channels: ChannelSettings) {
        config.setChannelSet(ChannelSet.Builder().also { it.settings = channels.toList() }.build())
    }

    private fun channel(name: String): ChannelSettings = ChannelSettings.Builder()
        .also {
            it.name = name
            it.psk = byteArrayOf(1).toByteString()
        }
        .build()
}
