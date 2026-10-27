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
package org.meshtastic.feature.settings.component

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_installed_version
import org.meshtastic.core.resources.app_update_available
import org.meshtastic.core.resources.app_update_check
import org.meshtastic.core.resources.app_update_checking
import org.meshtastic.core.resources.app_update_current
import org.meshtastic.core.resources.app_update_error
import org.meshtastic.core.resources.app_update_opening
import org.meshtastic.core.ui.component.ListItem
import org.meshtastic.core.ui.theme.StatusColors.StatusGreen

internal class AppUpdateState {
    var checking by mutableStateOf(false)
        private set

    var opening by mutableStateOf(false)
        private set

    var availableUrl by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf(Res.string.app_update_checking)
        private set

    private var leftApp = false

    @Suppress("TooGenericExceptionCaught")
    suspend fun check(findUpdate: suspend () -> String?) {
        if (checking || opening) return
        checking = true
        availableUrl = null
        status = Res.string.app_update_checking
        try {
            availableUrl = findUpdate()
            status = if (availableUrl == null) Res.string.app_update_current else Res.string.app_update_available
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(error) { "Unable to check application update" }
            status = Res.string.app_update_error
        } finally {
            checking = false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun open(onOpen: (String) -> Unit) {
        val url = availableUrl ?: return
        if (checking || opening) return
        opening = true
        status = Res.string.app_update_opening
        try {
            onOpen(url)
        } catch (cancelled: CancellationException) {
            opening = false
            throw cancelled
        } catch (error: Exception) {
            opening = false
            Logger.w(error) { "Unable to open application update" }
            status = Res.string.app_update_error
        }
    }

    fun onPause() {
        if (opening) leftApp = true
    }

    fun onResume() {
        if (!leftApp) return
        leftApp = false
        opening = false
        status = Res.string.app_update_available
    }
}

@Composable
internal fun AppUpdateContent(
    installedVersion: String,
    findUpdate: suspend () -> String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(installedVersion) { AppUpdateState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.check(findUpdate) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { state.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.onResume() }
    val background =
        if (state.availableUrl != null) {
            MaterialTheme.colorScheme.StatusGreen.copy(alpha = 0.18f)
        } else {
            Color.Transparent
        }
    ListItem(
        modifier = modifier.background(background, MaterialTheme.shapes.medium),
        text = stringResource(Res.string.app_update_check),
        supportingText =
        stringResource(Res.string.app_installed_version, installedVersion) + "\n" + stringResource(state.status),
        enabled = !state.checking && !state.opening,
        onClick = {
            if (!state.checking && !state.opening) {
                if (state.availableUrl != null) {
                    state.open(onOpen)
                } else {
                    scope.launch { state.check(findUpdate) }
                }
            }
        },
    )
}
