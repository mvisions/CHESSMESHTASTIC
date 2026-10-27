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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.UiText
import org.meshtastic.core.resources.chess_channel_conflict
import org.meshtastic.core.resources.chess_channel_failed
import org.meshtastic.core.resources.chess_channel_full
import org.meshtastic.core.resources.chess_channel_installed
import org.meshtastic.core.resources.chess_channel_licensed
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.errorEventFlow
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.messaging.MessagingUiTextResolver

@KoinViewModel
class ChessGameViewModel(
    private val coordinator: ChessGameCoordinator,
    private val snackbarManager: SnackbarManager,
    private val channelInstaller: ChessChannelInstaller,
) : ViewModel() {
    private val gameId = MutableStateFlow<String?>(null)
    private val errorEvents = errorEventFlow()

    val invitations = coordinator.invitations
    val game =
        combine(coordinator.games, gameId) { games, id -> id?.let(games::get) }
            .stateInWhileSubscribed(initialValue = null)

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            errorEvents.collect { snackbarManager.showSnackbar(MessagingUiTextResolver.resolve(it)) }
        }
        safeLaunch(errorEvents = errorEvents, tag = "chessChannel") {
            channelInstaller.observe { result ->
                val resource =
                    when (result) {
                        ChessChannelInstallResult.INSTALLED -> Res.string.chess_channel_installed
                        ChessChannelInstallResult.NAME_CONFLICT -> Res.string.chess_channel_conflict
                        ChessChannelInstallResult.NO_FREE_SLOT -> Res.string.chess_channel_full
                        ChessChannelInstallResult.LICENSED_NODE -> Res.string.chess_channel_licensed
                        ChessChannelInstallResult.FAILED -> Res.string.chess_channel_failed
                        ChessChannelInstallResult.ALREADY_PRESENT -> null
                    }
                resource?.let {
                    snackbarManager.showSnackbar(MessagingUiTextResolver.resolve(UiText.Resource(it)))
                }
            }
        }
    }

    fun setGameId(id: String) {
        gameId.value = id
    }

    fun directMessageContactKey(node: Node): String = coordinator.directMessageContactKey(node)

    fun invite(node: Node, onInvited: (String) -> Unit) {
        safeLaunch(errorEvents = errorEvents, tag = "chessInvite") {
            val id = coordinator.invite(node, node.effectiveLongName)
            onInvited(id)
        }
    }

    fun respondToInvitation(gameId: String, accept: Boolean, onAccepted: (String) -> Unit = {}) {
        safeLaunch(errorEvents = errorEvents, tag = "chessInvitationResponse") {
            val responded =
                if (accept) {
                    coordinator.acceptInvitation(gameId)
                } else {
                    coordinator.declineInvitation(gameId)
                }
            if (responded && accept) onAccepted(gameId)
        }
    }

    fun playMove(gameId: String, move: ChessMove) =
        safeLaunch(errorEvents = errorEvents, tag = "chessMove") { coordinator.playMove(gameId, move) }

    fun resign(gameId: String) =
        safeLaunch(errorEvents = errorEvents, tag = "chessResign") { coordinator.resign(gameId) }

    fun offerDraw(gameId: String) =
        safeLaunch(errorEvents = errorEvents, tag = "chessOfferDraw") { coordinator.offerDraw(gameId) }

    fun respondToDraw(gameId: String, accept: Boolean) =
        safeLaunch(errorEvents = errorEvents, tag = "chessRespondToDraw") {
            coordinator.respondToDraw(gameId, accept)
        }

    fun claimDraw(gameId: String, outcome: ClaimableDraw) =
        safeLaunch(errorEvents = errorEvents, tag = "chessClaimDraw") { coordinator.claimDraw(gameId, outcome) }

    fun checkTurnTimeout(gameId: String, now: Long) {
        safeLaunch(errorEvents = errorEvents, tag = "chessTurnTimeout") {
            coordinator.checkTurnTimeout(gameId, now)
        }
    }
}
