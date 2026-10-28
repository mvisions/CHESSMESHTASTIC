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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.network.AppUpdateRelease
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_installed_version
import org.meshtastic.core.resources.app_update_check
import org.meshtastic.core.resources.app_update_checking
import org.meshtastic.core.resources.app_update_current
import org.meshtastic.core.resources.app_update_download
import org.meshtastic.core.resources.app_update_downloading
import org.meshtastic.core.resources.app_update_error
import org.meshtastic.core.resources.app_update_install
import org.meshtastic.core.resources.app_update_installed
import org.meshtastic.core.resources.app_update_not_installed
import org.meshtastic.core.resources.app_update_permission
import org.meshtastic.core.resources.app_update_ready
import org.meshtastic.core.resources.app_update_target
import org.meshtastic.core.resources.app_update_verifying
import org.meshtastic.core.ui.component.ListItem
import org.meshtastic.core.ui.theme.StatusColors.StatusGreen
import kotlin.time.Duration.Companion.seconds

internal class AppUpdateState(
    private val installer: AppUpdateInstaller,
    initialVersion: String,
    private val findUpdate: suspend (String) -> AppUpdateRelease?,
) {
    var installedVersion by mutableStateOf(initialVersion)
        private set

    var release by mutableStateOf<AppUpdateRelease?>(null)
        private set

    var progress by mutableStateOf<AppUpdateProgress>(AppUpdateProgress.Missing)
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf(Res.string.app_update_checking)
        private set

    var installing by mutableStateOf(false)
        private set

    private var pausedForInstall = false

    suspend fun check() = guarded {
        installedVersion = installer.installedVersion()
        val pending = installer.pendingRelease()
        if (pending != null) {
            release = pending
            refreshProgress(pending)
            if (progress is AppUpdateProgress.Downloading || progress == AppUpdateProgress.Ready) return@guarded
            if (progress == AppUpdateProgress.Installed) release = null
        }
        val next = findUpdate(installedVersion)
        if (progress !is AppUpdateProgress.Downloading && progress != AppUpdateProgress.Ready) {
            release = next
            if (next != null) {
                progress = AppUpdateProgress.Missing
                status = Res.string.app_update_target
            } else if (progress != AppUpdateProgress.Installed) {
                status = Res.string.app_update_current
            }
        }
    }

    suspend fun download() = guarded {
        val target = checkNotNull(release) { "No update selected" }
        installer.download(target)
        progress = AppUpdateProgress.Downloading(0, target.size)
        status = Res.string.app_update_downloading
    }

    suspend fun refresh() = guarded {
        installedVersion = installer.installedVersion()
        release?.let { target ->
            if (installer.pendingRelease() == target || target.isInstalled(installedVersion)) {
                refreshProgress(target)
            }
        }
        if (installing && pausedForInstall) {
            installing = false
            pausedForInstall = false
            if (progress != AppUpdateProgress.Installed) status = Res.string.app_update_not_installed
        }
    }

    suspend fun install() = guarded {
        val target = checkNotNull(release) { "No update selected" }
        check(progress == AppUpdateProgress.Ready) { "Update is not verified" }
        when (installer.install(target)) {
            AppUpdateInstallResult.OPENED -> {
                installing = true
                status = Res.string.app_update_not_installed
            }

            AppUpdateInstallResult.PERMISSION_REQUIRED -> status = Res.string.app_update_permission
        }
    }

    fun onPause() {
        if (installing) pausedForInstall = true
    }

    private suspend fun refreshProgress(target: AppUpdateRelease) {
        val previous = progress
        if (previous is AppUpdateProgress.Downloading) {
            progress = AppUpdateProgress.Verifying
            status = Res.string.app_update_verifying
        }
        progress = installer.progress(target)
        status =
            when (progress) {
                is AppUpdateProgress.Downloading -> Res.string.app_update_downloading
                AppUpdateProgress.Missing -> Res.string.app_update_target
                AppUpdateProgress.Verifying -> Res.string.app_update_verifying
                AppUpdateProgress.Ready -> Res.string.app_update_ready
                AppUpdateProgress.Installed -> Res.string.app_update_installed
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(action: suspend () -> Unit) {
        if (busy || (installing && !pausedForInstall)) return
        busy = true
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(error) { "Application update operation failed" }
            status = Res.string.app_update_error
            installing = false
            progress = AppUpdateProgress.Missing
        } finally {
            busy = false
        }
    }
}

@Composable
internal fun AppUpdateContent(
    installedVersion: String,
    installer: AppUpdateInstaller,
    findUpdate: suspend (String) -> AppUpdateRelease?,
    modifier: Modifier = Modifier,
) {
    val state = remember(installer, installedVersion) { AppUpdateState(installer, installedVersion, findUpdate) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.check() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { state.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { state.refresh() } }
    val downloading = state.progress as? AppUpdateProgress.Downloading
    val polling = downloading != null || state.progress == AppUpdateProgress.Verifying
    LaunchedEffect(state, polling) {
        while (state.progress is AppUpdateProgress.Downloading || state.progress == AppUpdateProgress.Verifying) {
            delay(1.seconds)
            state.refresh()
        }
    }
    val actionable = state.release != null && state.progress != AppUpdateProgress.Installed
    Column(
        modifier.background(
            if (actionable) MaterialTheme.colorScheme.StatusGreen.copy(alpha = 0.18f) else Color.Transparent,
            MaterialTheme.shapes.medium,
        ),
    ) {
        ListItem(
            text = stringResource(if (!actionable) Res.string.app_update_check else updateAction(state.progress)),
            supportingText = updateDescription(state),
            enabled = !state.busy && !state.installing && downloading == null,
            onClick = {
                scope.launch {
                    when {
                        state.progress == AppUpdateProgress.Ready -> state.install()
                        actionable -> state.download()
                        else -> state.check()
                    }
                }
            },
        )
        when {
            downloading != null -> {
                LinearProgressIndicator(
                    progress = { downloading.fraction },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            state.progress == AppUpdateProgress.Verifying -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun updateAction(progress: AppUpdateProgress): StringResource = when (progress) {
    AppUpdateProgress.Ready -> Res.string.app_update_install
    AppUpdateProgress.Missing -> Res.string.app_update_download
    else -> Res.string.app_update_check
}

@Composable
private fun updateDescription(state: AppUpdateState): String {
    val target = state.release
    val detail =
        if (state.status == Res.string.app_update_target && target != null) {
            stringResource(Res.string.app_update_target, target.version)
        } else if (state.status == Res.string.app_update_downloading) {
            val fraction = (state.progress as? AppUpdateProgress.Downloading)?.fraction ?: 0f
            stringResource(Res.string.app_update_downloading, (fraction * PERCENT_SCALE).toInt())
        } else {
            stringResource(state.status)
        }

    return stringResource(Res.string.app_installed_version, state.installedVersion) +
        target?.let { "\n${it.fileName}" }.orEmpty() +
        "\n" +
        detail
}

private const val PERCENT_SCALE = 100
