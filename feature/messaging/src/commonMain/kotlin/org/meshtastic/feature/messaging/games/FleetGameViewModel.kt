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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.errorEventFlow
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.messaging.MessagingUiTextResolver

@KoinViewModel
class FleetGameViewModel(private val coordinator: FleetGameCoordinator, private val snackbarManager: SnackbarManager) :
    ViewModel() {
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
    }

    fun setGameId(id: String) {
        gameId.value = id
    }

    fun directMessageContactKey(node: Node): String = coordinator.directMessageContactKey(node)

    fun invite(node: Node, onInvited: (String) -> Unit) {
        safeLaunch(errorEvents = errorEvents, tag = "fleetInvite") {
            onInvited(coordinator.invite(node, node.effectiveLongName))
        }
    }

    fun respondToInvitation(gameId: String, accept: Boolean, onAccepted: (String) -> Unit = {}) {
        safeLaunch(errorEvents = errorEvents, tag = "fleetInvitationResponse") {
            val responded = if (accept) coordinator.acceptInvitation(gameId) else coordinator.declineInvitation(gameId)
            if (responded && accept) onAccepted(gameId)
        }
    }

    fun fireShot(gameId: String, cell: Int) {
        safeLaunch(errorEvents = errorEvents, tag = "fleetShot") { coordinator.fireShot(gameId, cell) }
    }

    fun resign(gameId: String) {
        safeLaunch(errorEvents = errorEvents, tag = "fleetResign") { coordinator.resign(gameId) }
    }

    fun reportDefeat(gameId: String) {
        safeLaunch(errorEvents = errorEvents, tag = "fleetReportDefeat") { coordinator.reportDefeat(gameId) }
    }
}
