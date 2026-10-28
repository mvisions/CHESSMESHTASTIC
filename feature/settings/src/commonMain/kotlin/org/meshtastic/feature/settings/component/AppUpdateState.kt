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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.network.AppUpdateRelease
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_installed_version
import org.meshtastic.core.resources.app_update_action
import org.meshtastic.core.resources.app_update_check
import org.meshtastic.core.resources.app_update_checking
import org.meshtastic.core.resources.app_update_confirm
import org.meshtastic.core.resources.app_update_current
import org.meshtastic.core.resources.app_update_downloading
import org.meshtastic.core.resources.app_update_error
import org.meshtastic.core.resources.app_update_install
import org.meshtastic.core.resources.app_update_installed
import org.meshtastic.core.resources.app_update_installing
import org.meshtastic.core.resources.app_update_not_installed
import org.meshtastic.core.resources.app_update_permission
import org.meshtastic.core.resources.app_update_ready
import org.meshtastic.core.resources.app_update_target
import org.meshtastic.core.resources.app_update_verifying
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.ui.component.ListItem
import org.meshtastic.core.ui.theme.StatusColors.StatusGreen
import org.meshtastic.core.ui.util.AlertManager
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.compose.resources.getString as getStringSuspend

internal class AppUpdateState(
    private val installer: AppUpdateInstaller,
    initialVersion: String,
    private var automaticRelease: AppUpdateRelease? = null,
    private var awaitingPermission: Boolean = false,
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
    private var foreground = true
    private var resumePending = false

    fun saveAutomaticUpdate(): List<String> = listOf(
        Json.encodeToString(automaticRelease),
        awaitingPermission.toString(),
    )

    suspend fun check() = guarded {
        installedVersion = installer.installedVersion()
        val pending = installer.pendingRelease()
        if (pending != null) {
            release = pending
            refreshProgress(pending)
            continueInstallation()
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

    suspend fun update(target: AppUpdateRelease) = guarded {
        check(release == target) { "Selected update has changed; confirm the new version" }
        check(progress == AppUpdateProgress.Missing || progress == AppUpdateProgress.Ready) {
            "Update is already in progress"
        }
        automaticRelease = target
        awaitingPermission = false
        if (progress == AppUpdateProgress.Ready) {
            continueInstallation()
        } else {
            installer.download(target)
            progress = AppUpdateProgress.Downloading(0, target.size)
            status = Res.string.app_update_downloading
        }
    }

    suspend fun refresh(resumed: Boolean = false) = guarded {
        installedVersion = installer.installedVersion()
        release?.let { target ->
            if (installer.pendingRelease() == target || target.isInstalled(installedVersion)) {
                refreshProgress(target)
            }
        }
        if (resumed && installing && pausedForInstall) {
            installing = false
            pausedForInstall = false
            if (progress != AppUpdateProgress.Installed) status = Res.string.app_update_not_installed
        }
        if (awaitingPermission && resumed) {
            awaitingPermission = false
            if (!installer.canInstall()) {
                automaticRelease = null
                status = Res.string.app_update_permission
            }
        }
        continueInstallation()
    }

    private suspend fun continueInstallation() {
        if (progress == AppUpdateProgress.Installed) {
            automaticRelease = null
            awaitingPermission = false
        }
        val target = automaticRelease
        if (target == null || release != target) return
        if (!foreground || installing || awaitingPermission || progress != AppUpdateProgress.Ready) {
            return
        }
        when (installer.install(target)) {
            AppUpdateInstallResult.OPENED -> {
                automaticRelease = null
                installing = true
                status = Res.string.app_update_installing
            }

            AppUpdateInstallResult.PERMISSION_REQUIRED -> {
                awaitingPermission = true
                status = Res.string.app_update_permission
            }
        }
    }

    suspend fun onResume() {
        foreground = true
        if (busy) {
            resumePending = true
            return
        }
        refresh(resumed = true)
    }

    fun onPause() {
        foreground = false
        if (installing) pausedForInstall = true
    }

    private suspend fun refreshProgress(target: AppUpdateRelease) {
        progress = installer.progress(target)
        status =
            when (progress) {
                is AppUpdateProgress.Downloading -> Res.string.app_update_downloading

                AppUpdateProgress.Missing -> Res.string.app_update_target

                AppUpdateProgress.Verifying -> Res.string.app_update_verifying

                AppUpdateProgress.Ready ->
                    when (status) {
                        Res.string.app_update_permission,
                        Res.string.app_update_not_installed,
                        -> status

                        else -> Res.string.app_update_ready
                    }

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
            automaticRelease = null
            awaitingPermission = false
            installing = false
            progress = AppUpdateProgress.Missing
        } finally {
            busy = false
        }
        if (resumePending && foreground) {
            resumePending = false
            onResume()
        }
    }
}

@Composable
internal fun AppUpdateContent(
    installedVersion: String,
    installer: AppUpdateInstaller,
    findUpdate: suspend (String) -> AppUpdateRelease?,
    alertManager: AlertManager,
    modifier: Modifier = Modifier,
) {
    val state =
        rememberSaveable(
            installer,
            installedVersion,
            saver =
            listSaver(
                save = { it.saveAutomaticUpdate() },
                restore = {
                    AppUpdateState(
                        installer,
                        installedVersion,
                        Json.decodeFromString<AppUpdateRelease?>(it[0]),
                        it[1].toBooleanStrict(),
                        findUpdate,
                    )
                },
            ),
        ) {
            AppUpdateState(installer, installedVersion, findUpdate = findUpdate)
        }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.check() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { state.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { state.onResume() } }
    val downloading = state.progress as? AppUpdateProgress.Downloading
    val polling = downloading != null || state.progress == AppUpdateProgress.Verifying
    LaunchedEffect(state, polling) {
        while (state.progress is AppUpdateProgress.Downloading || state.progress == AppUpdateProgress.Verifying) {
            delay(1.seconds)
            state.refresh()
        }
    }
    val actionable = state.release != null && state.progress != AppUpdateProgress.Installed
    val highlight = updateHighlight(actionable && !state.busy && !state.installing && !polling)
    Column(
        modifier.background(
            if (actionable) MaterialTheme.colorScheme.StatusGreen.copy(alpha = highlight) else Color.Transparent,
            MaterialTheme.shapes.medium,
        ),
    ) {
        ListItem(
            text = stringResource(if (!actionable) Res.string.app_update_check else updateAction(state.progress)),
            supportingText = updateDescription(state),
            enabled = !state.busy && !state.installing && !polling,
            onClick = {
                scope.launch {
                    when {
                        actionable -> {
                            val target = checkNotNull(state.release)
                            alertManager.confirmUpdate(target) { scope.launch { state.update(target) } }
                        }

                        else -> state.check()
                    }
                }
            },
        )
        when {
            downloading != null -> {
                AppUpdateDownloadProgress(downloading.fraction)
            }

            state.progress == AppUpdateProgress.Verifying -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun updateHighlight(pulsing: Boolean): Float {
    if (!pulsing) return 0.18f
    val transition = rememberInfiniteTransition(label = "Available app update")
    val alpha by
        transition.animateFloat(
            initialValue = 0.12f,
            targetValue = 0.28f,
            animationSpec = infiniteRepeatable(tween(UPDATE_PULSE_MILLIS), RepeatMode.Reverse),
            label = "Update highlight",
        )
    return alpha
}

@Composable
private fun AppUpdateDownloadProgress(fraction: Float) {
    val progress by
        animateFloatAsState(
            fraction,
            animationSpec = tween(DOWNLOAD_PROGRESS_MILLIS),
            label = "APK download progress",
        )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

private suspend fun AlertManager.confirmUpdate(target: AppUpdateRelease, onConfirm: () -> Unit) {
    showAlert(
        titleRes = Res.string.app_update_action,
        message = getStringSuspend(Res.string.app_update_confirm, target.version),
        confirmTextRes = Res.string.app_update_action,
        dismissTextRes = Res.string.cancel,
        onConfirm = onConfirm,
    )
}

private fun updateAction(progress: AppUpdateProgress): StringResource = when (progress) {
    AppUpdateProgress.Ready -> Res.string.app_update_install
    AppUpdateProgress.Missing -> Res.string.app_update_action
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
private const val UPDATE_PULSE_MILLIS = 1_200
private const val DOWNLOAD_PROGRESS_MILLIS = 700
