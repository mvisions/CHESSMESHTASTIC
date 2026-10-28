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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.model.Node
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.accept
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.fleet_decline
import org.meshtastic.core.resources.fleet_resign
import org.meshtastic.core.resources.getStringSuspend
import org.meshtastic.core.resources.mines_invitation_message
import org.meshtastic.core.resources.mines_invitation_title
import org.meshtastic.core.resources.mines_resign_confirmation
import org.meshtastic.core.ui.util.AlertManager
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.errorEventFlow
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.messaging.MessagingUiTextResolver

@KoinViewModel
class MinesGameViewModel(
    private val coordinator: MinesGameCoordinator,
    private val snackbarManager: SnackbarManager,
    private val alertManager: AlertManager,
) : ViewModel() {
    private val gameId = MutableStateFlow<String?>(null)
    private val errors = errorEventFlow()
    private var observer: Job? = null
    private var ownedAlert: AlertManager.AlertData? = null
    val invitations = coordinator.invitations
    val game = combine(coordinator.games, gameId) { games, id -> games[id] }.stateInWhileSubscribed(initialValue = null)

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            errors.collect { snackbarManager.showSnackbar(MessagingUiTextResolver.resolve(it)) }
        }
    }

    fun setGameId(id: String) {
        gameId.value = id
    }

    fun observeDirectMessages() {
        if (observer?.isActive == true) return
        observer = safeLaunch(errorEvents = errors, tag = "minesObserver") { coordinator.observeDirectMessages() }
    }

    fun invite(node: Node, onInvited: (String) -> Unit) {
        safeLaunch(errorEvents = errors, tag = "minesInvite") { onInvited(coordinator.invite(node)) }
    }

    fun reveal(gameId: String, cell: Int) {
        safeLaunch(errorEvents = errors, tag = "minesReveal") { coordinator.reveal(gameId, cell) }
    }

    fun retry(gameId: String) {
        coordinator.invitations.value[gameId]?.let {
            showInvitation(it) {}
            return
        }
        safeLaunch(errorEvents = errors, tag = "minesRetry") { coordinator.retry(gameId) }
    }

    fun showInvitation(invitation: MinesInvitation, onOpenGame: (String) -> Unit) {
        safeLaunch(errorEvents = errors, tag = "minesInvitation") {
            alertManager.currentAlert.first { it == null }
            if (coordinator.invitations.value[invitation.gameId] != invitation) return@safeLaunch
            alertManager.showAlert(
                titleRes = Res.string.mines_invitation_title,
                message = getStringSuspend(Res.string.mines_invitation_message, invitation.opponentName),
                confirmTextRes = Res.string.accept,
                dismissTextRes = Res.string.fleet_decline,
                onConfirm = { acceptInvitation(invitation, onOpenGame) },
                onDismiss = {
                    safeLaunch(errorEvents = errors, tag = "minesDecline") {
                        coordinator.respond(invitation.gameId, accept = false)
                    }
                },
            )
            ownedAlert = alertManager.currentAlert.value
        }
    }

    internal fun acceptInvitation(invitation: MinesInvitation, onOpenGame: (String) -> Unit) {
        onOpenGame(invitation.gameId)
        safeLaunch(errorEvents = errors, tag = "minesAccept") {
            coordinator.respond(invitation.gameId, accept = true)
        }
    }

    fun resign(gameId: String) {
        if (alertManager.currentAlert.value != null) return
        alertManager.showAlert(
            messageRes = Res.string.mines_resign_confirmation,
            confirmTextRes = Res.string.fleet_resign,
            dismissTextRes = Res.string.cancel,
            onConfirm = {
                safeLaunch(errorEvents = errors, tag = "minesResign") { coordinator.resign(gameId) }
            },
        )
        ownedAlert = alertManager.currentAlert.value
    }

    override fun onCleared() {
        if (ownedAlert != null && alertManager.currentAlert.value === ownedAlert) alertManager.dismissAlert()
        super.onCleared()
    }
}
