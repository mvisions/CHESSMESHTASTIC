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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.safeCatching
import org.meshtastic.core.model.ConnectionLifecycle
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.util.CHANNEL_REPLACEMENT_SLOT_COUNT
import org.meshtastic.core.model.util.channelIdentity
import org.meshtastic.core.model.util.isChannelPlaceholder
import org.meshtastic.core.repository.AdminController
import org.meshtastic.core.repository.ConnectionIdentity
import org.meshtastic.core.repository.ConnectionStateProvider
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

internal enum class ChessChannelInstallResult {
    INSTALLED,
    ALREADY_PRESENT,
    NAME_CONFLICT,
    NO_FREE_SLOT,
    LICENSED_NODE,
    FAILED,
}

/** Adds only the public chess discovery channel; gameplay remains in encrypted direct messages. */
@Single
class ChessChannelInstaller(
    private val adminController: AdminController,
    private val radioConfigRepository: RadioConfigRepository,
    private val nodeManager: NodeManager,
    private val connectionStateProvider: ConnectionStateProvider,
) {
    private val mutex = Mutex()
    private var lastAttempt: Pair<ConnectionIdentity, Long>? = null

    internal suspend fun observe(onResult: suspend (ChessChannelInstallResult) -> Unit) {
        combine(
            connectionStateProvider.connectionLifecycle,
            nodeManager.isNodeDbReady,
            nodeManager.connectionIdentity,
        ) { lifecycle, ready, identity ->
            if (lifecycle.state == ConnectionState.Connected && ready && identity != null) {
                identity to lifecycle
            } else {
                null
            }
        }
            .distinctUntilChanged()
            .collect { connection ->
                if (connection != null) {
                    mutex.withLock {
                        val (identity, lifecycle) = connection
                        val attempt = identity to lifecycle.version
                        if (lastAttempt != attempt) {
                            lastAttempt = attempt
                            val result = safeCatching {
                                install(identity, lifecycle)
                            }
                                .getOrElse {
                                    Logger.w { "Chess discovery channel installation failed; reconnect to retry" }
                                    ChessChannelInstallResult.FAILED
                                }
                            onResult(result)
                        }
                    }
                }
            }
    }

    internal suspend fun install(
        identity: ConnectionIdentity,
        lifecycle: ConnectionLifecycle,
    ): ChessChannelInstallResult {
        requireCurrentConnection(identity, lifecycle)
        val localNode = checkNotNull(nodeManager.nodeDBbyNodeNum[identity.nodeNum]) { "Local node unavailable" }
        val initial =
            if (localNode.user.is_licensed) {
                ChannelPlan(ChessChannelInstallResult.LICENSED_NODE)
            } else {
                plan()
            }
        if (initial.channel == null) return initial.result
        var committedSettings: List<ChannelSettings>? = null
        var result = initial.result
        adminController.editSettings(identity.nodeNum) {
            requireCurrentConnection(identity, lifecycle)
            // Read again after the begin boundary, which suspends while other settings may change.
            val current = plan()
            result = current.result
            current.channel?.let { channel ->
                requireCurrentConnection(identity, lifecycle)
                setChannel(channel)
                committedSettings =
                    current.settings.toMutableList().apply {
                        while (size <= channel.index) add(ChannelSettings.Builder().build())
                        this[channel.index] = checkNotNull(channel.settings)
                    }
            }
        }
        committedSettings?.let { settings ->
            withContext(NonCancellable) {
                requireCurrentConnection(identity, lifecycle)
                radioConfigRepository.updateChannelSet(settingsList = settings, loraConfig = null)
            }
        }
        return result
    }

    private suspend fun plan(): ChannelPlan {
        val channelSet = radioConfigRepository.channelSetFlow.first()
        val settings = channelSet.settings
        check(settings.isNotEmpty() && !settings.first().isChannelPlaceholder()) {
            "Cannot add the chess channel without a configured primary channel"
        }
        val loraConfig = channelSet.lora_config ?: Config.LoRaConfig.Builder().build()
        val expected = chessSettings.channelIdentity(loraConfig)
        val index =
            (1 until CHANNEL_REPLACEMENT_SLOT_COUNT).firstOrNull {
                settings.getOrNull(it)?.isChannelPlaceholder() != false
            }
        return when {
            settings.any {
                it.channelIdentity(loraConfig) == expected && it.use_aead == chessSettings.use_aead
            } -> ChannelPlan(ChessChannelInstallResult.ALREADY_PRESENT)

            settings.any { it.name.equals(chessSettings.name, ignoreCase = true) } ->
                ChannelPlan(ChessChannelInstallResult.NAME_CONFLICT)

            index == null -> ChannelPlan(ChessChannelInstallResult.NO_FREE_SLOT)

            else ->
                ChannelPlan(
                    ChessChannelInstallResult.INSTALLED,
                    Channel.Builder()
                        .also {
                            it.index = index
                            it.role = Channel.Role.SECONDARY
                            it.settings = chessSettings
                        }
                        .build(),
                    settings,
                )
        }
    }

    private fun requireCurrentConnection(identity: ConnectionIdentity, lifecycle: ConnectionLifecycle) {
        check(
            lifecycle.state == ConnectionState.Connected &&
                connectionStateProvider.connectionLifecycle.value == lifecycle &&
                nodeManager.isNodeDbReady.value &&
                nodeManager.connectionIdentity.value == identity &&
                nodeManager.myNodeNum.value == identity.nodeNum,
        ) {
            "The radio connection changed during chess channel installation"
        }
    }

    private data class ChannelPlan(
        val result: ChessChannelInstallResult,
        val channel: Channel? = null,
        val settings: List<ChannelSettings> = emptyList(),
    )

    private companion object {
        // A public one-byte firmware key selector, not a private encryption key.
        val chessSettings: ChannelSettings =
            ChannelSettings.Builder()
                .also {
                    it.name = "chess"
                    it.psk = checkNotNull("CH==".decodeBase64())
                    it.uplink_enabled = false
                    it.downlink_enabled = false
                }
                .build()
    }
}
