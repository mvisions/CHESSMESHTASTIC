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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.chess_accept
import org.meshtastic.core.resources.chess_decline
import org.meshtastic.core.resources.chess_invitation_message
import org.meshtastic.core.resources.chess_invitation_title

@Composable
fun ChessInvitationHost(onOpenGame: (String) -> Unit) {
    val coordinator = koinInject<ChessGameCoordinator>()
    val viewModel = koinViewModel<ChessGameViewModel>()
    val invitations by viewModel.invitations.collectAsStateWithLifecycle()
    val invitation = invitations.values.firstOrNull()

    LaunchedEffect(coordinator) { coordinator.observeDirectMessages() }

    invitation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.respondToInvitation(pending.gameId, accept = false) },
            title = { Text(stringResource(Res.string.chess_invitation_title)) },
            text = { Text(stringResource(Res.string.chess_invitation_message, pending.opponentName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.respondToInvitation(pending.gameId, accept = true, onAccepted = onOpenGame)
                    },
                ) {
                    Text(stringResource(Res.string.chess_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondToInvitation(pending.gameId, accept = false) }) {
                    Text(stringResource(Res.string.chess_decline))
                }
            },
        )
    }
}
