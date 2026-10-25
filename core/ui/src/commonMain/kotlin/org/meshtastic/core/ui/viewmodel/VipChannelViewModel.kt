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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okio.ByteString.Companion.decodeBase64
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.util.isChannelPlaceholder
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.UiText
import org.meshtastic.core.resources.vip_channel_connection_changed
import org.meshtastic.core.resources.vip_channel_no_slots
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings

internal const val VIP_CHANNEL_NAME = "VIP"

@KoinViewModel
class VipChannelViewModel(
    private val radioController: RadioController,
    private val radioConfigRepository: RadioConfigRepository,
    private val nodeRepository: NodeRepository,
) : ViewModel() {
    private data class Session(val generation: Long, val handshake: Long, val nodeNum: Int)

    private val _showPrompt = MutableStateFlow(false)
    val showPrompt = _showPrompt.asStateFlow()
    private val _errors = errorEventFlow()
    val errors = _errors.asSharedFlow()
    private var offeredSession: Session? = null
    private var handledSession: Session? = null

    init {
        combine(
            radioController.connectionLifecycle,
            radioController.sessionGeneration,
            nodeRepository.myNodeInfo,
            radioConfigRepository.channelSetFlow,
            radioConfigRepository.localConfigFlow,
        ) { lifecycle, generation, info, channels, config ->
            val eligibleChannels = channels.settings.isNotEmpty() && !channels.settings.hasVipSecondary()
            if (
                lifecycle.state == ConnectionState.Connected &&
                info != null &&
                eligibleChannels &&
                config.security?.is_managed != true
            ) {
                Session(generation, lifecycle.epochs.completedHandshakes, info.myNodeNum)
            } else {
                null
            }
        }
            .onEach { session ->
                offeredSession = session
                _showPrompt.value = session != null && session != handledSession
            }
            .launchIn(viewModelScope)
    }

    fun dismiss() {
        handledSession = offeredSession
        _showPrompt.value = false
    }

    fun join() {
        val session = offeredSession ?: return
        if (!_showPrompt.value) return
        dismiss()
        safeLaunch(errorEvents = _errors, tag = "joinVipChannel") {
            val channels = radioConfigRepository.channelSetFlow.first().settings
            val config = radioConfigRepository.localConfigFlow.first()
            val lifecycle = radioController.connectionLifecycle.value
            val sessionChanged =
                lifecycle.state != ConnectionState.Connected ||
                    radioController.sessionGeneration.value != session.generation ||
                    lifecycle.epochs.completedHandshakes != session.handshake ||
                    nodeRepository.myNodeInfo.value?.myNodeNum != session.nodeNum
            if (sessionChanged || config.security?.is_managed == true) {
                Logger.w { "VIP channel join rejected: connection or management state changed" }
                _errors.tryEmit(UiText.Resource(Res.string.vip_channel_connection_changed))
                return@safeLaunch
            }
            if (channels.hasVipSecondary()) return@safeLaunch
            val maxChannels = nodeRepository.myNodeInfo.value?.maxChannels ?: 0
            val channel = vipChannel(channels, maxChannels)
            if (channel == null) {
                Logger.w { "VIP channel join rejected: no secondary channel slot available" }
                _errors.tryEmit(UiText.Resource(Res.string.vip_channel_no_slots))
                return@safeLaunch
            }
            radioController.setLocalChannel(channel)
        }
    }
}

private fun List<ChannelSettings>.hasVipSecondary(): Boolean = drop(1).any { it.name == VIP_CHANNEL_NAME }

internal fun vipChannel(settings: List<ChannelSettings>, maxChannels: Int): Channel? {
    if (settings.isEmpty() || settings.hasVipSecondary()) return null
    val index =
        (1 until maxChannels).firstOrNull { slot ->
            settings.getOrNull(slot)?.isChannelPlaceholder() != false
        }
    return index?.let { slot ->
        val vipSettings =
            ChannelSettings.Builder()
                .also { builder ->
                    builder.name = VIP_CHANNEL_NAME
                    builder.psk = checkNotNull("VP==".decodeBase64())
                }
                .build()
        Channel.Builder()
            .also { channel ->
                channel.index = slot
                channel.role = Channel.Role.SECONDARY
                channel.settings = vipSettings
            }
            .build()
    }
}
