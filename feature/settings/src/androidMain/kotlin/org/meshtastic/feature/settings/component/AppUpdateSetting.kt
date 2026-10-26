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

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.network.AppReleaseRemoteDataSource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_installed_version
import org.meshtastic.core.resources.app_update_check
import org.meshtastic.core.resources.app_update_checking
import org.meshtastic.core.resources.app_update_current
import org.meshtastic.core.resources.app_update_error
import org.meshtastic.core.resources.app_update_opening
import org.meshtastic.core.ui.component.ListItem

@Composable
@Suppress("TooGenericExceptionCaught") // UI boundary reports transport, decoding and browser failures.
fun AppUpdateSetting(modifier: Modifier = Modifier) {
    val source = koinInject<AppReleaseRemoteDataSource>()
    val build = koinInject<BuildConfigProvider>()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(Res.string.app_update_check) }
    ListItem(
        modifier = modifier,
        text = stringResource(Res.string.app_update_check),
        supportingText =
        stringResource(Res.string.app_installed_version, build.versionName) + "\n" + stringResource(status),
        enabled = !checking,
        onClick = {
            checking = true
            status = Res.string.app_update_checking
            scope.launch {
                try {
                    val url = source.findUpdate(build.versionName, arm64 = "arm64-v8a" in Build.SUPPORTED_ABIS)
                    status =
                        if (url == null) {
                            Res.string.app_update_current
                        } else {
                            uriHandler.openUri(url)
                            Res.string.app_update_opening
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logger.e(error) { "Unable to check or open application update" }
                    status = Res.string.app_update_error
                } finally {
                    checking = false
                }
            }
        },
    )
}
