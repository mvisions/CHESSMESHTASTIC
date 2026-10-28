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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MinesInvitationHost(onOpenGame: (String) -> Unit) {
    val viewModel = koinViewModel<MinesGameViewModel>()
    val invitations by viewModel.invitations.collectAsStateWithLifecycle()
    val openGame by rememberUpdatedState(onOpenGame)
    val invitation = invitations.values.firstOrNull()

    SideEffect(Unit) { viewModel.observeDirectMessages() }
    SideEffect(invitation?.gameId) {
        invitation?.let { viewModel.showInvitation(it) { gameId -> openGame(gameId) } }
    }
}
