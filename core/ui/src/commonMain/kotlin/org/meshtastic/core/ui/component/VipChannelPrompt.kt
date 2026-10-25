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
package org.meshtastic.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.accept
import org.meshtastic.core.resources.not_now
import org.meshtastic.core.resources.vip_channel_join_message
import org.meshtastic.core.resources.vip_channel_join_title
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.VipChannelViewModel

@Composable
fun VipChannelPrompt(
    viewModel: VipChannelViewModel = koinViewModel(),
    snackbarManager: SnackbarManager = koinInject(),
) {
    val showPrompt by viewModel.showPrompt.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, snackbarManager) {
        viewModel.errors.collect { snackbarManager.showSnackbar(it.resolve()) }
    }
    if (showPrompt) {
        MeshtasticDialog(
            onDismiss = viewModel::dismiss,
            titleRes = Res.string.vip_channel_join_title,
            messageRes = Res.string.vip_channel_join_message,
            confirmTextRes = Res.string.accept,
            dismissTextRes = Res.string.not_now,
            onConfirm = viewModel::join,
        )
    }
}
