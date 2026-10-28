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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.network.AppUpdateRelease
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_update_error
import org.meshtastic.core.resources.app_update_installed
import org.meshtastic.core.resources.app_update_installing
import org.meshtastic.core.resources.app_update_not_installed
import org.meshtastic.core.resources.app_update_permission
import org.meshtastic.core.ui.util.AlertManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppUpdateContentTest {
    private val release =
        AppUpdateRelease(
            "6.4",
            "https://github.com/mvisions/Meshtastic-Android/releases/download/media-links-test-6.4/Meshtastic-media-arm64.apk",
            100,
            "a".repeat(64),
        )

    @Test
    fun confirmedUpdateAutomaticallyInstallsOnceAndCancellationDoesNotRetry() = runTest {
        val fake = FakeInstaller()
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        val gate = CompletableDeferred<Unit>()
        fake.downloadGate = gate
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { state.update(release) }
        repeat(5) { state.update(release) }
        assertEquals(1, fake.downloads)
        assertEquals(0, fake.installs)
        gate.complete(Unit)
        job.join()
        assertTrue(state.progress is AppUpdateProgress.Downloading)
        fake.result = AppUpdateProgress.Ready
        state.refresh()
        repeat(5) { state.refresh() }
        assertEquals(1, fake.installs)
        assertEquals(Res.string.app_update_installing, state.status)
        state.onPause()
        state.onResume()
        assertFalse(state.installing)
        assertEquals(Res.string.app_update_not_installed, state.status)
        repeat(3) { state.refresh() }
        assertEquals(1, fake.installs)
    }

    @Test
    fun recreationRestoresDownloadAndConfirmsActualInstalledVersion() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Downloading(50, 100)
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        assertEquals(AppUpdateProgress.Downloading(50, 100), state.progress)
        fake.version = "2.8.4-media-part6.4"
        state.refresh()
        assertEquals(Res.string.app_update_installed, state.status)
        assertEquals(fake.version, state.installedVersion)
    }

    @Test
    fun grantingPermissionAutomaticallyContinuesWithoutClaimingSuccess() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Ready
        fake.installResult = AppUpdateInstallResult.PERMISSION_REQUIRED
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.update(release)
        assertEquals(Res.string.app_update_permission, state.status)
        assertFalse(state.installing)
        state.onPause()
        fake.permission = true
        fake.installResult = AppUpdateInstallResult.OPENED
        state.onResume()
        assertEquals(2, fake.installs)
        assertTrue(state.installing)
        assertEquals(Res.string.app_update_installing, state.status)
        assertEquals(AppUpdateProgress.Ready, state.progress)
    }

    @Test
    fun deniedPermissionDoesNotReopenSettingsOrRetryAutomatically() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Ready
        fake.installResult = AppUpdateInstallResult.PERMISSION_REQUIRED
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.update(release)
        state.onPause()
        state.onResume()
        repeat(3) { state.onResume() }
        assertEquals(1, fake.installs)
        assertFalse(state.installing)
        assertEquals(Res.string.app_update_permission, state.status)
    }

    @Test
    fun completedDownloadWaitsForForegroundThenInstallsOnce() = runTest {
        val fake = FakeInstaller()
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.update(release)
        state.onPause()
        fake.result = AppUpdateProgress.Ready
        state.refresh()
        assertEquals(0, fake.installs)
        state.onResume()
        assertEquals(1, fake.installs)
    }

    @Test
    fun savedConfirmationResumesOnlyTheSameRelease() = runTest {
        val fake = FakeInstaller()
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.update(release)
        val saved = state.saveAutomaticUpdate()
        fake.result = AppUpdateProgress.Ready
        val restored =
            AppUpdateState(
                fake,
                fake.version,
                kotlinx.serialization.json.Json.decodeFromString<AppUpdateRelease?>(saved[0]),
                saved[1].toBooleanStrict(),
                { release },
            )
        restored.check()
        assertEquals(1, fake.installs)
    }

    @Test
    fun restoredConfirmationDoesNotAuthorizeADifferentRelease() = runTest {
        val fake = FakeInstaller()
        fake.pending = release.copy(sha256 = "b".repeat(64))
        fake.result = AppUpdateProgress.Ready
        val state = AppUpdateState(fake, fake.version, automaticRelease = release) { release }
        state.check()
        assertEquals(0, fake.installs)
    }

    @Test
    fun returningFromPermissionWhileCheckIsBusyStillContinues() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Ready
        fake.permission = true
        val gate = CompletableDeferred<Unit>()
        fake.progressGate = gate
        val restored = AppUpdateState(fake, fake.version, release, awaitingPermission = true) { release }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { restored.check() }
        restored.onResume()
        gate.complete(Unit)
        job.join()
        assertEquals(1, fake.installs)
        assertTrue(restored.installing)
    }

    @Test
    fun verificationFailureAfterConfirmationDoesNotInstall() = runTest {
        val fake = FakeInstaller()
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.update(release)
        fake.failure = true
        state.refresh()
        assertEquals(Res.string.app_update_error, state.status)
        assertEquals(0, fake.installs)
    }

    @Test
    fun returningBeforeDownloadingDoesNotQueryANonexistentDownload() = runTest {
        val fake = FakeInstaller()
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        state.refresh()
        assertEquals(0, fake.progressQueries)
        assertEquals(AppUpdateProgress.Missing, state.progress)
        assertEquals(release, state.release)
        assertEquals(0, fake.downloads)
    }

    @Test
    fun failedVerificationIsVisibleAndDoesNotEnableInstaller() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.failure = true
        val state = AppUpdateState(fake, fake.version) { release }
        state.check()
        assertEquals(Res.string.app_update_error, state.status)
        assertEquals(AppUpdateProgress.Missing, state.progress)
        state.update(release)
        assertEquals(0, fake.installs)
    }

    @Test
    fun progressPercentageAndVersionedFilenameAreVisibleWithoutAutoInstalling() = runComposeUiTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Downloading(50, 100)
        setContent { MaterialTheme { AppUpdateContent(fake.version, fake, { release }, AlertManager()) } }
        onNodeWithText("Downloading update: 50%", substring = true).assertIsDisplayed()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f),
                ),
            )
        onNodeWithText(release.fileName, substring = true).assertIsDisplayed()
        runOnIdle { assertEquals(0, fake.installs) }
    }

    @Test
    fun progressBarRemainsDeterminateWhileDownloadStatusIsBeingQueried() = runComposeUiTest {
        mainClock.autoAdvance = false
        val fake = FakeInstaller()
        fake.pending = release
        fake.result = AppUpdateProgress.Downloading(50, 100)
        setContent { MaterialTheme { AppUpdateContent(fake.version, fake, { release }, AlertManager()) } }
        waitForIdle()
        val gate = CompletableDeferred<Unit>()
        runOnIdle {
            fake.progressGate = gate
            fake.result = AppUpdateProgress.Ready
        }
        mainClock.advanceTimeBy(1_200)
        waitForIdle()
        onNodeWithText("Downloading update: 50%", substring = true).assertIsDisplayed()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f),
                ),
            )
        runOnIdle { assertEquals(0, fake.installs) }
        runOnIdle { gate.complete(Unit) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("Install downloaded update").assertIsDisplayed()
        runOnIdle {
            assertTrue(fake.progressQueries >= 2)
            assertEquals(0, fake.installs)
        }
    }

    @Test
    fun failedDiscoveryAfterInstalledPendingUpdateRetriesDiscoveryNotDownload() = runTest {
        val fake = FakeInstaller()
        fake.pending = release
        fake.version = "2.8.4-media-part6.4"
        var checks = 0
        val state =
            AppUpdateState(fake, fake.version) {
                checks++
                error("Offline")
            }
        state.check()
        assertEquals(Res.string.app_update_error, state.status)
        assertEquals(null, state.release)
        state.check()
        assertEquals(2, checks)
        assertEquals(0, fake.downloads)
    }

    @Test
    fun availableUpdateOnlyDownloadsOnExplicitAction() = runComposeUiTest {
        val fake = FakeInstaller()
        val alerts = AlertManager()
        setContent { MaterialTheme { AppUpdateContent(fake.version, fake, { release }, alerts) } }
        onNodeWithText("Version 6.4 is available.", substring = true).assertIsDisplayed()
        runOnIdle { assertEquals(0, fake.downloads) }
        onNodeWithText("Update app").performClick()
        runOnIdle {
            assertEquals(0, fake.downloads)
            assertTrue(checkNotNull(alerts.currentAlert.value?.message).contains("version 6.4"))
            alerts.currentAlert.value?.onDismiss?.invoke()
        }
        runOnIdle { assertEquals(0, fake.downloads) }
        onNodeWithText("Update app").performClick()
        runOnIdle { checkNotNull(alerts.currentAlert.value?.onConfirm).invoke() }
        runOnIdle {
            assertEquals(1, fake.downloads)
            assertEquals(0, fake.installs)
        }
    }

    @Test
    fun disposingUpdateRowDoesNotCancelAConfirmedUpdateOrRepeatDiscovery() = runComposeUiTest {
        val fake = FakeInstaller()
        val alerts = AlertManager()
        val downloadGate = CompletableDeferred<Unit>()
        fake.downloadGate = downloadGate
        var checks = 0
        var rowComposed = false
        val showRow = mutableStateOf(true)
        setContent {
            MaterialTheme {
                AppUpdateHost(
                    fake.version,
                    fake,
                    {
                        checks++
                        release
                    },
                    alerts,
                ) { updateSetting ->
                    if (showRow.value) {
                        DisposableEffect(Unit) {
                            rowComposed = true
                            onDispose { rowComposed = false }
                        }
                        updateSetting()
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithText("Update app").performClick()
        waitUntil { alerts.currentAlert.value?.onConfirm != null }
        runOnIdle { checkNotNull(alerts.currentAlert.value?.onConfirm).invoke() }
        runOnIdle { assertEquals(1, fake.downloads) }
        mainClock.autoAdvance = false
        runOnIdle { showRow.value = false }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        runOnIdle {
            assertFalse(rowComposed)
            downloadGate.complete(Unit)
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        runOnIdle {
            assertEquals(release, fake.pending)
            fake.result = AppUpdateProgress.Ready
        }
        mainClock.advanceTimeBy(1_200)
        waitForIdle()
        runOnIdle {
            assertEquals(1, fake.installs)
            assertEquals(1, checks)
        }
        runOnIdle { showRow.value = true }
        mainClock.advanceTimeByFrame()
        onNodeWithText("Continue in the Android installer", substring = true).assertIsDisplayed()
        runOnIdle {
            assertTrue(rowComposed)
            assertEquals(1, fake.downloads)
            assertEquals(1, fake.installs)
            assertEquals(1, checks)
        }
    }

    @Test
    fun apkValidationRejectsCorruptForeignOldAndWrongSignerPackages() {
        val installed = AppUpdateApk("test.app", 1, "2.8.4-media-part6.3", setOf("cert"))
        val candidate = installed.copy(versionCode = 2, versionName = "2.8.4-media-part6.4 (2) fdroid")
        validateAppUpdate(release, 100, release.sha256, installed, candidate)
        for (
        invalid in
        listOf(
            candidate.copy(packageName = "another.app"),
            candidate.copy(versionCode = 1),
            candidate.copy(signerDigests = setOf("other")),
            candidate.copy(signerDigests = emptySet()),
            candidate.copy(versionName = "2.8.4-media-part6.40"),
        )
        ) {
            assertFailsWith<IllegalStateException> {
                validateAppUpdate(release, 100, release.sha256, installed, invalid)
            }
        }
        assertFailsWith<IllegalStateException> { validateAppUpdate(release, 99, release.sha256, installed, candidate) }
        assertFailsWith<IllegalStateException> { validateAppUpdate(release, 100, "b".repeat(64), installed, candidate) }
    }

    private class FakeInstaller : AppUpdateInstaller {
        var version = "2.8.4-media-part6.3"
        var pending: AppUpdateRelease? = null
        var result: AppUpdateProgress = AppUpdateProgress.Missing
        var downloads = 0
        var installs = 0
        var failure = false
        var progressQueries = 0
        var progressGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var installResult = AppUpdateInstallResult.OPENED
        var permission = false

        override suspend fun canInstall() = permission

        override suspend fun installedVersion() = version

        override suspend fun pendingRelease() = pending

        override suspend fun download(release: AppUpdateRelease) {
            downloads++
            downloadGate?.await()
            pending = release
            result = AppUpdateProgress.Downloading(0, release.size)
        }

        override suspend fun progress(release: AppUpdateRelease): AppUpdateProgress {
            progressQueries++
            check(pending == release) { "No pending download" }
            check(!failure) { "Invalid APK" }
            progressGate?.await()
            return if (release.isInstalled(version)) AppUpdateProgress.Installed else result
        }

        override suspend fun install(release: AppUpdateRelease): AppUpdateInstallResult {
            installs++
            return installResult
        }
    }
}
