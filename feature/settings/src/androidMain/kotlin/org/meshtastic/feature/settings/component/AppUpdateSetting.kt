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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import org.koin.compose.koinInject
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.network.AppReleaseRemoteDataSource

@Composable
fun AppUpdateSetting(modifier: Modifier = Modifier) {
    val source = koinInject<AppReleaseRemoteDataSource>()
    val build = koinInject<BuildConfigProvider>()
    val uriHandler = LocalUriHandler.current
    AppUpdateContent(
        modifier = modifier,
        installedVersion = build.versionName,
        findUpdate = { source.findUpdate(build.versionName, arm64 = "arm64-v8a" in Build.SUPPORTED_ABIS) },
        onOpen = uriHandler::openUri,
    )
}
