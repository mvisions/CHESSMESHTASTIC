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

import org.meshtastic.core.network.AppUpdateRelease

interface AppUpdateInstaller {
    suspend fun installedVersion(): String

    suspend fun pendingRelease(): AppUpdateRelease?

    suspend fun download(release: AppUpdateRelease)

    suspend fun progress(release: AppUpdateRelease): AppUpdateProgress

    suspend fun canInstall(): Boolean

    suspend fun install(release: AppUpdateRelease): AppUpdateInstallResult
}

sealed interface AppUpdateProgress {
    data class Downloading(val downloaded: Long, val total: Long) : AppUpdateProgress {
        val fraction: Float
            get() = if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    data object Missing : AppUpdateProgress

    data object Verifying : AppUpdateProgress

    data object Ready : AppUpdateProgress

    data object Installed : AppUpdateProgress
}

enum class AppUpdateInstallResult {
    OPENED,
    PERMISSION_REQUIRED,
}
